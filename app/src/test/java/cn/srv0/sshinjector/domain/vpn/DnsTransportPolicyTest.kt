package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsTransportPolicyTest {
    @Test
    fun `mode 0 maps to REMOTE regardless of whitelist`() {
        assertEquals(DnsInterceptor.DnsTransport.REMOTE, dnsTransportFor(0, emptyList()))
        assertEquals(DnsInterceptor.DnsTransport.REMOTE, dnsTransportFor(0, listOf("com.example.browser")))
    }

    @Test
    fun `mode 1 maps to SYSTEM`() {
        assertEquals(DnsInterceptor.DnsTransport.SYSTEM, dnsTransportFor(1, emptyList()))
    }

    @Test
    fun `whitelist mode with enabled packages keeps fake-ip tunnel`() {
        assertEquals(
            DnsInterceptor.DnsTransport.WHITELIST,
            dnsTransportFor(2, listOf("com.example.browser")),
        )
    }

    @Test
    fun `whitelist mode with empty list degrades to SYSTEM so direct access survives`() {
        // 空名单: 无路由 + DNS 仍进 TUN (10.0.0.2 on-link) → 回假 IP 会因 198.18/15 无路由而黑洞直连
        assertEquals(DnsInterceptor.DnsTransport.SYSTEM, dnsTransportFor(2, emptyList()))
    }

    @Test
    fun `mode 3 maps to DOMAIN_SPLIT and unknown modes fall back to REMOTE`() {
        assertEquals(DnsInterceptor.DnsTransport.DOMAIN_SPLIT, dnsTransportFor(3, emptyList()))
        assertEquals(DnsInterceptor.DnsTransport.REMOTE, dnsTransportFor(42, emptyList()))
    }
}
