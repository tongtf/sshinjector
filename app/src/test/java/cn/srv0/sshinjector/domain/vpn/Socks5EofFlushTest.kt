package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * 回归: 远端 clean EOF 后必须先把 `pendingWrites` 里的回程写完再 close。
 *
 * 旧实现 `relayFromTarget` 在 `callback == null` (直连 SOCKS / 本地代理) 时把回程
 * `sendReply` 入队, 读到 -1 立刻 `close()` → 队列里剩下的字节被事件环 cancel 丢弃。
 * 现场形态: 无 Content-Length 的响应偶发整段丢失 (CI 上 `fetchExitIp` 读到 null)。
 *
 * 构造确定性: 回程 4MB + 服务端 512KB `SO_SNDBUF` + 客户端先 sleep 不读 → EOF 时
 * 队列必然非空。旧实现只收到内核缓冲里的前 ~0.5MB, 新实现必须收到完整 4MB。
 */
class Socks5EofFlushTest {
    private lateinit var server: Socks5ProxyServer
    private var port = 0

    private val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() =
        runBlocking {
            val factory = mock<SshChannelFactory>()
            whenever(factory.createDirectChannel(any(), any())).thenReturn(EofBurstTunnel(payload))
            server = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            server.setExpectedAuth("testUser", "testPass")
            port = server.start(0, "127.0.0.1").getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            server.stop()
        }

    @Test
    fun `full return payload is flushed before close after remote clean eof`() =
        runBlocking {
            val sock = SocketChannel.open()
            sock.configureBlocking(true)
            sock.connect(InetSocketAddress("127.0.0.1", port))
            try {
                handshakeAuthConnect(sock)
                // 请求内容不重要: 假隧道的 outputStream 直接丢弃
                sock.write(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: echo.test\r\n\r\n".toByteArray()))
                // 先不读: 让服务端回程队列在 EOF 时必然有积压
                Thread.sleep(300)

                val got = readUntilEof(sock)
                assertEquals("远端 clean EOF 前必须把回程队列全部写完", payload.size, got.size)
                // 内容完整性: 首/中/尾各抽一段 (整段 4MB 比较没必要)
                assertRange(got, 0)
                assertRange(got, payload.size / 2)
                assertRange(got, payload.size - 256)
            } finally {
                sock.close()
            }
        }

    private fun assertRange(
        got: ByteArray,
        from: Int,
    ) {
        for (i in from until (from + 256).coerceAtMost(got.size)) {
            assertEquals("byte $i", payload[i], got[i])
        }
    }

    private fun handshakeAuthConnect(sock: SocketChannel) {
        sock.write(ByteBuffer.wrap(byteArrayOf(0x05, 0x01, 0x02)))
        assertBytes(readN(sock, 2), byteArrayOf(0x05, 0x02))

        val user = "testUser".toByteArray()
        val pass = "testPass".toByteArray()
        val auth =
            ByteBuffer
                .allocate(3 + user.size + pass.size)
                .apply {
                    put(0x01).put(user.size.toByte()).put(user)
                    put(pass.size.toByte()).put(pass)
                }.array()
        sock.write(ByteBuffer.wrap(auth))
        assertBytes(readN(sock, 2), byteArrayOf(0x01, 0x00))

        val connect =
            ByteBuffer
                .allocate(10)
                .apply {
                    put(0x05).put(0x01).put(0x00).put(0x01)
                    put(1).put(2).put(3).put(4)
                    put(0x00).put(0x50)
                }.array()
        sock.write(ByteBuffer.wrap(connect))
        val success = readN(sock, 10)
        assertEquals("CONNECT rep", 0x00, success[1].toInt() and 0xFF)
    }

    private fun assertBytes(
        got: ByteArray,
        expected: ByteArray,
    ) {
        assertEquals(expected.size, got.size)
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i].toInt() and 0xFF, got[i].toInt() and 0xFF)
        }
    }

    private fun readN(
        sock: SocketChannel,
        n: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(n)
        val deadline = System.currentTimeMillis() + 10_000
        while (buf.hasRemaining()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("read timeout at ${buf.position()}/$n")
            val r = sock.read(buf)
            if (r < 0) throw AssertionError("EOF at ${buf.position()}/$n")
        }
        return buf.array()
    }

    /** 读到 EOF; 有界等待, 避免回归时挂住 CI。 */
    private fun readUntilEof(sock: SocketChannel): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(65536)
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("read timeout, got ${out.size()}B")
            buf.clear()
            val r = sock.read(buf)
            if (r < 0) break
            if (r > 0) out.write(buf.array(), 0, r)
        }
        return out.toByteArray()
    }

    /** 回程一次性可读 (ByteArrayInputStream), 读完即 EOF。 */
    private class EofBurstTunnel(
        private val payload: ByteArray,
    ) : TunnelChannel {
        private val input: InputStream = ByteArrayInputStream(payload)
        private val output: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = Unit
            }

        override fun connect(timeoutMs: Int): Boolean = true

        override val inputStream: InputStream get() = input
        override val outputStream: OutputStream get() = output
        override val isConnected: Boolean get() = true

        override fun disconnect() = Unit
    }
}
