package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.vpn.StatsListOps
import cn.srv0.sshinjector.domain.vpn.Suggestion
import cn.srv0.sshinjector.domain.vpn.SuggestionDirection
import cn.srv0.sshinjector.domain.vpn.SuggestionNote
import cn.srv0.sshinjector.domain.vpn.SuggestionTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * 只覆盖 [StatsListApplier] 的接线与串行 (纯变换的语义在 `StatsListOpsTest`):
 * ① 写的是"统计生成"的三个键; ② 写完立刻 reload 两边 (广告名单才需要重编译 matcher);
 * ③ 返回的 token 是**应用前**快照, restore 原样写回。
 */
class StatsListApplierTest {
    /** 三个键的内存替身。 */
    private class FakeKeys {
        var adRules: String? = null
        var added: Set<String> = emptySet()
        var removed: Set<String> = emptySet()
    }

    private fun newSettings(f: FakeKeys): SettingsDataStore =
        mock {
            onBlocking { getAdBlockRulesFromStats() } doSuspendableAnswer { f.adRules }
            onBlocking { getDomainListAddedFromStats() } doSuspendableAnswer { f.added }
            onBlocking { getDomainListRemovedFromStats() } doSuspendableAnswer { f.removed }
            onBlocking { setAdBlockRulesFromStats(anyOrNull()) } doSuspendableAnswer { inv ->
                f.adRules = inv.getArgument(0)
            }
            onBlocking { setDomainListAddedFromStats(any()) } doSuspendableAnswer { inv ->
                f.added = inv.getArgument(0)
            }
            onBlocking { setDomainListRemovedFromStats(any()) } doSuspendableAnswer { inv ->
                f.removed = inv.getArgument(0)
            }
        }

    private fun suggestion(
        domain: String,
        target: SuggestionTarget,
        direction: SuggestionDirection,
    ) = Suggestion(domain, target, direction, note = SuggestionNote(0, 0L, 0), createdAt = 0L)

    @Test
    fun `apply writes all three stats keys and reloads both sides`() =
        runTest {
            val fake = FakeKeys()
            val settings = newSettings(fake)
            val domainList = mock<DomainListManager>()
            val vpn = mock<VpnController>()
            val applier = StatsListApplier(settings, domainList, vpn)

            val token =
                applier.apply(
                    listOf(
                        suggestion("ad.com", SuggestionTarget.AD_BLOCK, SuggestionDirection.ADD),
                        suggestion("proxy.com", SuggestionTarget.DOMAIN_LIST, SuggestionDirection.ADD),
                    ),
                )

            assertEquals("||ad.com^", fake.adRules)
            assertEquals(setOf("proxy.com"), fake.added)
            assertEquals(setOf<String>(), fake.removed)
            verify(domainList).reloadOverlay()
            verify(vpn).reloadAdBlockRules()
            // token = 应用前 (全空)
            assertEquals(StatsListOps.State(null, emptySet(), emptySet()), token)
        }

    @Test
    fun `domain-list-only apply does not recompile the ad matcher`() =
        runTest {
            val fake = FakeKeys()
            val domainList = mock<DomainListManager>()
            val vpn = mock<VpnController>()
            val applier = StatsListApplier(newSettings(fake), domainList, vpn)

            applier.apply(listOf(suggestion("proxy.com", SuggestionTarget.DOMAIN_LIST, SuggestionDirection.ADD)))

            verify(domainList).reloadOverlay()
            verify(vpn, never()).reloadAdBlockRules()
        }

    @Test
    fun `restore writes the token back and reloads both sides`() =
        runTest {
            val fake = FakeKeys()
            val domainList = mock<DomainListManager>()
            val vpn = mock<VpnController>()
            val applier = StatsListApplier(newSettings(fake), domainList, vpn)

            val token = applier.apply(listOf(suggestion("ad.com", SuggestionTarget.AD_BLOCK, SuggestionDirection.ADD)))
            applier.restore(token)

            assertEquals(null, fake.adRules)
            assertTrue(fake.added.isEmpty())
            assertTrue(fake.removed.isEmpty())
            verify(vpn, atLeastOnce()).reloadAdBlockRules()
        }

    @Test
    fun `empty batch is a no-op that still returns the current state as token`() =
        runTest {
            val fake = FakeKeys()
            fake.adRules = "||keep.com^"
            val domainList = mock<DomainListManager>()
            val vpn = mock<VpnController>()
            val applier = StatsListApplier(newSettings(fake), domainList, vpn)

            val token = applier.apply(emptyList())

            assertEquals("||keep.com^", fake.adRules)
            assertEquals(StatsListOps.State("||keep.com^", emptySet(), emptySet()), token)
            verify(vpn, never()).reloadAdBlockRules()
        }
}
