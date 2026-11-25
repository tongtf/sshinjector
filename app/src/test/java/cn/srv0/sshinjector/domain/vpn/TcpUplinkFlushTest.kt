package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * P1-8 上行 flush: 写锁 + 队列优先 + "写不动就结束本轮, 不自旋"。
 *
 * 三条历史故障形态都在这一个文件里锁死:
 *
 * 1. `flushPendingUpstream` 旧实现在 `write==0` / `IOException` 之后把数据放回队首**继续
 *    `while(true)`** —— 同一段必然失败/必然写不动的数据被无限重试, packetLoop (或建连协程)
 *    被一条连接钉死。用例 ①③ 断言"单轮结束", 用例 ② 用 500ms 上限的 latch 断言"没有自旋"。
 * 2. `markEstablished` 早于 flush 置 Established, packetLoop 的新数据可以走 `forwardToSocks`
 *    直接写通道 —— 旧队列被**越过**, 隧道侧字节序错乱 (本栈没有重排也没有隧道级重传,
 *    一次错位 = 这条连接永久坏掉)。用例 ④ 断言对端先收到队列里的旧数据。
 * 3. 建连协程与 packetLoop 两条线程都可能写 `socksChannel`, 不串行化会把两段字节交错
 *    写进隧道。队列顺序 + 记账只在 [UplinkDrain] 里做 (与 SocketChannel 解耦, 见该 KDoc),
 *    用例 ①② 用脚本化的 write 精确覆盖这两条路径。
 *
 * 所有等待都有界: 只有 `latch.await(500ms)` 与带 deadline 的 socket 读, 没有无界 while/sleep。
 */
class TcpUplinkFlushTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")
    private val serverIp = InetAddress.getByName("93.184.216.34")
    private val srcPort = 41000
    private val dstPort = 443

    // ---- ① 写 0 一次后恢复: 数据按序送达, forwardedBytes 只在真写后推进, 不重复 ----
    @Test
    fun `write that returns0 keeps bytes queued and never advances forwardedBytes`() {
        val conn = newConnection()
        conn.queueUpstream("AAA")
        val received = ByteArrayOutputStream()
        // 第一块写不动 (非阻塞写返回 0), 之后正常接收
        val stalled = AtomicReference(true)
        val write: (ByteBuffer) -> Int = { buf ->
            if (stalled.getAndSet(false)) {
                0
            } else {
                val n = buf.remaining()
                val copy = ByteArray(n)
                buf.get(copy)
                received.write(copy)
                n
            }
        }

        val first = UplinkDrain.drain(conn, write)
        assertFalse("写 0 不能当排空", first.drained)
        assertEquals("写 0 不得推进 forwardedBytes", 0L, conn.forwardedBytes)
        assertEquals("未写部分必须留在队首", 3L, conn.pendingUpstreamBytes)

        val second = UplinkDrain.drain(conn, write)
        assertTrue("恢复后必须一轮排空", second.drained)
        assertEquals(3, second.written)
        assertEquals("只在真写之后推进", 3L, conn.forwardedBytes)
        assertEquals("写入内容", "AAA", String(received.toByteArray(), Charsets.US_ASCII))

        // 第三次: 队列已空 → 不重复写 (旧实现在这里也可能把同一段再发一遍)
        val third = UplinkDrain.drain(conn, write)
        assertTrue(third.drained)
        assertEquals(0, third.written)
        assertEquals("不得重复写", "AAA", String(received.toByteArray(), Charsets.US_ASCII))
        assertEquals(3L, conn.forwardedBytes)
        assertEquals(0L, conn.pendingUpstreamBytes)
    }

    // ---- ①' 部分写: 已写部分记账, 未写部分留在队首, 续写不重复 ----
    @Test
    fun `partial write books only the written bytes and resumes from the queue head`() {
        val conn = newConnection()
        conn.queueUpstream("AAA", "BBB")
        val received = ByteArrayOutputStream()
        // 第一轮总共只接受 1B (之后 write 返回 0 = 缓冲满); 第二轮全收
        var budget = 1
        val write: (ByteBuffer) -> Int = { buf ->
            val n = minOf(budget, buf.remaining())
            if (n <= 0) {
                0
            } else {
                budget -= n
                val copy = ByteArray(n)
                buf.get(copy)
                received.write(copy)
                n
            }
        }

        val first = UplinkDrain.drain(conn, write)
        assertFalse(first.drained)
        assertEquals(1, first.written)
        assertEquals("只按真实写入推进", 1L, conn.forwardedBytes)
        assertEquals("未写的 5B 留在队首", 5L, conn.pendingUpstreamBytes)

        budget = Int.MAX_VALUE
        val second = UplinkDrain.drain(conn, write)
        assertTrue(second.drained)
        assertEquals(5, second.written)
        assertEquals(6L, conn.forwardedBytes)
        assertEquals("续写内容", "AAABBB", String(received.toByteArray(), Charsets.US_ASCII))
        assertEquals(0L, conn.pendingUpstreamBytes)
    }

    // ---- ② 通道坏掉: 关闭连接并立即返回 (旧实现无限自旋) ----
    @Test
    fun `write failure closes the connection and returns instead of spinning`() {
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(tunPackets)
        val conn = newConnection()
        conn.queueUpstream("AAA")
        // 已关闭的 SocketChannel: 任何 write 都抛 ClosedChannelException (IOException),
        // 与"SSH 通道被远端/本栈关掉"这一现场同形。
        conn.socksChannel = SocketChannel.open().apply { close() }

        val returned = AtomicReference<Boolean>()
        val done = CountDownLatch(1)
        Thread {
            try {
                returned.set(sm.flushPendingUpstream(conn))
            } finally {
                done.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(
            "写失败必须立即返回: 旧实现在 IOException 后把数据放回队首继续 while(true), 同一条" +
                "必然失败的写会无限循环 (packetLoop/建连协程被永久钉住)",
            done.await(500, TimeUnit.MILLISECONDS),
        )
        assertFalse("通道坏了 = 未排空", returned.get())
        assertEquals(
            "写失败必须关闭连接 (数据已无法交付, 让客户端重连)",
            TcpStateMachine.TcpConnection.TcpState.Closed,
            conn.state,
        )
        assertEquals("关闭包 (RST) 必须发出一次", 1, tunPackets.size)
    }

    // ---- ③ 队列非空时新数据不得越过队列 (顺序断言) ----
    @Test
    fun `new data must not overtake queued upstream data`() {
        ServerSocket(0).use { listener ->
            val peerResult = AtomicReference<Socket>()
            val accepted = CountDownLatch(1)
            Thread {
                try {
                    peerResult.set(listener.accept().apply { soTimeout = SOCKET_TIMEOUT_MS.toInt() })
                } catch (_: Exception) {
                } finally {
                    accepted.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

            val channel = SocketChannel.open(InetSocketAddress("127.0.0.1", listener.localPort))
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            try {
                channel.configureBlocking(false)
                val conn = newConnection()
                conn.socksChannel = channel
                // 建连期间缓存下来的上行数据 (客户端在 SYN_SENT 后提前发来的请求)
                conn.queueUpstream("OLD")
                val sm = newStateMachine()

                // 此刻到达的包 seq 仍是排空前的期望值 = 队列里那段数据的重传:
                // 排空并 ACK 之后它已过期, 必须丢弃 (再写一遍就是重复上行)
                val stale = sm.forwardToSocks(conn, ByteBuffer.wrap("NEW".toByteArray()), 0, 3, payloadSeq = 1001L)
                assertEquals("已由排空 ACK 覆盖的重传段不得再写", 0, stale)
                assertEquals(
                    "对端只应收到队列里的旧数据一次",
                    "OLD",
                    String(readExactly(peerResult.get(), 3, SOCKET_TIMEOUT_MS), Charsets.US_ASCII),
                )
                assertEquals("排空写出的旧数据要记账", 3L, conn.forwardedBytes)

                // 真正的下一条新数据 (seq = 1001 + 3) 正常写出, 且不会重复
                val fresh = sm.forwardToSocks(conn, ByteBuffer.wrap("NEW".toByteArray()), 0, 3, payloadSeq = 1004L)
                assertEquals(3, fresh)
                assertEquals(
                    "旧数据在前、新数据在后",
                    "NEW",
                    String(readExactly(peerResult.get(), 3, SOCKET_TIMEOUT_MS), Charsets.US_ASCII),
                )
                // 直写由调用方记账 (生产在 handleRead 里), 这里模拟同一口径
                conn.forwardedBytes += fresh
                assertEquals(6L, conn.forwardedBytes)
            } finally {
                runCatching { channel.close() }
            }
        }
    }

    // ---- ③' 排空进行中: 新数据一律不写、不 ACK (并发排空会打乱字节序) ----
    @Test
    fun `new data waits while another thread is draining the queue`() {
        ServerSocket(0).use { listener ->
            val peerResult = AtomicReference<Socket>()
            val accepted = CountDownLatch(1)
            Thread {
                try {
                    peerResult.set(listener.accept().apply { soTimeout = 300 })
                } catch (_: Exception) {
                } finally {
                    accepted.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }

            val channel = SocketChannel.open(InetSocketAddress("127.0.0.1", listener.localPort))
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            try {
                channel.configureBlocking(false)
                val tunPackets = CopyOnWriteArrayList<ByteArray>()
                val conn = newConnection()
                conn.socksChannel = channel
                conn.queueUpstream("OLD")
                // 另一条路径正在排空 (建连协程的 flush): 这条连接此刻必须"什么都不写"
                conn.uplinkFlushing = true
                val sm = newStateMachine(tunPackets)

                val written = sm.forwardToSocks(conn, ByteBuffer.wrap("NEW".toByteArray()), 0, 3, payloadSeq = 1001L)

                assertEquals("排空进行中: 新数据不得越过队列", 0, written)
                assertEquals("不推进 forwardedBytes (客户端会重传这一段)", 0L, conn.forwardedBytes)
                assertTrue("不得 ACK: ACK 会让客户端误以为这段已送达", tunPackets.isEmpty())
                assertEquals("对端不该收到任何字节", 0, readAvailable(peerResult.get()))
            } finally {
                runCatching { channel.close() }
            }
        }
    }

    private fun newConnection() =
        TcpStateMachine
            .TcpConnection(
                id = 1L,
                srcIp = clientIp,
                dstIp = serverIp,
                srcPort = srcPort,
                dstPort = dstPort,
            ).apply {
                state = TcpStateMachine.TcpConnection.TcpState.Established
                browserSeq = 1000
                serverSeq = 5000
                synAckSeq = 4000
                synAckWritten = true
            }

    /** 按真实口径入队 (与 `TcpStateMachine.bufferPendingUpstream` 同锁同计数)。 */
    private fun TcpStateMachine.TcpConnection.queueUpstream(vararg chunks: String) {
        synchronized(pendingUpstreamLock) {
            chunks.forEach { text ->
                val bytes = text.toByteArray(Charsets.US_ASCII)
                pendingUpstream.addLast(bytes)
                pendingUpstreamBytes += bytes.size.toLong()
            }
        }
    }

    private fun newStateMachine(packets: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()): TcpStateMachine =
        TcpStateMachine(
            mock<TunnelManager>(),
            { tunSinkOf { p: ByteArray -> packets.add(p) } },
            PacketStats(),
            SshIoDispatcher(),
        )

    /** 从对端 socket 精确读 n 字节 (阻塞 + SO_TIMEOUT, 超时即失败)。 */
    private fun readExactly(
        socket: Socket,
        n: Int,
        timeoutMs: Long,
    ): ByteArray {
        val out = ByteArray(n)
        var off = 0
        val input = socket.getInputStream()
        while (off < n) {
            val read =
                try {
                    input.read(out, off, n - off)
                } catch (e: SocketTimeoutException) {
                    throw AssertionError("对端在 ${timeoutMs}ms 内只读到 $off/$n 字节 (顺序断言失败)", e)
                }
            if (read < 0) throw AssertionError("对端提前 EOF: $off/$n")
            off += read
        }
        return out
    }

    /** 有界地读一下对端是否有字节 (300ms SO_TIMEOUT + 非阻塞探测)。 */
    private fun readAvailable(socket: Socket): Int {
        val buf = ByteArray(64)
        return try {
            socket.getInputStream().read(buf)
        } catch (_: SocketTimeoutException) {
            0
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 3000L
    }
}
