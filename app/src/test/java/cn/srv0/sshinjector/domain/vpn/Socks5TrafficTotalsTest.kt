package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * 每出口的**会话累计流量** (§12.1 的 `↑1.2MB ↓8.4MB`)。
 *
 * 这条数据是"哪个出口真的在干活"的唯一判据: 只看活跃连接数会在长连接关闭后归零,
 * 而且限速/黑洞问题需要上/下行**分开**看 (上行多下行 0 = 回程堵, 反之是上行堵)。
 *
 * 走真实 socket + 假 tunnel 通道: 只有把字节真正流过 relay, `toTunnelTotal/fromTunnelTotal`
 * 才会增长 —— 直接调 getter 的"假测试"证明不了接线正确。
 */
class Socks5TrafficTotalsTest {
    private lateinit var server: Socks5ProxyServer
    private lateinit var tunnelIn: PipedOutputStream
    private lateinit var tunnelOut: ByteArrayOutputStream
    private var port = 0

    private val downPayload = ByteArray(300) { (it % 251).toByte() }
    private val upPayload = ByteArray(200) { (it % 249).toByte() }

    @Before
    fun setUp() =
        runBlocking {
            val pipedIn = PipedInputStream(64 * 1024)
            tunnelIn = PipedOutputStream(pipedIn)
            tunnelOut = ByteArrayOutputStream()
            val channel = FakeTunnelChannel(pipedIn, tunnelOut)
            val factory = mock<SshChannelFactory>()
            whenever(factory.createDirectChannel(any(), any())).thenReturn(channel)

            server = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            server.setExpectedAuth("u", "p")
            port = server.start(0, "127.0.0.1").getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            runCatching { tunnelIn.close() }
            server.stop()
        }

    @Test
    fun `session totals accumulate bytes in both directions and on close`() =
        runBlocking {
            val sock = SocketChannel.open()
            sock.configureBlocking(true)
            sock.connect(InetSocketAddress("127.0.0.1", port))
            try {
                handshake(sock)
                connect(sock)

                // 下行: 隧道 → 客户端 (relayFromTarget 读到即计数)
                tunnelIn.write(downPayload)
                tunnelIn.flush()
                readExactly(sock, downPayload.size)

                // 上行: 客户端 → 隧道。等字节真的写进假通道, 否则关闭可能先于写协程
                sock.write(ByteBuffer.wrap(upPayload))
                assertTrue(
                    "上行必须写进隧道: 实际 ${tunnelOut.size()}/${upPayload.size}",
                    awaitUntil { tunnelOut.size() >= upPayload.size },
                )

                // 客户端先断开 → 服务端 close() → 把该连接的上/下行累加进会话总量。
                // 再关隧道读端, 让 relay 线程也退出 (避免 @After 里残留线程)。
                sock.close()
                tunnelIn.close()
                assertTrue(
                    "关闭后必须把该连接的上/下行累加进会话总量: " +
                        "up=${server.totalUpBytes} down=${server.totalDownBytes} conns=${server.totalConnections} " +
                        "· ${server.diagnostics()}",
                    awaitUntil {
                        server.totalUpBytes >= upPayload.size &&
                            server.totalDownBytes >= downPayload.size
                    },
                )
                assertEquals("本会话只接过一条连接", 1L, server.totalConnections)
            } finally {
                runCatching { sock.close() }
            }
        }

    private fun handshake(sock: SocketChannel) {
        sock.write(ByteBuffer.wrap(byteArrayOf(0x05, 0x01, 0x02)))
        readExactly(sock, 2)
        val user = "u".toByteArray()
        val pass = "p".toByteArray()
        val auth =
            ByteBuffer
                .allocate(3 + user.size + pass.size)
                .apply {
                    put(0x01)
                    put(user.size.toByte())
                    put(user)
                    put(pass.size.toByte())
                    put(pass)
                }.array()
        sock.write(ByteBuffer.wrap(auth))
        readExactly(sock, 2)
    }

    private fun connect(sock: SocketChannel) {
        val req =
            ByteBuffer
                .allocate(10)
                .apply {
                    put(0x05).put(0x01).put(0x00).put(0x01)
                    put(93).put(184.toByte()).put(216.toByte()).put(34)
                    put(0x01).put(0xBB.toByte())
                }.array()
        sock.write(ByteBuffer.wrap(req))
        val reply = readExactly(sock, 10)
        assertEquals("CONNECT 必须成功", 0x00, reply[1].toInt() and 0xFF)
    }

    private fun readExactly(
        sock: SocketChannel,
        n: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) {
            val read = sock.read(buf)
            if (read <= 0) throw AssertionError("EOF at ${n - buf.remaining()}/$n")
        }
        return buf.array()
    }

    private fun awaitUntil(
        timeoutMs: Long = 5_000,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private class FakeTunnelChannel(
        override val inputStream: InputStream,
        override val outputStream: OutputStream,
    ) : TunnelChannel {
        @Volatile private var open = true

        override fun connect(timeoutMs: Int): Boolean = true

        override val isConnected: Boolean get() = open

        override fun disconnect() {
            open = false
        }
    }
}
