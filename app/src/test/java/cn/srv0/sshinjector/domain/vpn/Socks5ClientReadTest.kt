package cn.srv0.sshinjector.domain.vpn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * 回归守卫: ed713d3 引入 SOCKS5 认证后, 客户端用单次 sock.read() + 多次 .get(), TCP 分片送达时
 * 触发 BufferUnderflow → 静默断连 (连接但无网)。修复改读 readFully 循环读满。
 *
 * 本测试以 SocketChannel (与 TcpStateMachine.forwardThroughLocalSocks 同传输) 复现,
 * 验证握手/认证/CONNECT-success 三阶段在分片送达下仍能正确读完整条记录。
 */
class Socks5ClientReadTest {
    private lateinit var server: Socks5ProxyServer
    private var port = 0

    @Before
    fun setUp() =
        runBlocking {
            val factory = mock<SshChannelFactory>()
            whenever(factory.createDirectChannel(any(), any())).thenReturn(NullTunnelChannel())
            server = Socks5ProxyServer(factory, DnsInterceptor(), SshIoDispatcher())
            server.setExpectedAuth("testUser", "testPass")
            port = server.start(0, "127.0.0.1").getOrThrow()
        }

    @After
    fun tearDown() =
        runBlocking {
            server.stop()
        }

    /** 逐字节分片写入请求 (模拟 TCP 分段), 服务端以非阻塞写回小报文也易分段。 */
    private fun writeInChunks(
        sock: SocketChannel,
        data: ByteArray,
    ) {
        for (b in data) {
            sock.write(ByteBuffer.wrap(byteArrayOf(b)))
            Thread.sleep(5)
        }
    }

    @Test
    fun `socketchannel handshake auth and connect succeed under fragmentation`() =
        runBlocking {
            val sock = SocketChannel.open()
            sock.configureBlocking(true)
            sock.connect(InetSocketAddress("127.0.0.1", port))
            try {
                // 握手: 05 01 02 -> 应回 05 02
                writeInChunks(sock, byteArrayOf(0x05, 0x01, 0x02))
                assertReply(sock, byteArrayOf(0x05, 0x02))

                // 认证 RFC1929: 01 ULEN USER PLEN PASS
                val user = "testUser".toByteArray(Charsets.UTF_8)
                val pass = "testPass".toByteArray(Charsets.UTF_8)
                val authReq =
                    ByteBuffer
                        .allocate(3 + user.size + pass.size)
                        .apply {
                            put(0x01).put(user.size.toByte()).put(user)
                            put(pass.size.toByte()).put(pass)
                        }.array()
                writeInChunks(sock, authReq)
                assertReply(sock, byteArrayOf(0x01, 0x00))

                // CONNECT (atyp=IPv4 baidu.com->占位 1.2.3.4 :80) -> 应回 10 字节成功响应
                val connectReq =
                    ByteBuffer
                        .allocate(10)
                        .apply {
                            put(0x05).put(0x01).put(0x00).put(0x01)
                            put(1).put(2).put(3).put(4)
                            put(0x00).put(0x50)
                        }.array()
                writeInChunks(sock, connectReq)

                val success = readN(sock, 10)
                assertEquals("CONNECT ver", 0x05, success[0].toInt() and 0xFF)
                assertEquals("CONNECT rep (0=success)", 0x00, success[1].toInt() and 0xFF)
            } finally {
                sock.close()
            }
        }

    private fun assertReply(
        sock: SocketChannel,
        expected: ByteArray,
    ) {
        val got = readN(sock, expected.size)
        for (i in expected.indices) {
            assertEquals("reply byte $i", expected[i].toInt() and 0xFF, got[i].toInt() and 0xFF)
        }
    }

    /** 循环读满 n 字节 — 与修复后 TcpStateMachine.readFully 同语义。 */
    private fun readN(
        sock: SocketChannel,
        n: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) {
            val r = sock.read(buf)
            if (r <= 0) throw AssertionError("EOF at ${n - buf.remaining()}/$n")
        }
        return buf.array()
    }

    /** CONNECT 目标无需真回程数据; 空 tunnel 即可让 onTargetConnected 发成功响应。 */
    private class NullTunnelChannel : TunnelChannel {
        @Volatile private var open = true

        override fun connect(timeoutMs: Int): Boolean = true

        override val inputStream: InputStream? get() = null
        override val outputStream: OutputStream? get() = null
        override val isConnected: Boolean get() = open

        override fun disconnect() {
            open = false
        }
    }
}
