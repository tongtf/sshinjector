package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.CidrRoute
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * [VpnController] 里可脱离 Android 单测的纯策略函数: 分流直连判定与假 IP 识别。
 * 这两条错了表现都是"该走隧道的走了直连 / 该直连的走了隧道", 现场只能看到某个域名
 * 时通时不通, 靠日志几乎无法归因, 所以必须锁在单测里。
 */
class VpnControllerTest {
    private val fakeV4 = InetAddress.getByName("198.18.0.7")
    private val realV4 = InetAddress.getByName("142.250.72.14")
    private val fakeV6 = InetAddress.getByName("fd00::2")

    @Test
    fun `excluded route forces bypass in every transport mode`() {
        val route = CidrRoute.parse("10.0.0.0/8")!!
        val dst = InetAddress.getByName("10.1.2.3")
        for (mode in DnsInterceptor.DnsTransport.entries) {
            assertTrue(
                "route match must bypass regardless of mode $mode",
                VpnController.computeShouldBypassTcp(dst, listOf(route), mode),
            )
        }
    }

    @Test
    fun `route match takes precedence over a fake ip in domain split mode`() {
        val route = CidrRoute.parse("198.18.0.0/15")!!
        assertTrue(
            "even a fake ip must bypass when the route is excluded",
            VpnController.computeShouldBypassTcp(fakeV4, listOf(route), DnsInterceptor.DnsTransport.DOMAIN_SPLIT),
        )
    }

    @Test
    fun `domain split bypasses only real destinations`() {
        assertTrue(
            "real ip must bypass in DOMAIN_SPLIT (list miss goes direct)",
            VpnController.computeShouldBypassTcp(realV4, emptyList(), DnsInterceptor.DnsTransport.DOMAIN_SPLIT),
        )
        assertFalse(
            "fake ip must stay tunneled in DOMAIN_SPLIT",
            VpnController.computeShouldBypassTcp(fakeV4, emptyList(), DnsInterceptor.DnsTransport.DOMAIN_SPLIT),
        )
        assertFalse(
            "fake ipv6 must stay tunneled in DOMAIN_SPLIT",
            VpnController.computeShouldBypassTcp(fakeV6, emptyList(), DnsInterceptor.DnsTransport.DOMAIN_SPLIT),
        )
    }

    @Test
    fun `non domain split modes never bypass a plain destination`() {
        for (mode in listOf(
            DnsInterceptor.DnsTransport.REMOTE,
            DnsInterceptor.DnsTransport.SYSTEM,
            DnsInterceptor.DnsTransport.WHITELIST,
        )) {
            assertFalse(
                "mode $mode must not bypass without an excluded route",
                VpnController.computeShouldBypassTcp(realV4, emptyList(), mode),
            )
        }
    }

    @Test
    fun `fake ip detection covers both families and rejects near misses`() {
        // 198.18.0.0/15 = 198.18.x.x + 198.19.x.x
        assertTrue(VpnController.isFakeIp(InetAddress.getByName("198.18.0.0")))
        assertTrue(VpnController.isFakeIp(InetAddress.getByName("198.19.255.255")))
        assertFalse("198.20 is outside /15", VpnController.isFakeIp(InetAddress.getByName("198.20.0.1")))
        assertFalse("public v4 is not fake", VpnController.isFakeIp(realV4))
        assertTrue("fd00::/8 is the fake v6 pool", VpnController.isFakeIp(fakeV6))
        assertFalse("2400:: is a real global v6", VpnController.isFakeIp(InetAddress.getByName("2400:cb00::2048")))
    }

    @Test
    fun `fakeIpOrNull is the refcount table key and drops real destinations`() {
        assertEquals("198.18.0.7", VpnController.fakeIpOrNull(fakeV4))
        assertEquals("fd00:0:0:0:0:0:0:2", VpnController.fakeIpOrNull(fakeV6))
        assertNull(
            "real ip must not enter the fake-ip refcount table",
            VpnController.fakeIpOrNull(realV4),
        )
    }
}
