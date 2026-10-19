package cn.srv0.sshinjector.ui.screen.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [AppUidLabels] 的展示纪律: 任何一步拿不到名字都必须**退化**而不是崩 —— 它在 Compose 列表里
 * 逐行调用, 抛一次异常就是整页白屏。
 */
class AppUidLabelsTest {
    @Test
    fun `unresolvable uid degrades to a machine readable fallback`() {
        // mock Context 的 packageManager 为 null → 解析链每一步都可能失败
        val labels = AppUidLabels(mock())
        assertEquals("拿不到名字时保留机器事实", "uid 12345", labels.label(12345))
        assertEquals("哨兵 uid 不解析", "", labels.label(-1))
    }

    @Test
    fun `label is resolved once and then served from the cache`() {
        val pm = mock<PackageManager>()
        whenever(pm.getPackagesForUid(10086)).thenReturn(arrayOf("com.example.app"))
        whenever(pm.getApplicationInfo("com.example.app", 0)).thenReturn(mock<ApplicationInfo>())
        whenever(pm.getApplicationLabel(any())).thenReturn("示例应用")
        val context = mock<Context>()
        whenever(context.packageManager).thenReturn(pm)

        val labels = AppUidLabels(context)

        assertEquals("示例应用", labels.label(10086))
        assertEquals("缓存命中: 第二次刷新不再问 PackageManager", "示例应用", labels.label(10086))
        verify(pm, times(1)).getPackagesForUid(10086)
    }

    @Test
    fun `uid without packages falls back to the numeric label`() {
        val pm = mock<PackageManager>()
        whenever(pm.getPackagesForUid(999)).thenReturn(null)
        val context = mock<Context>()
        whenever(context.packageManager).thenReturn(pm)

        assertEquals("已卸载应用的 uid 不能变成空白", "uid 999", AppUidLabels(context).label(999))
    }
}
