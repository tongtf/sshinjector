package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

/**
 * S2 认证 (RFC 1929) + S3 半包重组的协议级验证。
 *
 * 服务端 fail-closed: 未配置凭据或客户端不提供 0x02 方法一律回 0xFF,
 * 不再降级到无认证 (0x00) — 任何能连到 127.0.0.1 的进程此前都可以裸连代理。
 */
class Socks5AuthTest {
    private lateinit var fakeTunnel: FakeTunnelChannel
    private lateinit var server: Socks5ProxyServer
    private var port = 0

    @Before
    fun setUp() =
        runBlocking {
            fakeTunnel = FakeTunnelChannel()
            val factory = mock<SshChannelFactory>()
            whenever(factory.createDirectChannel(any(), any())).thenReturn(fakeTunnel)
            server = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            server.setExpectedAuth("testUser", "testPass")
            port = server.start(0, "127.0.0.1").getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            fakeTunnel.disconnect()
            server.stop()
        }

    @Test
    fun `client offering no-auth method is rejected with 0xFF`() {
        withSocket { sock ->
            sock.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            assertArrayEquals("no 0x00 downgrade", byteArrayOf(0x05, 0xFF.toByte()), readFully(sock, 2))
        }
    }

    @Test
    fun `server without configured credentials rejects even 0x02 offer`() =
        runBlocking {
            val factory = mock<SshChannelFactory>()
            val bare = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            val barePort = bare.start(0, "127.0.0.1").getOrThrow()
            try {
                val sock = Socket("127.0.0.1", barePort)
                sock.use {
                    it.soTimeout = SOCKET_TIMEOUT_MS
                    it.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
                    assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), readFully(it, 2))
                }
            } finally {
                bare.stop()
            }
        }

    @Test
    fun `wrong password is rejected with auth status 0x01`() {
        withSocket { sock ->
            val out = sock.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x02))
            assertArrayEquals(byteArrayOf(0x05, 0x02), readFully(sock, 2))

            out.write(authRequest("testUser", "wrongPass"))
            assertArrayEquals(byteArrayOf(0x01, 0x01), readFully(sock, 2))
        }
    }

    @Test
    fun `correct credentials proceed to connect`() {
        withSocket { sock ->
            authenticate(sock, "testUser", "testPass")

            sock.getOutputStream().write(connectRequest())
            assertArrayEquals(
                "CONNECT must succeed after auth",
                byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
                readFully(sock, 10),
            )
        }
    }

    @Test
    fun `handshake split byte by byte still completes connect`() {
        withSocket { sock ->
            val out = sock.getOutputStream()
            for (b in byteArrayOf(0x05, 0x01, 0x02)) {
                out.write(b.toInt())
                out.flush()
                Thread.sleep(20)
            }
            assertArrayEquals(byteArrayOf(0x05, 0x02), readFully(sock, 2))

            out.write(authRequest("testUser", "testPass"))
            assertArrayEquals(byteArrayOf(0x01, 0x00), readFully(sock, 2))

            out.write(connectRequest())
            val reply = readFully(sock, 10)
            assertEquals("ver", 0x05, reply[0].toInt() and 0xFF)
            assertEquals("rep", 0x00, reply[1].toInt() and 0xFF)
        }
    }

    @Test
    fun `connect request split in three segments still succeeds`() {
        withSocket { sock ->
            authenticate(sock, "testUser", "testPass")

            val out = sock.getOutputStream()
            val req = connectRequest()
            for (seg in listOf(0 until 2, 2 until 7, 7 until req.size)) {
                // IntRange.last 是末元素 (不含尾界), 长度 = last+1-first
                out.write(req, seg.first, seg.last + 1 - seg.first)
                out.flush()
                Thread.sleep(20)
            }
            val reply = readFully(sock, 10)
            assertEquals("ver", 0x05, reply[0].toInt() and 0xFF)
            assertEquals("rep", 0x00, reply[1].toInt() and 0xFF)
        }
    }

    @Test
    fun `second relay write arrives intact after first write cleared the buffer`() {
        // 回归: handleRead 尾部曾在 enqueueToSsh 的 clear() 之后执行 compact() →
        // buffer 毒化为 (pos=capacity, limit=capacity) → 下次 read 零容量返回 0,
        // flip() 暴露 32KB 陈旧字节灌进隧道, 浏览器数据永远到不了远端 (已连接但无网络)。
        withSocket { sock ->
            authenticate(sock, "testUser", "testPass")
            val out = sock.getOutputStream()
            out.write(connectRequest())
            readFully(sock, 10)

            out.write("hello".toByteArray())
            out.flush()
            assertEquals("first write must relay", "hello", awaitTunnelBytes(5, firstOnly = true))

            // 确保第一次 handleRead (含尾部重置) 已完全结束, 再投递第二段
            Thread.sleep(100)
            out.write("world".toByteArray())
            out.flush()
            assertEquals("no stale-buffer flood", "helloworld", awaitTunnelBytes(10, firstOnly = false))
        }
    }

    // ---- helpers ------------------------------------------------------------

    private inline fun withSocket(block: (Socket) -> Unit) {
        val sock = Socket("127.0.0.1", port)
        sock.use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            block(it)
        }
    }

    private fun authenticate(
        sock: Socket,
        user: String,
        pass: String,
    ) {
        val out = sock.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x02))
        assertArrayEquals(byteArrayOf(0x05, 0x02), readFully(sock, 2))
        out.write(authRequest(user, pass))
        assertArrayEquals(byteArrayOf(0x01, 0x00), readFully(sock, 2))
    }

    private fun authRequest(
        user: String,
        pass: String,
    ): ByteArray {
        val u = user.toByteArray(Charsets.UTF_8)
        val p = pass.toByteArray(Charsets.UTF_8)
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

    private fun connectRequest(): ByteArray =
        byteArrayOf(
            0x05,
            0x01,
            0x00,
            0x01, // VER CMD RSV ATYP=IPv4
            0x01,
            0x02,
            0x03,
            0x04, // 1.2.3.4
            0x00,
            0x50, // :80
        )

    private fun readFully(
        sock: Socket,
        n: Int,
    ): ByteArray {
        val input = sock.getInputStream()
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) fail("EOF at $off/$n")
            off += r
        }
        return buf
    }

    /** 有界等待隧道累计收到 >= n 字节, 返回前 n 字节的解码结果; 超时 fail。 */
    private fun awaitTunnelBytes(
        n: Int,
        firstOnly: Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val data = fakeTunnel.received()
            if (data.size >= n) {
                val head = data.copyOfRange(0, n)
                if (firstOnly && data.size != n) {
                    fail("expected exactly $n bytes, tunnel got ${data.size}")
                }
                return String(head, Charsets.UTF_8)
            }
            Thread.sleep(20)
        }
        fail("tunnel received only ${fakeTunnel.received().size} bytes, expected >= $n")
        return ""
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000
    }
}

/** 回程流: 在 disconnect() 前阻塞 (不立即 EOF, 避免成功回复被 close 丢弃); 捕获出向写入字节供断言。 */
private class FakeTunnelChannel : TunnelChannel {
    @Volatile private var open = true

    private val receivedLock = Any()
    private val receivedBytes = java.io.ByteArrayOutputStream()

    fun received(): ByteArray = synchronized(receivedLock) { receivedBytes.toByteArray() }

    override fun connect(timeoutMs: Int): Boolean = true

    override val inputStream: InputStream =
        object : InputStream() {
            override fun read(): Int {
                while (open) {
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                return -1
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = read()
        }

    override val outputStream: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = synchronized(receivedLock) { receivedBytes.write(b) }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = synchronized(receivedLock) { receivedBytes.write(b, off, len) }
        }

    override val isConnected: Boolean get() = open

    override fun disconnect() {
        open = false
    }
}
