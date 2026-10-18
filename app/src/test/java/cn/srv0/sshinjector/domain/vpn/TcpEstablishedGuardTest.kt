package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.InetAddress
import java.nio.channels.SocketChannel

/**
 * `markEstablished` 是**置 Established 的唯一入口** (SOCKS 主路径与旁路直连共用), 它承担两条
 * 不变量:
 *
 * 1. 握手期间客户端 RST (close 先跑完) → **不得**置 Established: 否则这条已从 map 移除的连接
 *    照样跑 relay, 后续包全成孤儿、连接永久卡死;
 * 2. 同一临界区里配对假 IP 的 acquire, 使 "close 先 release、establish 再 acquire" 这种
 *    计数泄漏 (16384 条池打满 → 后续 CONNECT 全 0x03 卡"连接中") 不可能发生。
 *
 * 两条互斥的先后顺序都必须在锁内决出, 因此这里是**确定性**断言而不是竞态压力测试:
 * `closeTcpConnection` 与 `markEstablished` 持同一把 `conn` 监视器且判同一个 `state` 字段,
 * "close 先"就是本测第 1 例, "establish 先"是第 2 例 (也是所有既有连接用例的常态路径)。
 */
class TcpEstablishedGuardTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")
    private val fakeIp = InetAddress.getByName("198.18.0.7")
    private val realIp = InetAddress.getByName("93.184.216.34")

    private fun newStateMachine(): TcpStateMachine =
        TcpStateMachine(
            mock<TunnelManager>(),
            { null },
            PacketStats(),
            SshIoDispatcher(),
        )

    private fun conn(
        id: Long,
        dstIp: InetAddress,
        srcPort: Int,
    ) = TcpStateMachine.TcpConnection(
        id = id,
        srcIp = clientIp,
        dstIp = dstIp,
        srcPort = srcPort,
        dstPort = 443,
    )

    @Test
    fun `closed connection must not be established, and the fresh channel is closed too`() {
        val sm = newStateMachine()
        val c = conn(1L, fakeIp, 41000)
        c.state = TcpStateMachine.TcpConnection.TcpState.Closed
        val channel = SocketChannel.open()

        assertFalse("已关闭的连接不得置 Established", sm.markEstablished(c, channel))
        assertEquals(TcpStateMachine.TcpConnection.TcpState.Closed, c.state)
        assertFalse("刚建好的通道没人会再管它, 必须就地关闭", channel.isOpen)
        assertEquals("不得 acquire 假 IP", 0, sm.fakeIpActiveConnCount(fakeIp.hostAddress!!))
        assertFalse(c.fakeIpAcquired)
    }

    @Test
    fun `fresh connection is established and pairs the fake ip acquire`() {
        val sm = newStateMachine()
        val c = conn(2L, fakeIp, 41001)
        val channel = SocketChannel.open()

        assertTrue(sm.markEstablished(c, channel))
        assertEquals(TcpStateMachine.TcpConnection.TcpState.Established, c.state)
        assertSame(channel, c.socksChannel)
        assertTrue(channel.isOpen)
        assertTrue("Established 必须与 acquire 同处置位", c.fakeIpAcquired)
        assertEquals(1, sm.fakeIpActiveConnCount(fakeIp.hostAddress!!))
    }

    @Test
    fun `a real destination address is not counted as a fake ip holder`() {
        val sm = newStateMachine()
        val c = conn(3L, realIp, 41002)
        val channel = SocketChannel.open()

        assertTrue(sm.markEstablished(c, channel))
        assertFalse("非假 IP 目标不得记账 (否则 release 时会把别人扣掉)", c.fakeIpAcquired)
        assertEquals(0, sm.fakeIpActiveConnCount(realIp.hostAddress!!))
    }
}
