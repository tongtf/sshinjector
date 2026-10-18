package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通道就绪窗口 (Connecting) 的上行数据纪律。
 *
 * 历史缺陷: eventLoop 在 `state == Connecting` 时读到的上行只会留在连接自己的 buffer 里
 * (handleRead 的 Connecting 分支不消费), 而状态翻到 Relaying 后**不会再有 OP_READ 事件**
 * (内核接收缓冲已被那一次 read 读空) —— 这批字节再也没人处理。单请求场景 (HTTP 探测 /
 * 一次性 GET / 发完 ClientHello 就等的客户端) 就直接卡到超时; 真机形态是探测失败或
 * `隧道侧零回程` 而所有丢弃计数都是 0 (最难排的一类)。
 *
 * 修法: 一进 Connecting 就按下 OP_READ (字节留在内核接收缓冲, 由 TCP 反向背压),
 * 通道就绪时先置 Relaying、放开读, 再发出成功应答。
 *
 * 用例是**确定性**的: 假通道的 connect() 被闩锁钉住, 于是"仍在连接"是一个可观测状态;
 * 所有等待都有界 (CI 上不许无限等)。
 */
class Socks5ConnectWindowTest {
    private lateinit var tunnel: GatedTunnelChannel
    private lateinit var server: Socks5ProxyServer
    private var port = 0

    @Before
    fun setUp() =
        runBlocking {
            tunnel = GatedTunnelChannel()
            val factory = mock<SshChannelFactory>()
            whenever(factory.createDirectChannel(any(), any())).thenReturn(tunnel)
            server = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            server.setExpectedAuth(USER, PASS)
            port = server.start(0, "127.0.0.1").getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            tunnel.close()
            server.stop()
        }

    @Test
    fun `uplink sent while the tunnel is still connecting is forwarded once ready`() {
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            socksHandshake(output, input)

            // 服务端此刻被钉在 connect() 里 = state == Connecting (可观测, 不靠竞速)
            assertTrue("服务端应已进入 Connecting", tunnel.connectEntered.await(10, TimeUnit.SECONDS))

            // 通道就绪前发上行: 这批字节既不能丢, 也不能卡在连接 buffer 里等人再发一次
            output.write(PAYLOAD)
            output.flush()

            // 只给 eventLoop 一个消费窗口 —— 老实现正是在这里把字节读进 buffer 后再也不会处理。
            // (修好之后这条 sleep 无意义: 字节留在内核缓冲, 由放开 OP_READ 后的事件读走。)
            Thread.sleep(CONNECTING_DWELL_MS)

            tunnel.releaseConnect()

            assertEquals(0x00, readExact(input, SOCKS_REPLY_SIZE)[1].toInt())
            assertTrue("上行应在通道就绪后被转发", tunnel.received.await(10, TimeUnit.SECONDS))
            assertArrayEquals(PAYLOAD, tunnel.written())
        }
    }

    /** RFC 1928/1929: 方法协商 → 用户名密码认证 → CONNECT (域名型)。 */
    private fun socksHandshake(
        output: OutputStream,
        input: InputStream,
    ) {
        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        assertEquals(0x02, readExact(input, 2)[1].toInt())

        val user = USER.toByteArray()
        val pass = PASS.toByteArray()
        output.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        output.flush()
        assertEquals(0x00, readExact(input, 2)[1].toInt())

        val host = TARGET_HOST.toByteArray()
        output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host + byteArrayOf(0x00, 0x50))
        output.flush()
    }

    private fun readExact(
        input: InputStream,
        n: Int,
    ): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            check(r > 0) { "EOF at $off/$n" }
            off += r
        }
        return buf
    }

    companion object {
        private const val USER = "testUser"
        private const val PASS = "testPass"
        private const val TARGET_HOST = "example.test"
        private const val SOCKS_REPLY_SIZE = 10
        private const val CONNECTING_DWELL_MS = 300L
        private val PAYLOAD = "PING".toByteArray()
    }
}

/**
 * 假隧道: `connect()` 阻塞到 [releaseConnect] 为止 (把服务端钉在 Connecting),
 * 并记录真正写进隧道的上行字节。
 *
 * 回程 `read()` 只在 [close] 之后返回 -1 —— 提前 EOF 会让 relayFromTarget 收尾关闭整条
 * 连接, 被测的上行转发就没了入口。
 */
private class GatedTunnelChannel : TunnelChannel {
    val connectEntered = CountDownLatch(1)
    val received = CountDownLatch(1)
    private val connectGate = CountDownLatch(1)
    private val closeGate = CountDownLatch(1)
    private val out = ByteArrayOutputStream()

    @Volatile private var open = true

    override fun connect(timeoutMs: Int): Boolean {
        connectEntered.countDown()
        connectGate.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        return open
    }

    override val inputStream: InputStream =
        object : InputStream() {
            override fun read(): Int {
                // 有界等待: 被测路径结束前不给 EOF, 之后回 -1 (中继自行收尾)
                closeGate.await(30, TimeUnit.SECONDS)
                return -1
            }
        }

    override val outputStream: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                synchronized(out) { out.write(b, off, len) }
                received.countDown()
            }
        }

    fun written(): ByteArray = synchronized(out) { out.toByteArray() }

    fun releaseConnect() {
        connectGate.countDown()
    }

    fun close() {
        open = false
        connectGate.countDown()
        closeGate.countDown()
    }

    override val isConnected: Boolean get() = open

    override fun disconnect() {
        close()
    }
}
