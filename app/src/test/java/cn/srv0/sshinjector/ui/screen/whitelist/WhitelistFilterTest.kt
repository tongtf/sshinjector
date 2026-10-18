package cn.srv0.sshinjector.ui.screen.whitelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单过滤/全选语义 (T-13 / T-19)。
 *
 * 两个都是纯函数 —— 过滤集决定了「全选」对哪些包生效, 一旦语义错了就是
 * 「点全选没反应」或「系统应用永远勾不上」。
 */
class WhitelistFilterTest {
    private val userApp = InstalledApp("com.example.user", "User", isSystem = false)
    private val otherUserApp = InstalledApp("com.example.user2", "User2", isSystem = false)
    private val systemApp = InstalledApp("android", "Android System", isSystem = true)

    private val all = listOf(userApp, otherUserApp, systemApp)

    @Test
    fun `system filter must not be emptied by hideSystemApps default`() {
        // T-19 回归: 默认 hideSystemApps=true 时选「系统应用」曾经恒空
        val filtered =
            filterWhitelistApps(
                allApps = all,
                searchQuery = "",
                hideSystemApps = true,
                filterMode = WHITELIST_FILTER_SYSTEM,
                enabledPackages = emptySet(),
            )
        assertEquals(listOf(systemApp), filtered)
    }

    @Test
    fun `all filter hides system apps when asked`() {
        val filtered =
            filterWhitelistApps(
                allApps = all,
                searchQuery = "",
                hideSystemApps = true,
                filterMode = WHITELIST_FILTER_ALL,
                enabledPackages = emptySet(),
            )
        assertEquals(listOf(userApp, otherUserApp), filtered)
    }

    @Test
    fun `selected filter intersects with enabled packages`() {
        val filtered =
            filterWhitelistApps(
                allApps = all,
                searchQuery = "",
                hideSystemApps = false,
                filterMode = WHITELIST_FILTER_SELECTED,
                enabledPackages = setOf(userApp.packageName),
            )
        assertEquals(listOf(userApp), filtered)
    }

    @Test
    fun `all selected is derived from the filtered set`() {
        assertFalse(areAllSelected(emptyList(), emptySet()))
        assertFalse(areAllSelected(all, emptySet()))
        assertFalse(areAllSelected(all, setOf(userApp.packageName)))
        assertTrue(areAllSelected(all, all.map { it.packageName }.toSet()))
        // 过滤集之外的已选项不影响判定
        assertTrue(areAllSelected(listOf(userApp), setOf(userApp.packageName, systemApp.packageName)))
    }
}
