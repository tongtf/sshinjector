package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelCapability
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfigDescriptor
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelState
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F6 (关闭发 RST + 条目移除 + 同五元组可重建) 与 F8 (SYN-ACK 先于 banner 写入 TUN)。
 */
class TcpCloseBannerTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")
    private val serverIp = InetAddress.getByName("93.184.216.34")
    private val srcPort = 41000
    private val dstPort = 443

    @Test
    fun `socks connect refused sends RST and frees the5-tuple for retry (F6)`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val plugin = FakeSocksPlugin(closedPort)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)

        sendSyn(sm)
        assertTrue("refused connect must emit RST within 5s", awaitUntil { tunPackets.any(::isRst) })

        tunPackets.clear()
        // 同五元组再来一个 SYN: 若条目未移除会走 existing 分支不再建连 → 不会有第二个 RST
        sendSyn(sm)
        assertTrue(
            "entry must be removed so a new SYN rebuilds and re-emits RST",
            awaitUntil { tunPackets.any(::isRst) },
        )
    }

    @Test
    fun `SYN-ACK is written to TUN before server banner (F8)`() {
        lateinit var plugin: FakeSocksPlugin
        val banner = "SSH-2.0-testserver\r\n".toByteArray()
        val server =
            FakeSocksServer(
                beforeConnectReply = {
                    // 模拟真实竞态: 回程 relay 已在跑, banner 先于 CONNECT 应答产生
                    plugin.tunCallback?.invoke(banner, 0, banner.size)
                },
            )
        plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)

        try {
            sendSyn(sm)
            assertTrue("banner must reach TUN", awaitUntil { tunPackets.any(::containsBanner) })

            val first = tunPackets.first()
            assertEquals("first TUN packet must be SYN-ACK, not banner", 0x12, first[33].toInt() and 0xFF)
            val bannerIdx = tunPackets.indexOfFirst(::containsBanner)
            assertTrue("banner must be written after SYN-ACK", bannerIdx > 0)
            assertEquals("banner segment is a pure ACK+PSH payload", 0x18, tunPackets[bannerIdx][33].toInt() and 0xFF)
        } finally {
            server.close()
        }
    }

    @Test
    fun `browser FIN closes without RST and frees the5-tuple (F6)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)

        try {
            sendSyn(sm)
            assertTrue("handshake must reach SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })
            assertTrue("server must receive CONNECT", server.awaitConnect(5, TimeUnit.SECONDS))

            sendFinAck(sm)
            Thread.sleep(300)
            assertTrue(
                "browser FIN must not be answered with RST (peer already closed)",
                tunPackets.none(::isRst),
            )

            tunPackets.clear()
            sendSyn(sm)
            assertTrue("entry must be removed by FIN close", awaitUntil { tunPackets.any(::isSynAck) })
        } finally {
            server.close()
        }
    }

    private fun newStateMachine(
        plugin: TunnelPlugin,
        tunPackets: CopyOnWriteArrayList<ByteArray>,
    ): TcpStateMachine {
        val tunnelManager = mock<TunnelManager>()
        whenever(tunnelManager.getActiveOrFallback()).thenReturn(plugin)
        return TcpStateMachine(
            tunnelManager,
            { { p: ByteArray -> tunPackets.add(p) } },
            PacketStats(),
            SshIoDispatcher(),
        )
    }

    private fun sendSyn(sm: TcpStateMachine) {
        val pkt = buildTcpPacket(0x02, 12345, 0)
        sm.processTcpPacket(ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN), clientIp, serverIp, 0, pkt.size)
    }

    private fun sendFinAck(sm: TcpStateMachine) {
        val pkt = buildTcpPacket(0x11, 12346, 1)
        sm.processTcpPacket(ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN), clientIp, serverIp, 0, pkt.size)
    }

    private fun isRst(p: ByteArray) = p.size >= 34 && (p[33].toInt() and 0x04) != 0

    private fun isSynAck(p: ByteArray) = p.size >= 34 && (p[33].toInt() and 0xFF) == 0x12

    private fun containsBanner(p: ByteArray): Boolean {
        if (p.size <= 40) return false
        return String(p, 40, p.size - 40, Charsets.ISO_8859_1).startsWith("SSH-2.0")
    }

    private fun awaitUntil(
        timeoutMs: Long = 5000,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /** 纯 TCP 头 (无 IP 层), payloadStart=0。 */
    private fun buildTcpPacket(
        flags: Int,
        seq: Int,
        ack: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(srcPort.toShort())
                putShort(dstPort.toShort())
                putInt(seq)
                putInt(ack)
                putShort(((5 shl 12) or flags).toShort())
                putShort(65535.toShort())
                putShort(0)
                putShort(0)
            }.array()

    /** 最小可用 SOCKS5 服务端: 用户名/密码认证 + CONNECT, 支持多连接。 */
    private class FakeSocksServer(
        private val beforeConnectReply: (() -> Unit)? = null,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val connectLatch = CountDownLatch(1)

        fun awaitConnect(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = connectLatch.await(timeout, unit)

        init {
            Thread {
                try {
                    while (true) {
                        handle(serverSocket.accept())
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun handle(s: Socket) {
            try {
                s.soTimeout = 5000
                val inp = s.getInputStream()
                val out = s.getOutputStream()

                // 方法选择: VER NM METHODS...
                inp.read()
                val nm = inp.read()
                repeat(nm) { inp.read() }
                out.write(byteArrayOf(0x05, 0x02))
                out.flush()

                // RFC 1929 认证
                inp.read()
                val ul = inp.read()
                repeat(ul) { inp.read() }
                val pl = inp.read()
                repeat(pl) { inp.read() }
                out.write(byteArrayOf(0x01, 0x00))
                out.flush()

                // CONNECT: VER CMD RSV ATYP ADDR PORT
                inp.read()
                inp.read()
                inp.read()
                when (inp.read()) {
                    0x01 -> repeat(6) { inp.read() }
                    0x04 -> repeat(18) { inp.read() }
                    0x03 -> {
                        val l = inp.read()
                        repeat(l + 2) { inp.read() }
                    }
                    else -> return
                }
                connectLatch.countDown()
                beforeConnectReply?.invoke()
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                while (inp.read() != -1) {
                    // 消费浏览器→服务端数据直到对端关闭
                }
            } catch (_: Exception) {
            } finally {
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }

        override fun close() {
            try {
                serverSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class FakeSocksPlugin(
        override val localSocksPort: Int,
    ) : TunnelPlugin {
        @Volatile var tunCallback: ((ByteArray, Int, Int) -> Unit)? = null

        override val id = "fake-socks"
        override val displayName = "fake"
        override val iconResId = 0
        override val capabilities = setOf(TunnelCapability.TCP)
        override val configDescriptor = TunnelConfigDescriptor(emptyList())
        override val socksAuth: Pair<String, String> = "user" to "pass"
        override val state = MutableStateFlow(TunnelState())
        override val stats = MutableStateFlow(TunnelStats())

        override suspend fun connect(config: TunnelConfig): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override fun openTcpChannel(
            host: String,
            port: Int,
        ): TunnelChannel? = null

        override fun registerTunCallback(
            clientPort: Int,
            callback: (ByteArray, Int, Int) -> Unit,
        ) {
            tunCallback = callback
        }

        override fun removeTunCallback(clientPort: Int) {
            tunCallback = null
        }
    }
}
