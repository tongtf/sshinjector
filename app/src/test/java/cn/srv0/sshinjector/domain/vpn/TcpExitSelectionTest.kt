package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.exit.ExitHealth
import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import cn.srv0.sshinjector.domain.vpn.routing.RoutingSnapshot
import cn.srv0.sshinjector.domain.vpn.routing.RoutingSnapshotSource
import cn.srv0.sshinjector.domain.vpn.routing.RoutingTable
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **多出口最核心的新行为**: 命中规则时, 连接必须真的走附加出口的插件 (而不是主出口)。
 *
 * 之前这条路径没有任何确定性用例 —— `RoutingTable` 的纯逻辑测了, 但"解析结果是否真的被
 * 数据面拿来选插件"这一段只能靠真机。`RoutingSnapshotSource` 就是为此加的测试缝。
 *
 * 用例覆盖三条语义 (设计 §6.1 / §9.1 / §9.5):
 * 1. 命中 → 用附加出口;
 * 2. 命中但出口不可用 (`isHealthy=false`) + failover 开 → 回主出口 (连接仍然能用);
 * 3. 命中但出口不可用 + failover 关 → 直接 RST, **不许**悄悄换出口。
 */
class TcpExitSelectionTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")

    /** `203.0.113.0/24` 内的目标 → 命中附加出口 `hk`。 */
    private val targetIp = InetAddress.getByName("203.0.113.9")

    @Test
    fun `a matching rule routes the connection to the additional exit`() {
        val primaryServer = FakeSocksServer()
        val hkServer = FakeSocksServer()
        val sm =
            newStateMachine(
                primary = FakeSocksPlugin(primaryServer.port),
                additional = FakeSocksPlugin(hkServer.port),
                failover = true,
            )

        try {
            sendSyn(sm, srcPort = 41000)

            assertTrue("附加出口必须收到 CONNECT", awaitUntil { hkServer.accepted.get() == 1 })
            assertEquals("主出口不得收到这条连接", 0, primaryServer.accepted.get())
        } finally {
            primaryServer.close()
            hkServer.close()
        }
    }

    @Test
    fun `an unhealthy exit falls back to the primary exit when failover is on`() {
        val primaryServer = FakeSocksServer()
        val sm =
            newStateMachine(
                primary = FakeSocksPlugin(primaryServer.port),
                // 端口 0 = isHealthy 为 false (池没见过活口/监听未起)
                additional = FakeSocksPlugin(0),
                failover = true,
            )

        try {
            sendSyn(sm, srcPort = 41000)

            assertTrue("failover 开: 连接必须回落到主出口而不是失败", awaitUntil { primaryServer.accepted.get() == 1 })
        } finally {
            primaryServer.close()
        }
    }

    @Test
    fun `a pre-connect exit failure retries once on the primary exit`() {
        val primaryServer = FakeSocksServer()
        // 已经关闭的端口: 插件 isHealthy 仍为 true (端口号 > 0), 但 loopback 连接会失败 ——
        // 正是"出口在握手瞬间死掉"的形态 (设计 §9.5)
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        val sm =
            newStateMachine(
                primary = FakeSocksPlugin(primaryServer.port),
                additional = FakeSocksPlugin(deadPort),
                failover = true,
            )

        try {
            sendSyn(sm, srcPort = 41000)

            assertTrue(
                "握手阶段秒失败必须换主出口重试一次, 而不是让客户端白等",
                awaitUntil { primaryServer.accepted.get() == 1 },
            )
        } finally {
            primaryServer.close()
        }
    }

    @Test
    fun `a saturated exit falls back to the primary exit even with failover off`() {
        val primaryServer = FakeSocksServer()
        val sm =
            newStateMachine(
                primary = FakeSocksPlugin(primaryServer.port),
                // 活跃连接数已达软上限 128
                additional = FakeSocksPlugin(0, activeConnections = 128),
                failover = false,
            )

        try {
            sendSyn(sm, srcPort = 41000)

            assertTrue(
                "软上限不是失败: 超限必须回主出口 (连 failover=false 也不该 RST)",
                awaitUntil { primaryServer.accepted.get() == 1 },
            )
        } finally {
            primaryServer.close()
        }
    }

    @Test
    fun `an unhealthy exit rejects the connection when failover is off`() {
        val primaryServer = FakeSocksServer()
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm =
            newStateMachine(
                primary = FakeSocksPlugin(primaryServer.port),
                additional = FakeSocksPlugin(0),
                failover = false,
                tunPackets = tunPackets,
            )

        try {
            sendSyn(sm, srcPort = 41000)

            assertTrue("failover 关: 必须发 RST 而不是悄悄换出口", awaitUntil { tunPackets.any { isRst(it) } })
            assertEquals("不得回落到主出口", 0, primaryServer.accepted.get())
        } finally {
            primaryServer.close()
        }
    }

    // ---- 装配 ----

    private fun newStateMachine(
        primary: FakeSocksPlugin,
        additional: FakeSocksPlugin,
        failover: Boolean,
        tunPackets: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList(),
    ): TcpStateMachine {
        val tunnelManager = mock<TunnelManager>()
        whenever(tunnelManager.getActiveOrFallback()).thenReturn(primary)
        whenever(tunnelManager.exit("hk")).thenReturn(
            TunnelManager.ActiveExit(
                id = "hk",
                label = "hk",
                serverHost = "hk.example.com",
                plugin = additional,
                startedAt = 0L,
                health = ExitHealth.Ready,
            ),
        )
        val snapshot =
            RoutingSnapshot(
                enabled = true,
                failoverEnabled = failover,
                exits =
                    listOf(
                        ProxyExit(id = 1, name = "hk", serverId = 1, enabled = true, priority = 100),
                    ),
                table = RoutingTable.fromText("203.0.113.0/24 -> hk", setOf("hk")),
            )
        val source = RoutingSnapshotSource { snapshot }
        return TcpStateMachine(
            tunnelManager,
            { tunSinkOf { p: ByteArray -> tunPackets.add(p) } },
            PacketStats(),
            SshIoDispatcher(),
            routingSnapshotSource = source,
        )
    }

    private fun sendSyn(
        sm: TcpStateMachine,
        srcPort: Int,
    ) {
        val pkt = buildTcpPacket(srcPort, flags = 0x02)
        sm.processTcpPacket(
            ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN),
            IpPacketParser.ParsedPacket(clientIp, targetIp, 6, 0, pkt.size),
        )
    }

    private fun buildTcpPacket(
        srcPort: Int,
        flags: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(srcPort.toShort())
                putShort(443.toShort())
                putInt(12345)
                putInt(0)
                putShort(((5 shl 12) or flags).toShort())
                putShort(65535.toShort())
                putShort(0)
                putShort(0)
            }.array()

    /** 关闭包是完整 IPv4 包: 取 IHL 后 TCP flags 字节的 RST 位。 */
    private fun isRst(packet: ByteArray): Boolean {
        if (packet.size < 34) return false
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ihl + 14) return false
        return (packet[ihl + 13].toInt() and 0x04) != 0
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
}
