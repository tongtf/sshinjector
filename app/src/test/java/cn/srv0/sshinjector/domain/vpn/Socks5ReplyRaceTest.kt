package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * P1-9: `sendReply` (sshIo/回程线程: `pendingWrites.add` + 置 OP_WRITE) 与 `handleWrite`
 * (eventLoop: 队列空 → 清 OP_WRITE) 的竞态。
 *
 * 旧实现在锁**外**判空、锁内清位: 如果判空恰好在 `add` 之前、清位恰好在置位之后,
 * 这条应答就永远躺在 `pendingWrites` 里没人写 —— 客户端表现为"连接悬挂", 而所有计数器
 * 都是干净的 (与"远端不回话"同形, 是最难排的一类)。
 *
 * 这里用真实 loopback socket + 假隧道把这条路径压出来: 隧道下行按块供给, relay 每读到
 * 一块就在**回程线程**调一次 `sendReply`; 客户端持续读, eventLoop 的 `handleWrite` 不停
 * 清 OP_WRITE。10k 块是上限, 30s 是整条用例的硬 deadline (所有读都有界, 没有无界 while)。
 */
class Socks5ReplyRaceTest {
    private lateinit var server: Socks5ProxyServer
    private lateinit var tunnel: ChunkTunnel
    private var port = 0

    @Before
    fun setUp() =
        runBlocking {
            tunnel = ChunkTunnel()
            val factory = mock<SshChannelFactory>()
            whenever(factory.createDirectChannel(any(), any())).thenReturn(tunnel)
            server = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            server.setExpectedAuth(USER, PASS)
            port = server.start(0, "127.0.0.1").getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            tunnel.closeInput()
            server.stop()
        }

    @Test
    fun `every reply reaches the client while OP_WRITE is being cleared concurrently`() {
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        val sock = SocketChannel.open()
        sock.configureBlocking(true)
        sock.connect(InetSocketAddress("127.0.0.1", port))
        sock.configureBlocking(false)
        try {
            // 前三个应答分别在建连的**三个阶段**写出 (方法选择 / 认证 / CONNECT 成功),
            // 其中 CONNECT 成功那条来自 sshIo 线程, 天然与 eventLoop 交错。
            writeFully(sock, byteArrayOf(0x05, 0x01, 0x02), deadline)
            assertBytes("方法选择应答", byteArrayOf(0x05, 0x02), readExactly(sock, 2, deadline))

            val auth = buildAuthRequest(USER, PASS)
            writeFully(sock, auth, deadline)
            assertBytes("认证应答", byteArrayOf(0x01, 0x00), readExactly(sock, 2, deadline))

            writeFully(sock, CONNECT_REQUEST, deadline)
            val connectReply = readExactly(sock, 10, deadline)
            assertEquals("CONNECT 必须成功", 0x00, connectReply[1].toInt() and 0xFF)

            // 回程: 每块 = relay 的一次 read = 一次 sendReply。
            val feeder =
                Thread {
                    for (i in 0 until ROUNDS) {
                        tunnel.offer(chunkAt(i))
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            val got = readExactly(sock, ROUNDS * CHUNK, deadline)
            feeder.join(FEEDER_JOIN_MS)

            // 逐字节比对: 丢掉的是整块 (被误清 OP_WRITE 的那一条) 时, 这里会指出第几块开始错位
            for (i in got.indices) {
                val expected = ((i / CHUNK) % 251).toByte()
                if (got[i] != expected) {
                    throw AssertionError(
                        "第 ${i / CHUNK} 块 (offset $i) 内容错位: 期望 $expected 实收 ${got[i]} —— " +
                            "有应答被留在 pendingWrites 里 (P1-9)",
                    )
                }
            }
        } finally {
            runCatching { sock.close() }
        }
    }

    /** 与 Socks5ProxyServer 的解析口径一致: VER CMD RSV ATYP(IPv4) ADDR PORT。 */
    private fun buildAuthRequest(
        user: String,
        pass: String,
    ): ByteArray {
        val u = user.toByteArray(Charsets.US_ASCII)
        val p = pass.toByteArray(Charsets.US_ASCII)
        return ByteBuffer
            .allocate(3 + u.size + p.size)
            .apply {
                put(0x01)
                put(u.size.toByte())
                put(u)
                put(p.size.toByte())
                put(p)
            }.array()
    }

    private fun chunkAt(index: Int): ByteArray = ByteArray(CHUNK) { ((index) % 251).toByte() }

    private fun writeFully(
        sock: SocketChannel,
        data: ByteArray,
        deadline: Long,
    ) {
        val buf = ByteBuffer.wrap(data)
        while (buf.hasRemaining()) {
            val written = sock.write(buf)
            if (written == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("${DEADLINE_MS}ms 内写不完 ${data.size}B (服务端不再读)")
                }
                Thread.sleep(1)
            }
        }
    }

    /** 非阻塞精确读 n 字节; 超出 deadline 即失败并带上进度 (悬挂点一眼可见)。 */
    private fun readExactly(
        sock: SocketChannel,
        n: Int,
        deadline: Long,
    ): ByteArray {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) {
            val read = sock.read(buf)
            if (read < 0) throw AssertionError("服务端提前关闭: 已收 ${buf.position()}/$n 字节")
            if (read == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError(
                        "DEADLINE ${DEADLINE_MS}ms 内只收到 ${buf.position()}/$n 字节 —— " +
                            "剩余应答被留在 pendingWrites 里 (OP_WRITE 被 handleWrite 误清, P1-9)",
                    )
                }
                Thread.sleep(1)
            }
        }
        return buf.array()
    }

    private fun assertBytes(
        what: String,
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(what, expected.toList(), actual.toList())
    }

    /**
     * 假隧道: 下行按块供给 (每块对应 relay 的一次 read → 一次 `sendReply`), 上行只吞字节。
     * 与真实 JSch 通道的区别只有"数据从哪来", `Socks5Connection` 侧代码路径完全相同。
     */
    private class ChunkTunnel : TunnelChannel {
        private val down = LinkedBlockingDeque<ByteArray>()
        private val up = ByteArrayOutputStream()

        @Volatile private var open = true

        override val inputStream: InputStream =
            object : InputStream() {
                override fun read(): Int = -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    while (true) {
                        if (!open) return -1
                        val chunk = down.pollFirst(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                        if (chunk.isEmpty()) return -1
                        val n = minOf(len, chunk.size)
                        System.arraycopy(chunk, 0, b, off, n)
                        return n
                    }
                }
            }

        override val outputStream: OutputStream get() = up

        override fun connect(timeoutMs: Int): Boolean = true

        override val isConnected: Boolean get() = open

        override fun disconnect() {
            open = false
        }

        fun offer(chunk: ByteArray) {
            down.offerLast(chunk)
        }

        /** 关掉远端: 唤醒阻塞在队列上的 relay 线程 (read 返回 -1)。 */
        fun closeInput() {
            open = false
            down.offerLast(ByteArray(0))
        }

        private companion object {
            const val POLL_MS = 100L
        }
    }

    private companion object {
        const val USER = "u"
        const val PASS = "p"

        /** 一个连接要跑多少个"下行块 → sendReply → handleWrite"回合。 */
        const val ROUNDS = 10_000

        /** 每块字节数 (远小于 relay 的 64KB 读缓冲, 保证一块对应一次 read/sendReply)。 */
        const val CHUNK = 32

        /** 整条用例的硬 deadline (prompt: 30s 上限)。 */
        const val DEADLINE_MS = 30_000L
        const val FEEDER_JOIN_MS = 5_000L

        val CONNECT_REQUEST: ByteArray =
            ByteBuffer
                .allocate(10)
                .apply {
                    put(0x05).put(0x01).put(0x00).put(0x01)
                    put(93).put(184.toByte()).put(216.toByte()).put(34)
                    put(0x01).put(0xBB.toByte())
                }.array()
    }
}
