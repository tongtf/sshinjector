package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsRuleTextTest {
    @Test
    fun `rule rendering uses gfwlist suffix form`() {
        assertEquals("||example.com^", StatsRuleText.blockRule("example.com"))
        assertEquals("||example.com^", StatsRuleText.blockRule(" Example.com. "))
        assertEquals("@@||example.com^", StatsRuleText.exceptionRule("example.com"))
    }

    @Test
    fun `addLine is idempotent`() {
        val once = StatsRuleText.addLine("", "||a.com^")
        assertEquals("||a.com^", once)
        assertEquals(once, StatsRuleText.addLine(once, "||a.com^"))
        // 前导/尾随空白不影响判重
        assertEquals(once, StatsRuleText.addLine(once, "  ||a.com^  "))
    }

    @Test
    fun `addLine keeps existing lines and order`() {
        val text = StatsRuleText.addLine("||a.com^", "||b.com^")
        assertEquals("||a.com^\n||b.com^", text)
        assertEquals("||a.com^\n||b.com^\n@@||c.com^", StatsRuleText.addLine(text, "@@||c.com^"))
    }

    @Test
    fun `addLine tolerates blank input and blank lines`() {
        assertEquals("", StatsRuleText.addLine("   ", "  "))
        assertEquals("||a.com^", StatsRuleText.addLine("\n\n||a.com^\n\n", "||a.com^"))
    }

    @Test
    fun `removeLine deletes only the exact line`() {
        val text = "||a.com^\n||b.com^\n@@||b.com^"
        assertEquals("||a.com^\n@@||b.com^", StatsRuleText.removeLine(text, "||b.com^"))
        // 例外行与命中行是两行, 删命中行不动例外行
        assertEquals("||a.com^\n||b.com^", StatsRuleText.removeLine(text, "@@||b.com^"))
        // 不存在的行 = 原样
        assertEquals(text, StatsRuleText.removeLine(text, "||zzz.com^"))
    }

    @Test
    fun `add then remove restores the original text`() {
        val original = "||keep.com^"
        val added = StatsRuleText.addLine(original, StatsRuleText.blockRule("new.com"))
        assertEquals(original, StatsRuleText.removeLine(added, StatsRuleText.blockRule("new.com")))
    }

    @Test
    fun `contains distinguishes block from exception rule`() {
        val text = "||a.com^\n@@||b.com^"
        assertTrue(StatsRuleText.contains(text, "||a.com^"))
        assertTrue(StatsRuleText.contains(text, "@@||b.com^"))
        assertFalse(StatsRuleText.contains(text, "@@||a.com^"))
        assertFalse(StatsRuleText.contains(text, "||b.com^"))
        assertFalse(StatsRuleText.contains(text, "  "))
    }

    @Test
    fun `ruleCount ignores blanks`() {
        assertEquals(0, StatsRuleText.ruleCount(""))
        assertEquals(0, StatsRuleText.ruleCount("\n \n"))
        assertEquals(2, StatsRuleText.ruleCount("||a.com^\n\n@@||b.com^\n"))
    }

    @Test
    fun `stats rules actually block through GfwListMatcher and exception wins`() {
        val text =
            StatsRuleText.addLine(
                StatsRuleText.addLine("", StatsRuleText.blockRule("ads.example.com")),
                StatsRuleText.exceptionRule("safe.example.com"),
            )
        val matcher = GfwListMatcher.parse(text)
        assertTrue(matcher.matches("ads.example.com"))
        assertTrue(matcher.matches("x.ads.example.com"))
        assertFalse("只有例外行时本就不该被拦", matcher.matches("safe.example.com"))
        assertFalse(matcher.matches("unrelated.com"))
        // 例外规则优先于命中规则: 同一域名同时有命中与例外 → 不命中
        val both = "||safe.example.com^\n" + StatsRuleText.exceptionRule("safe.example.com")
        assertTrue(GfwListMatcher.parse("||safe.example.com^").matches("safe.example.com"))
        assertFalse(GfwListMatcher.parse(both).matches("safe.example.com"))
    }
}
