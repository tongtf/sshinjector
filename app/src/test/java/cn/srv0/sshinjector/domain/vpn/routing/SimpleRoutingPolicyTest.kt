package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简单路由模型 ↔ 规则文本。
 *
 * 锁的是两类会"静默丢配置"的形态: 简单模式表达不了的规则必须归 ADVANCED_RULE (不能丢),
 * 序列化顺序必须是优先级顺序 (应用保序 / 域名最长后缀优先 / default 最后)。
 */
class SimpleRoutingPolicyTest {
    private fun policyOf(text: String): SimpleRoutingPolicy {
        val result = SimpleRoutingPolicyCodec.parse(text)
        assertTrue("应支持简单模式: $result", result is SimplePolicyResult.Ok)
        return (result as SimplePolicyResult.Ok).policy
    }

    private fun reasonOf(text: String): SimplePolicyUnsupported {
        val result = SimpleRoutingPolicyCodec.parse(text)
        assertTrue("应判为不支持: $result", result is SimplePolicyResult.Unsupported)
        return (result as SimplePolicyResult.Unsupported).reason
    }

    @Test
    fun `simple text round trips exactly`() {
        val text =
            """
            app:com.example.bank -> jp
            example.com -> hk
            default -> us
            """.trimIndent()

        val policy = policyOf(text)

        assertEquals("us", policy.defaultExit)
        assertEquals(listOf(SimpleAppRoute("com.example.bank", "jp")), policy.apps)
        assertEquals(listOf(SimpleDomainRoute("example.com", "hk")), policy.domains)
        assertEquals(text, SimpleRoutingPolicyCodec.toText(policy))
    }

    @Test
    fun `missing default line falls back to the main exit`() {
        val policy = policyOf("example.com -> hk")

        assertEquals(ExitId.DEFAULT, policy.defaultExit)
        assertEquals("default -> default", SimpleRoutingPolicyCodec.toText(policy).lines().last())
    }

    @Test
    fun `apps keep list order and default is always last`() {
        val policy =
            SimpleRoutingPolicy(
                defaultExit = "us",
                apps = listOf(SimpleAppRoute("com.b", "hk"), SimpleAppRoute("com.a", "jp")),
            )

        val lines = SimpleRoutingPolicyCodec.toText(policy).lines()

        assertEquals("app:com.b -> hk", lines[0])
        assertEquals("app:com.a -> jp", lines[1])
        assertEquals("default -> us", lines.last())
    }

    @Test
    fun `domains are sorted by suffix length descending then alphabetically`() {
        val policy =
            SimpleRoutingPolicy(
                domains =
                    listOf(
                        SimpleDomainRoute("a.com", "us"),
                        SimpleDomainRoute("example.com", "hk"),
                        SimpleDomainRoute("b.example.com", "jp"),
                        SimpleDomainRoute("a.example.com", "jp"),
                    ),
            )

        val domainLines = SimpleRoutingPolicyCodec.toText(policy).lines().dropLast(1)

        assertEquals(
            listOf(
                "a.example.com -> jp",
                "b.example.com -> jp",
                "example.com -> hk",
                "a.com -> us",
            ),
            domainLines,
        )
    }

    @Test
    fun `serialized text parses back into the same policy`() {
        val policy =
            SimpleRoutingPolicy(
                defaultExit = "us",
                apps = listOf(SimpleAppRoute("com.example.bank", "jp")),
                // 域名列表按 toText 的规范顺序给出 (最长后缀优先), 往返才逐字段相等
                domains = listOf(SimpleDomainRoute("a.example.com", "jp"), SimpleDomainRoute("example.com", "hk")),
            )

        assertEquals(policy, policyOf(SimpleRoutingPolicyCodec.toText(policy)))
    }

    @Test
    fun `network rules are not representable in simple mode`() {
        assertEquals(SimplePolicyUnsupported.ADVANCED_RULE, reasonOf("203.0.113.0/24 -> hk"))
        assertEquals(SimplePolicyUnsupported.ADVANCED_RULE, reasonOf("1.2.3.4 -> hk"))
    }

    @Test
    fun `port qualified rules are not representable in simple mode`() {
        assertEquals(SimplePolicyUnsupported.ADVANCED_RULE, reasonOf("app:com.example.bank:443 -> jp"))
        assertEquals(SimplePolicyUnsupported.ADVANCED_RULE, reasonOf("example.com:443 -> hk"))
    }

    @Test
    fun `syntax errors map to invalid rule`() {
        assertEquals(SimplePolicyUnsupported.INVALID_RULE, reasonOf("example.com hk"))
        assertEquals(SimplePolicyUnsupported.INVALID_RULE, reasonOf("0.0.0.0/0 -> hk"))
    }

    @Test
    fun `default before other rules is rejected by the parser and mapped to invalid`() {
        assertEquals(
            SimplePolicyUnsupported.INVALID_RULE,
            reasonOf("default -> hk\nexample.com -> jp"),
        )
    }

    @Test
    fun `too many rules map to invalid rule`() {
        val text = (1..(ExitRuleParser.MAX_RULES + 1)).joinToString("\n") { "d$it.example.com -> hk" }

        assertEquals(SimplePolicyUnsupported.INVALID_RULE, reasonOf(text))
    }

    @Test
    fun `upsert app replaces in place and dedupes`() {
        val base =
            SimpleRoutingPolicy(
                apps = listOf(SimpleAppRoute("com.a", "hk"), SimpleAppRoute("com.b", "jp")),
            )

        val updated = base.upsertApp("com.a", "us")

        assertEquals(
            listOf(SimpleAppRoute("com.a", "us"), SimpleAppRoute("com.b", "jp")),
            updated.apps,
        )
        assertEquals("同包名不得出现第二条", 2, updated.upsertApp("com.a", "hk").apps.size)
    }

    @Test
    fun `upsert domain normalizes wildcard and dedupes`() {
        val base = SimpleRoutingPolicy(domains = listOf(SimpleDomainRoute("example.com", "hk")))

        val updated = base.upsertDomain("*.Example.COM", "jp")

        assertEquals(listOf(SimpleDomainRoute("example.com", "jp")), updated.domains)
    }

    @Test
    fun `remove and with default work on normalized values`() {
        val base =
            SimpleRoutingPolicy(
                defaultExit = "hk",
                apps = listOf(SimpleAppRoute("com.a", "hk")),
                domains = listOf(SimpleDomainRoute("example.com", "hk")),
            )

        assertTrue(base.removeApp("com.a").apps.isEmpty())
        assertTrue(base.removeDomain("*.example.com").domains.isEmpty())
        assertEquals("jp", base.withDefault(" JP ").defaultExit)
        assertEquals("非法出口名不得改动模型", base, base.withDefault("not an exit"))
    }

    @Test
    fun `normalize domain accepts apex and wildcard only`() {
        assertEquals("example.com", SimpleRoutingPolicyCodec.normalizeDomain(" Example.COM. "))
        assertEquals("example.com", SimpleRoutingPolicyCodec.normalizeDomain("*.Example.com"))
        assertNull(SimpleRoutingPolicyCodec.normalizeDomain("  "))
        assertNull(SimpleRoutingPolicyCodec.normalizeDomain("198.18.0.1"))
        assertNull(SimpleRoutingPolicyCodec.normalizeDomain("localhost"))
        assertNull(SimpleRoutingPolicyCodec.normalizeDomain("-bad.example.com"))
    }

    @Test
    fun `package validity shares the parser rule`() {
        assertTrue(SimpleRoutingPolicyCodec.isValidPackage("com.example.app"))
        assertTrue(SimpleRoutingPolicyCodec.isValidPackage(" com.example.app "))
        assertFalse(SimpleRoutingPolicyCodec.isValidPackage("com"))
        assertFalse(SimpleRoutingPolicyCodec.isValidPackage("com.example.app/sub"))
    }

    @Test
    fun `generated text keeps app before domain before default`() {
        val policy =
            SimpleRoutingPolicy(
                defaultExit = "hk",
                apps = listOf(SimpleAppRoute("com.example.app", "us")),
                domains =
                    listOf(
                        SimpleDomainRoute("example.com", "jp"),
                        SimpleDomainRoute("a.example.com", "us"),
                    ),
            )
        val table = RoutingTable.fromText(SimpleRoutingPolicyCodec.toText(policy), setOf("us", "jp", "hk"))
        val ip = java.net.InetAddress.getByName("1.2.3.4")

        assertEquals(
            "us",
            table.select(RouteTarget(uid = 1, pkg = "com.example.app", domain = "a.example.com", ip = ip, port = 443)),
        )
        assertEquals(
            "us",
            table.select(RouteTarget(uid = 1, pkg = null, domain = "a.example.com", ip = ip, port = 443)),
        )
        assertEquals(
            "jp",
            table.select(RouteTarget(uid = 1, pkg = null, domain = "b.example.com", ip = ip, port = 443)),
        )
        assertEquals(
            "hk",
            table.select(RouteTarget(uid = 1, pkg = null, domain = "other.com", ip = ip, port = 443)),
        )
    }
}
