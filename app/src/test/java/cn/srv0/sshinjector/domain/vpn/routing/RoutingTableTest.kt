package cn.srv0.sshinjector.domain.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * 规则解析 + 匹配语义 (设计 §6.2 / §6.3 / 附录 A)。
 *
 * 这些用例锁的都是"配了却没反应"或"静默丢用户配置"的形态 —— 无 adb 现场最难归因的一类:
 * 规则在 UI 里看得见, 数据面却因为一行语法/顺序/大小写问题完全没生效。
 */
class RoutingTableTest {
    private val v4 = InetAddress.getByName("93.184.216.34")
    private val v6 = InetAddress.getByName("2001:db8::1")

    private fun table(
        text: String,
        exits: Set<String> = setOf("hk", "jp"),
    ): RoutingTable = RoutingTable.fromText(text, exits)

    private fun target(
        pkg: String? = null,
        domain: String? = null,
        ip: InetAddress = v4,
        port: Int = 443,
    ) = RouteTarget(pkg = pkg, domain = domain, ip = ip, port = port)

    @Test
    fun `app rule requires a resolved package name`() {
        val t = table("app:com.example.bank -> jp")

        assertEquals("jp", t.select(target(pkg = "com.example.bank")))
        assertNull("拿不到包名时 app 规则必须跳过 (绝不回退直连)", t.select(target(pkg = null)))
        assertNull(t.select(target(pkg = "com.example.other")))
    }

    @Test
    fun `app rule with port only matches that port`() {
        val t = table("app:com.example.video:443 -> hk")

        assertEquals("hk", t.select(target(pkg = "com.example.video", port = 443)))
        assertNull(t.select(target(pkg = "com.example.video", port = 80)))
    }

    @Test
    fun `domain rule matches apex and subdomains`() {
        val t = table("example.com -> hk")

        assertEquals("hk", t.select(target(domain = "example.com")))
        assertEquals("hk", t.select(target(domain = "a.b.example.com")))
        assertEquals("hk", t.select(target(domain = "EXAMPLE.com")))
        assertNull("后缀必须按标签边界, 不能是裸 endsWith", t.select(target(domain = "notexample.com")))
    }

    @Test
    fun `wildcard prefix is equivalent to the bare suffix`() {
        val t = table("*.googlevideo.com -> hk")

        assertEquals("hk", t.select(target(domain = "r1.googlevideo.com")))
        assertEquals("hk", t.select(target(domain = "googlevideo.com")))
    }

    @Test
    fun `domain rule is skipped when the domain is unknown`() {
        val t = table("example.com -> hk\ndefault -> jp")

        assertEquals("jp", t.select(target(domain = null)))
    }

    @Test
    fun `domain rule honours the port`() {
        val t = table("example.co.jp:443 -> jp")

        assertEquals("jp", t.select(target(domain = "example.co.jp", port = 443)))
        assertNull(t.select(target(domain = "example.co.jp", port = 80)))
    }

    @Test
    fun `cidr rules match by family and never cross families`() {
        val t = table("203.0.113.0/24 -> hk\n[2001:db8::]/32 -> jp")

        assertEquals("hk", t.select(target(ip = InetAddress.getByName("203.0.113.7"))))
        assertNull(t.select(target(ip = InetAddress.getByName("203.0.114.7"))))
        assertEquals("jp", t.select(target(ip = v6)))
        assertNull(
            "IPv4 规则不得命中 IPv6 目标 (跨族)",
            t.select(target(ip = InetAddress.getByName("2001:db9::1"))),
        )
    }

    @Test
    fun `exact ip is a 32 or 128 bit route`() {
        val t = table("198.51.100.7 -> hk\n[2001:db8::1] -> jp")

        assertEquals("hk", t.select(target(ip = InetAddress.getByName("198.51.100.7"))))
        assertNull(t.select(target(ip = InetAddress.getByName("198.51.100.8"))))
        assertEquals("jp", t.select(target(ip = v6)))
    }

    @Test
    fun `ipv4 with port is accepted`() {
        val t = table("198.51.100.7:8443 -> hk")

        assertEquals("hk", t.select(target(ip = InetAddress.getByName("198.51.100.7"), port = 8443)))
        assertNull(t.select(target(ip = InetAddress.getByName("198.51.100.7"), port = 443)))
    }

    @Test
    fun `first matching rule wins in file order`() {
        val t =
            table(
                """
                app:com.example.bank -> jp
                example.com -> hk
                """.trimIndent(),
            )

        // 应用规则写在前面 → 同一个目标同时命中两条时 app 优先
        assertEquals("jp", t.select(target(pkg = "com.example.bank", domain = "example.com")))
        assertEquals("hk", t.select(target(pkg = "com.example.other", domain = "example.com")))
    }

    @Test
    fun `default is returned explicitly and may be a rule target`() {
        val t = table("example.com -> default\ndefault -> hk")

        assertEquals("default", t.select(target(domain = "example.com")))
        assertEquals("hk", t.select(target(domain = "other.com")))
    }

    @Test
    fun `default that is not the last line is dropped`() {
        val parsed = ExitRuleParser.parse("default -> hk\nexample.com -> jp")

        assertEquals(1, parsed.rules.size)
        assertTrue(parsed.rules.single() is ExitRule.Domain)
        assertTrue(parsed.issues.any { it.issue == RuleIssue.DEFAULT_NOT_LAST && it.lineNo == 1 })
    }

    @Test
    fun `unknown exit is kept but skipped until the exit exists`() {
        val t = table("example.com -> jp", exits = setOf("hk"))

        assertNull("出口不存在 → 这一行暂时等于不存在", t.select(target(domain = "example.com")))
        assertEquals(1, t.unresolvedRules.size)

        val resolved = t.withKnownExits(setOf("hk", "jp"))
        assertEquals("jp", resolved.select(target(domain = "example.com")))
        assertTrue(resolved.unresolvedRules.isEmpty())
    }

    @Test
    fun `duplicate target keeps the first occurrence`() {
        val parsed = ExitRuleParser.parse("example.com -> hk\nexample.com -> jp")

        assertEquals(1, parsed.rules.size)
        assertEquals("hk", parsed.rules.single().exitId)
        assertTrue(parsed.issues.any { it.issue == RuleIssue.DUPLICATE_TARGET && it.lineNo == 2 })
    }

    @Test
    fun `syntax errors drop only their own line`() {
        val parsed =
            ExitRuleParser.parse(
                """
                app:com -> hk
                example.com:0 -> hk
                203.0.113.0/99 -> hk
                example.com -> jp
                """.trimIndent(),
            )

        assertEquals(1, parsed.rules.size)
        assertEquals("example.com", (parsed.rules.single() as ExitRule.Domain).suffix)
        assertTrue(parsed.issues.any { it.issue == RuleIssue.INVALID_PACKAGE })
        assertTrue(parsed.issues.any { it.issue == RuleIssue.INVALID_PORT })
        assertTrue(parsed.issues.any { it.issue == RuleIssue.INVALID_CIDR })
    }

    @Test
    fun `catch all routes are rejected in favour of default`() {
        val parsed = ExitRuleParser.parse("0.0.0.0/0 -> hk\n::/0 -> jp")

        assertTrue(parsed.rules.isEmpty())
        assertEquals(2, parsed.issues.count { it.issue == RuleIssue.DEFAULT_ROUTE_REJECTED })
    }

    @Test
    fun `invalid exit names are syntax errors but unknown ones are not`() {
        val parsed = ExitRuleParser.parse("example.com -> HK 1\nexample.com -> jp")

        assertEquals(1, parsed.rules.size)
        assertEquals("jp", parsed.rules.single().exitId)
        assertTrue(parsed.issues.any { it.issue == RuleIssue.INVALID_EXIT })
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val parsed =
            ExitRuleParser.parse(
                """
                # 应用分流
                app:com.example.app -> jp

                  # 缩进注释
                example.com -> hk   # 行尾注释
                """.trimIndent(),
            )

        assertEquals(2, parsed.rules.size)
        assertTrue(parsed.issues.isEmpty())
    }

    @Test
    fun `more than the line budget refuses the whole document`() {
        val text = (0..ExitRuleParser.MAX_RULES).joinToString("\n") { "h$it.example.com -> hk" }

        val parsed = ExitRuleParser.parse(text)
        assertFalse(parsed.ok)
        assertTrue("超限必须整体拒绝保存, 不能静默截断", parsed.rules.isEmpty())
        assertTrue(parsed.issues.any { it.issue == RuleIssue.TOO_MANY_RULES })
    }

    @Test
    fun `has domain rule ignores ports and app rules`() {
        val t = table("app:com.example.app -> jp\nexample.com:443 -> hk")

        assertTrue("端口限定不影响 D-5 的隧道提升判定", t.hasDomainRule("example.com"))
        assertTrue(t.hasDomainRule("a.example.com"))
        assertFalse(t.hasDomainRule("other.com"))
        assertFalse("app 规则不能提升域名", t.hasDomainRule("com.example.app"))
    }

    @Test
    fun `unresolved domain rule does not promote traffic into the tunnel`() {
        val t = table("example.com -> jp", exits = setOf("hk"))

        assertFalse("出口不存在的规则是惰性的, 不能改变直连/隧道判定", t.hasDomainRule("example.com"))
    }

    @Test
    fun `empty text yields an empty table`() {
        val t = table("")

        assertTrue(t.isEmpty)
        assertNull(t.select(target(domain = "example.com")))
    }
}
