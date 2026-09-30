package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.HealthStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

/**
 * 探测失败的阶段归因 (spec): 代理握手阶段 -> PROXY; CONNECT 回复等待超时 -> TUNNEL;
 * CONNECT 回复 rep!=0 / HTTP 无状态行 -> REMOTE。成功 = 收到任意 HTTP 状态行。
 */
class ConnectivityProberTest {
    private lateinit var fakeTunnel: HttpFakeTunnelChannel
    private lateinit var server: Socks5ProxyServer
    private var port = 0

    @Before
    fun setUp() =
        runBlocking {
            fakeTunnel = HttpFakeTunnelChannel(HTTP_204)
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
    fun `probe succeeds when remote returns any http status line`() {
        val result = newProber().probe()
        assertEquals(ConnectivityProber.Result.Ok, result)
    }

    @Test
    fun `wrong socks credentials attributed to PROXY`() {
        val result = newProber(auth = "testUser" to "wrongPass").probe()
        assertStep(HealthStep.PROXY, result)
    }

    @Test
    fun `unreachable local proxy attributed to PROXY`() {
        // 占一个端口立刻释放: 该端口无监听 -> 连本地代理即 ConnectException
        val deadPort = ServerSocket(0).use { it.localPort }
        val result = newProber(socksPort = deadPort).probe()
        assertStep(HealthStep.PROXY, result)
        assertTrue((result as ConnectivityProber.Result.Failed).reason.contains("proxy unreachable"))
    }

    @Test
    fun `no tunnel channel (socks reply rep!=0) attributed to REMOTE`() =
        runBlocking {
            val nullFactory = mock<SshChannelFactory>()
            whenever(nullFactory.createDirectChannel(any(), any())).thenReturn(null)
            val bare = Socks5ProxyServer(nullFactory, DnsInterceptor(), SshIoDispatcher())
            bare.setExpectedAuth("testUser", "testPass")
            val barePort = bare.start(0, "127.0.0.1").getOrThrow()
            try {
                val result = newProber(socksPort = barePort).probe()
                assertStep(HealthStep.REMOTE, result)
                assertTrue((result as ConnectivityProber.Result.Failed).reason.contains("rep="))
            } finally {
                bare.stop()
            }
        }

    @Test
    fun `silent socks server on connect attributed to TUNNEL`() {
        // 原始 SOCKS 服务端: 完成 greet/auth 后对 CONNECT 永不应答 -> 读超时
        ServerSocket(0).use { ss ->
            val ready = java.util.concurrent.CountDownLatch(1)
            val t =
                Thread {
                    try {
                        ss.accept().use { s ->
                            val input = s.getInputStream()
                            val greet = readExact(input, 3)
                            assertEquals(0x05, greet[0].toInt())
                            s.getOutputStream().write(byteArrayOf(0x05, 0x02))
                            val ulen = readExact(input, 2)[1].toInt() and 0xFF
                            readExact(input, ulen)
                            val plen = readExact(input, 1)[0].toInt() and 0xFF
                            readExact(input, plen)
                            s.getOutputStream().write(byteArrayOf(0x01, 0x00))
                            ready.countDown()
                            // 不读也不关 CONNECT, 挂住直到测试结束
                            Thread.sleep(10_000)
                        }
                    } catch (_: Exception) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            val result = newProber(socksPort = ss.localPort, timeoutMs = 800).probe()
            assertTrue("greet/auth must complete before silent phase", ready.count == 0L)
            assertStep(HealthStep.TUNNEL, result)
            assertTrue((result as ConnectivityProber.Result.Failed).reason.contains("socks-connect"))
            t.interrupt()
        }
    }

    @Test
    fun `garbage response body attributed to REMOTE`() {
        fakeTunnel.response = "NO-HTTP-STATUS-LINE".toByteArray()
        val result = newProber().probe()
        assertStep(HealthStep.REMOTE, result)
        assertTrue((result as ConnectivityProber.Result.Failed).reason.contains("no http status line"))
    }

    @Test
    fun `tunnel eof at http phase attributed to REMOTE`() {
        fakeTunnel.response = ByteArray(0)
        val result = newProber().probe()
        assertStep(HealthStep.REMOTE, result)
        assertTrue((result as ConnectivityProber.Result.Failed).reason.contains("no http status line"))
    }

    @Test
    fun `invalid endpoint url attributed to REMOTE`() {
        val prober =
            ConnectivityProber(
                socksPort = port,
                socksAuth = "testUser" to "testPass",
                endpointUrl = "ftp://bad",
            )
        assertStep(HealthStep.REMOTE, prober.probe())
    }

    @Test
    fun `fetchExitIp parses json ip body`() {
        fakeTunnel.response = "HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n{\"ip\":\"203.0.113.7\"}".toByteArray()
        assertEquals("203.0.113.7", newProber().fetchExitIp("http://echo.test/"))
    }

    @Test
    fun `fetchExitIp reads plain body until eof when no content-length`() {
        fakeTunnel.response = "HTTP/1.1 200 OK\r\n\r\n198.51.100.23".toByteArray()
        assertEquals("198.51.100.23", newProber().fetchExitIp("http://echo.test/"))
    }

    @Test
    fun `fetchExitIp returns null on non-ip body`() {
        fakeTunnel.response = "HTTP/1.1 200 OK\r\nContent-Length: 17\r\n\r\n<html>oops</html>".toByteArray()
        assertNull(newProber().fetchExitIp("http://echo.test/"))
    }

    @Test
    fun `fetchExitIp returns null on empty body`() {
        assertNull(newProber().fetchExitIp("http://echo.test/"))
    }

    @Test
    fun `fetchExitIp returns null when local proxy unreachable`() {
        val deadPort = ServerSocket(0).use { it.localPort }
        assertNull(newProber(socksPort = deadPort).fetchExitIp("http://echo.test/"))
    }

    // ---- helpers ------------------------------------------------------------

    private fun newProber(
        socksPort: Int = port,
        auth: Pair<String, String>? = "testUser" to "testPass",
        timeoutMs: Int = 4000,
    ) = ConnectivityProber(socksPort, auth, "http://probe.test/generate_204", timeoutMs, timeoutMs)

    private fun assertStep(
        expected: HealthStep,
        result: ConnectivityProber.Result,
    ) {
        assertTrue("expected Failed, got $result", result is ConnectivityProber.Result.Failed)
        assertEquals(expected, (result as ConnectivityProber.Result.Failed).step)
    }

    private fun readExact(
        input: InputStream,
        n: Int,
    ): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            check(r >= 0) { "EOF at $off/$n" }
            off += r
        }
        return buf
    }

    companion object {
        private val HTTP_204 = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray()
    }
}

/**
 * 假隧道: 捕获出向写入 (HTTP 请求), 见到请求头结束标记后向回程注入 canned 响应;
 * 响应字节送完即 EOF (prober 只读状态行)。
 */
private class HttpFakeTunnelChannel(
    @Volatile var response: ByteArray,
) : TunnelChannel {
    @Volatile private var open = true
    private val lock = java.lang.Object()
    private val outBuf = java.io.ByteArrayOutputStream()
    private var responded = false
    private var delivered = 0

    override fun connect(timeoutMs: Int): Boolean = true

    override val inputStream: InputStream =
        object : InputStream() {
            override fun read(): Int {
                synchronized(lock) {
                    val deadline = System.currentTimeMillis() + 5000
                    while (open && !responded && System.currentTimeMillis() < deadline) {
                        lock.wait(50)
                    }
                    if (!responded) return -1
                    if (delivered >= response.size) return -1
                    return response[delivered++].toInt() and 0xFF
                }
            }
        }

    override val outputStream: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = onWrite(byteArrayOf(b.toByte()))

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = onWrite(b.copyOfRange(off, off + len))
        }

    private fun onWrite(bytes: ByteArray) {
        synchronized(lock) {
            outBuf.write(bytes)
            if (!responded && outBuf.toByteArray().toString(Charsets.ISO_8859_1).contains("\r\n\r\n")) {
                responded = true
                lock.notifyAll()
            }
        }
    }

    override val isConnected: Boolean get() = open

    override fun disconnect() {
        synchronized(lock) {
            open = false
            lock.notifyAll()
        }
    }
}
