package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.SuggestionDirection.ADD
import cn.srv0.sshinjector.domain.vpn.SuggestionDirection.REMOVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsListOpsTest {
    private val empty = StatsListOps.State(null, emptySet(), emptySet())

    private fun ad(
        domain: String,
        direction: SuggestionDirection,
    ) = Suggestion(domain, SuggestionTarget.AD_BLOCK, direction, note = SuggestionNote(0, 0L, 0), createdAt = 0L)

    private fun proxy(
        domain: String,
        direction: SuggestionDirection,
    ) = Suggestion(domain, SuggestionTarget.DOMAIN_LIST, direction, note = SuggestionNote(0, 0L, 0), createdAt = 0L)

    @Test
    fun `ad block add writes a block rule`() {
        val out = StatsListOps.apply(empty, listOf(ad("a.com", ADD)))
        assertEquals("||a.com^", out.adBlockRulesFromStats)
        assertTrue(GfwListMatcher.parse(out.adBlockRulesFromStats.orEmpty()).matches("x.a.com"))
    }

    @Test
    fun `ad block add is idempotent`() {
        val once = StatsListOps.apply(empty, listOf(ad("a.com", ADD)))
        assertEquals(once, StatsListOps.apply(once, listOf(ad("a.com", ADD))))
    }

    @Test
    fun `ad block remove deletes the stats-authored rule line`() {
        val added = StatsListOps.apply(empty, listOf(ad("a.com", ADD)))
        val removed = StatsListOps.apply(added, listOf(ad("a.com", REMOVE)))
        assertNull(removed.adBlockRulesFromStats)
    }

    @Test
    fun `ad block remove of a non-stats domain falls back to an exception rule`() {
        // 命中来自基础清单 (不是统计加的) → 只能加例外, 且例外真的能压住命中
        val out = StatsListOps.apply(empty, listOf(ad("blocked.com", REMOVE)))
        assertEquals("@@||blocked.com^", out.adBlockRulesFromStats)
        val matcher = GfwListMatcher.parse("||blocked.com^\n" + out.adBlockRulesFromStats.orEmpty())
        assertFalse(matcher.matches("blocked.com"))
    }

    @Test
    fun `ad block add clears a stale exception rule so the click actually takes effect`() {
        val withException = StatsListOps.apply(empty, listOf(ad("a.com", REMOVE)))
        val added = StatsListOps.apply(withException, listOf(ad("a.com", ADD)))
        assertEquals("||a.com^", added.adBlockRulesFromStats)
        assertTrue(GfwListMatcher.parse(added.adBlockRulesFromStats.orEmpty()).matches("a.com"))
    }

    @Test
    fun `ad block keeps unrelated rules`() {
        val base = StatsListOps.State("||keep.com^\n@@||other.com^", emptySet(), emptySet())
        val out = StatsListOps.apply(base, listOf(ad("a.com", ADD)))
        assertEquals("||keep.com^\n@@||other.com^\n||a.com^", out.adBlockRulesFromStats)
    }

    @Test
    fun `blank rules are normalized to null`() {
        val out = StatsListOps.apply(StatsListOps.State("  \n ", emptySet(), emptySet()), emptyList())
        assertNull(out.adBlockRulesFromStats)
    }

    @Test
    fun `domain list add and remove are mutually exclusive`() {
        val start = StatsListOps.State(null, emptySet(), setOf("a.com"))
        val added = StatsListOps.apply(start, listOf(proxy("a.com", ADD)))
        assertEquals(setOf("a.com"), added.domainListAdded)
        assertTrue(added.domainListRemoved.isEmpty())

        val flipped = StatsListOps.apply(added, listOf(proxy("a.com", REMOVE)))
        assertEquals(setOf("a.com"), flipped.domainListRemoved)
        assertTrue(flipped.domainListAdded.isEmpty())
    }

    @Test
    fun `domains are normalized and blank ones ignored`() {
        val out = StatsListOps.apply(empty, listOf(proxy("  A.COM. ", ADD), ad("   ", ADD)))
        assertEquals(setOf("a.com"), out.domainListAdded)
        assertNull(out.adBlockRulesFromStats)
    }

    @Test
    fun `both targets in one batch are applied independently`() {
        val out = StatsListOps.apply(empty, listOf(ad("ad.com", ADD), proxy("proxy.com", ADD)))
        assertEquals("||ad.com^", out.adBlockRulesFromStats)
        assertEquals(setOf("proxy.com"), out.domainListAdded)
    }

    @Test
    fun `apply with no items is a no-op and the pre-apply snapshot stays intact for restore`() {
        val before = StatsListOps.State("||keep.com^\n||other.com^", setOf("added.com"), setOf("removed.com"))
        val after = StatsListOps.apply(before, listOf(ad("keep.com", REMOVE)))
        assertEquals("||other.com^", after.adBlockRulesFromStats)
        assertEquals(after, StatsListOps.apply(after, emptyList()))
        // 撤销 token = 应用前的快照 (值语义: apply 不会就地改它), applier 的 restore 就是原样写回
        assertEquals("||keep.com^\n||other.com^", before.adBlockRulesFromStats)
        assertEquals(setOf("added.com"), before.domainListAdded)
        assertEquals(setOf("removed.com"), before.domainListRemoved)
    }
}
