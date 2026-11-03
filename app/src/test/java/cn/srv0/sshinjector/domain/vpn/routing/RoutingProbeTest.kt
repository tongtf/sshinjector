package cn.srv0.sshinjector.domain.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 规则编辑器测试框 (设计 §13.3)。
 *
 * 关键点: 测试框必须走**同一份**解析结果与匹配算法 —— 否则会出现"UI 说命中, 数据面走 default"
 * 这种无法靠肉眼发现的分歧。这里除了验证输入形态, 也锁住"未命中就明确说 default"的语义。
 */
class RoutingProbeTest {
    private val table =
        RoutingTable.fromText(
            """
            app:com.example.bank -> jp
            example.com -> hk
            example.co.jp:443 -> jp
            203.0.113.0/24 -> hk
            default -> hk
            """.trimIndent(),
            setOf("hk", "jp"),
        )

    @Test
    fun `domain input matches a suffix rule`() {
        val result = RoutingProbe.evaluate(table, "a.example.com")
        assertEquals("hk", result.exitId)
        assertNull(result.error)
    }

    @Test
    fun `port in the query is honoured`() {
        assertEquals("jp", RoutingProbe.evaluate(table, "example.co.jp:443").exitId)
        assertEquals("hk", RoutingProbe.evaluate(table, "example.co.jp:8443").exitId)
    }

    @Test
    fun `app input matches an app rule`() {
        val result = RoutingProbe.evaluate(table, "app:com.example.bank")
        assertEquals("jp", result.exitId)
        assertEquals("com.example.bank", result.target?.pkg)
    }

    @Test
    fun `ip input matches a cidr rule`() {
        val result = RoutingProbe.evaluate(table, "203.0.113.9:443")
        assertEquals("hk", result.exitId)
        assertNotNull(result.target?.ip)
    }

    @Test
    fun `a pasted url is reduced to its host`() {
        val result = RoutingProbe.evaluate(table, "https://a.example.com/path?q=1")
        assertEquals("hk", result.exitId)
        assertEquals("a.example.com", result.target?.domain)
    }

    @Test
    fun `unmatched input falls back to default`() {
        // 没有 default 行时 select 返回 null → 调用方理解为"主出口", 而不是"命中失败"
        val noDefault = RoutingTable.fromText("example.com -> hk", setOf("hk"))
        val result = RoutingProbe.evaluate(noDefault, "other.org")
        assertNull(result.exitId)
        assertNull(result.error)
    }

    @Test
    fun `a default rule is reported as its own exit`() {
        assertEquals("hk", RoutingProbe.evaluate(table, "other.org").exitId)
    }

    @Test
    fun `blank input is an error not a default match`() {
        val result = RoutingProbe.evaluate(table, "   ")
        assertNull(result.exitId)
        assertNotNull("空输入不能伪装成 default 命中", result.error)
    }

    @Test
    fun `invalid port is reported`() {
        assertNotNull(RoutingProbe.evaluate(table, "example.com:0").error)
        assertNotNull(RoutingProbe.evaluate(table, "example.com:99999").error)
    }
}
