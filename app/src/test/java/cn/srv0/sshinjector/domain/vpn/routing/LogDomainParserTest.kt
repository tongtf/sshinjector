package cn.srv0.sshinjector.domain.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日志行 → 域名。
 *
 * 日志页的"出口"按钮只在这些固定文案上出现; 一旦解析错, 用户点下去写的就是一条
 * 永远不命中的规则 (给 IP 写域名规则) —— 所以 IP 字面量与占位符必须显式排除。
 */
class LogDomainParserTest {
    @Test
    fun `tcp connection close line`() {
        val message = "TCP 连接结束 · example.com(443) — 远端正常关闭(clean EOF) · 上行 0B / 下行 1KB"

        assertEquals("example.com", LogDomainParser.domainOf(message))
    }

    @Test
    fun `tunnel channel ready line`() {
        assertEquals(
            "api.example.com",
            LogDomainParser.domainOf("隧道通道就绪 · api.example.com(443) — SSH+SOCKS 建连耗时 120ms"),
        )
    }

    @Test
    fun `remote does not resolve target line`() {
        val message = "远端不解析该目标 (域名可能不存在) · update.example.com:443 — 3/3 候选秒拒"

        assertEquals("update.example.com", LogDomainParser.domainOf(message))
    }

    @Test
    fun `socks rejected line`() {
        assertEquals(
            "www.example.com",
            LogDomainParser.domainOf("SOCKS 连接被拒 · www.example.com(443) — rep=5 (远端拒绝该目标)"),
        )
    }

    @Test
    fun `ipv4 literals are rejected`() {
        assertNull(LogDomainParser.domainOf("TCP 连接结束 · 1.2.3.4:443 — 客户端关闭(RST/FIN)"))
        assertNull(LogDomainParser.domainOf("隧道通道就绪 · 1.2.3.4(443) — SSH+SOCKS 建连耗时 80ms"))
    }

    @Test
    fun `ipv6 literals are rejected with and without brackets`() {
        assertNull(LogDomainParser.domainOf("TCP 连接结束 · 2001:db8::1(443) — 远端正常关闭"))
        assertNull(LogDomainParser.domainOf("TCP 连接结束 · [2001:db8::1](443) — 远端正常关闭"))
        assertNull(LogDomainParser.domainOf("远端不解析该目标 (…) · 2001:db8::1:443"))
    }

    @Test
    fun `single label host is rejected because it cannot be a domain rule`() {
        assertNull(LogDomainParser.domainOf("TCP 连接结束 · localhost(443) — 客户端关闭"))
    }

    @Test
    fun `placeholder and empty messages are rejected`() {
        assertNull(LogDomainParser.domainOf("TCP 连接结束 · ?(443) — 客户端关闭"))
        assertNull(LogDomainParser.domainOf("TCP 连接结束 · -(443) — 客户端关闭"))
        assertNull(LogDomainParser.domainOf(""))
        assertNull(LogDomainParser.domainOf("TCP 连接结束"))
        assertNull(LogDomainParser.domainOf("TCP 连接结束 ·"))
    }

    @Test
    fun `unrelated log lines are ignored`() {
        assertNull(LogDomainParser.domainOf("数据面快照 · 上行 1.2MB / 下行 3.4MB"))
        assertNull(LogDomainParser.domainOf("example.com 出现在正文里但不是目标标识"))
    }
}
