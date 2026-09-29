package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F1: TCP 用户态直连策略矩阵 + bypass 路径不经过隧道插件。
 */
class TcpBypassTest {
    @Test
    fun `shouldBypassTcp matrix`() {
        val lanOnly = listOfNotNull(CidrRoute.parse("192.168.0.0/16"))

        // 排除路由命中 → 直连 (任何模式)
        assertTrue(
            VpnController.computeShouldBypassTcp(
                InetAddress.getByName("192.168.1.1"),
                lanOnly,
                DnsInterceptor.DnsTransport.REMOTE,
            ),
        )
        // 排除路由未命中 → 走隧道
        assertFalse(
            VpnController.computeShouldBypassTcp(
                InetAddress.getByName("8.8.8.8"),
                lanOnly,
                DnsInterceptor.DnsTransport.REMOTE,
            ),
        )
        // SYSTEM 模式无排除 → 走隧道
        assertFalse(
            VpnController.computeShouldBypassTcp(
                InetAddress.getByName("8.8.8.8"),
                emptyList(),
                DnsInterceptor.DnsTransport.SYSTEM,
            ),
        )
        // DOMAIN_SPLIT 未命中域名 (真实 IP) → 直连
        assertTrue(
            VpnController.computeShouldBypassTcp(
                InetAddress.getByName("8.8.8.8"),
                emptyList(),
                DnsInterceptor.DnsTransport.DOMAIN_SPLIT,
            ),
        )
        // DOMAIN_SPLIT 命中域名 (假 IP 198.18.x.x) → 走隧道
        assertFalse(
            VpnController.computeShouldBypassTcp(
                InetAddress.getByName("198.18.0.1"),
                emptyList(),
                DnsInterceptor.DnsTransport.DOMAIN_SPLIT,
            ),
        )
        // DOMAIN_SPLIT 假 IPv6 (fd00::x) → 走隧道
        assertFalse(
            VpnController.computeShouldBypassTcp(
                InetAddress.getByName("fd00::2"),
                emptyList(),
                DnsInterceptor.DnsTransport.DOMAIN_SPLIT,
            ),
        )
    }

    @Test
    fun `bypass syn never touches tunnel plugin`() {
        val tunnelManager = mock<TunnelManager>()
        val protectCalled = CountDownLatch(1)
        val stateMachine =
            TcpStateMachine(
                tunnelManager,
                { null },
                PacketStats(),
                SshIoDispatcher(),
            )
        stateMachine.setBypass(
            shouldBypass = { _, _ -> true },
            protect = {
                protectCalled.countDown()
                true
            },
        )

        val synPacket = buildSynPacket()
        val buffer = ByteBuffer.wrap(synPacket).order(ByteOrder.BIG_ENDIAN)
        stateMachine.processTcpPacket(
            buffer,
            InetAddress.getByName("10.0.0.2"),
            InetAddress.getByName("127.0.0.1"),
            0,
            synPacket.size,
        )

        assertTrue("bypass path (protect) not invoked within 5s", protectCalled.await(5, TimeUnit.SECONDS))
        // bypass 命中后绝不能走到隧道插件 (openTcpChannel 的必经入口)
        verify(tunnelManager, never()).getActiveOrFallback()
    }

    /** 纯 TCP SYN 头 (无 IP 层, payloadStart=0)。 */
    private fun buildSynPacket(): ByteArray =
        ByteBuffer
            .allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(40000.toShort()) // srcPort
                putShort(9.toShort()) // dstPort (discard, 快速拒连)
                putInt(12345) // seq
                putInt(0) // ack
                putShort(((5 shl 12) or 0x02).toShort()) // dataOffset=5, SYN
                putShort(65535.toShort()) // window
                putShort(0) // checksum
                putShort(0) // urgent
            }.array()
}
