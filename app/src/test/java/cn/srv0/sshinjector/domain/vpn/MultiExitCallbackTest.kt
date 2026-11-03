package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * C-2: 回程回调必须记在**连接**上, 不是机器级字段。
 *
 * 旧实现 `TcpStateMachine.tunCallbackPlugin` 每次建连都被覆盖, 于是:
 * - 连接 1 (出口 A) 关闭时去**出口 B** 上摘回调 → A 的注册永久泄漏 (端口复用后回程写进死连接),
 * - B 的回调被误删 → 连接 2 的回程全丢, 现场表现是"零回程"挂到超时。
 *
 * 多出口之前只有一条出口, 两种后果都不可见 —— 这个用例就是为了让它在单出口下也能复现。
 */
class MultiExitCallbackTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")
    private val serverIp = InetAddress.getByName("93.184.216.34")

    @Test
    fun `closing one connection removes callbacks only from its own exit`() {
        val server = FakeSocksServer()
        val exitA = FakeSocksPlugin(server.port)
        val exitB = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(exitA, exitB, tunPackets)

        try {
            // 两条连接先后建立: 第一次拿出口 A, 第二次拿出口 B
            sendSyn(sm, srcPort = 41000)
            sendSyn(sm, srcPort = 41001)

            assertTrue("出口 A 必须收到回调注册", awaitUntil { exitA.tunCallback != null })
            assertTrue("出口 B 必须收到回调注册", awaitUntil { exitB.tunCallback != null })
            assertTrue("两条连接都必须走到 CONNECT", awaitUntil { server.accepted.get() == 2 })

            // 关掉**第一条**连接。
            //
            // 这里不能假设"连接 1 一定落在出口 A": 两条连接的插件都是在各自的
            // `forwardSynToTunnel` 协程里取的, 谁先调用 `getActiveOrFallback` 由调度决定。
            // 断言改成"谁被摘除"而不是"谁应该是谁" —— 要锁的不变量是**按连接摘除**,
            // 旧实现 (机器级字段) 无论谁先谁后都会把另一条出口的回调一起摘掉。
            sendFinAck(sm, srcPort = 41000)

            assertTrue(
                "关闭连接 1 必须摘掉它自己那条出口的回调",
                awaitUntil { exitA.removedPorts.isNotEmpty() || exitB.removedPorts.isNotEmpty() },
            )
            val firstExit = if (exitA.removedPorts.isNotEmpty()) exitA else exitB
            val otherExit = if (firstExit === exitA) exitB else exitA
            assertNull("被关闭连接所属出口的回调必须清掉", firstExit.tunCallback)
            assertNotNull(
                "另一条出口的回调**不得**被这条连接的关闭摘掉 (旧实现会误删)",
                otherExit.tunCallback,
            )
            assertTrue("另一条出口不应收到任何摘除调用", otherExit.removedPorts.isEmpty())

            // 再关掉第二条: 这次才轮到另一条出口
            sendFinAck(sm, srcPort = 41001)
            assertTrue("关闭连接 2 必须摘掉另一条出口的回调", awaitUntil { otherExit.removedPorts.isNotEmpty() })
            assertNull(otherExit.tunCallback)
        } finally {
            server.close()
        }
    }

    private fun newStateMachine(
        first: TunnelPlugin,
        second: TunnelPlugin,
        tunPackets: CopyOnWriteArrayList<ByteArray>,
    ): TcpStateMachine {
        val tunnelManager = mock<TunnelManager>()
        // 每条新连接取一次插件: 第一次 A, 之后 B
        whenever(tunnelManager.getActiveOrFallback()).thenReturn(first, second)
        return TcpStateMachine(
            tunnelManager,
            { tunSinkOf { p: ByteArray -> tunPackets.add(p) } },
            PacketStats(),
            SshIoDispatcher(),
        )
    }

    private fun sendSyn(
        sm: TcpStateMachine,
        srcPort: Int,
    ) {
        val pkt = buildTcpPacket(srcPort, 0x02, 12345, 0)
        sm.processTcpPacket(
            ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN),
            IpPacketParser.ParsedPacket(clientIp, serverIp, 6, 0, pkt.size),
        )
    }

    private fun sendFinAck(
        sm: TcpStateMachine,
        srcPort: Int,
    ) {
        val pkt = buildTcpPacket(srcPort, 0x11, 12346, 1)
        sm.processTcpPacket(
            ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN),
            IpPacketParser.ParsedPacket(clientIp, serverIp, 6, 0, pkt.size),
        )
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

    private fun buildTcpPacket(
        srcPort: Int,
        flags: Int,
        seq: Int,
        ack: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(srcPort.toShort())
                putShort(443.toShort())
                putInt(seq)
                putInt(ack)
                putShort(((5 shl 12) or flags).toShort())
                putShort(65535.toShort())
                putShort(0)
                putShort(0)
            }.array()
}
