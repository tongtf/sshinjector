package cn.srv0.sshinjector.domain.obs

import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 失败归因计数 (无 adb 现场: 导出的日志里"哪类故障占主导"全靠它)。
 *
 * 锁三件事: 只有 WARNING/ERROR 计; 键必须归一到静态前缀 (否则每个域名各成一键, 汇总废掉);
 * 键满后**忽略新键**而不是清表 (清表 = 抹掉已归因的故障)。
 */
class AppMetricsTest {
    @Before
    fun setUp() = AppMetrics.resetSession()

    @After
    fun tearDown() = AppMetrics.resetSession()

    @Test
    fun `only warnings and errors count`() {
        AppMetrics.record(LogLevel.INFO, "隧道通道就绪 ·a.com")
        AppMetrics.record(LogLevel.SUCCESS, "VPN 连接成功")
        AppMetrics.record(LogLevel.DEBUG, "每包调试")
        assertTrue(AppMetrics.summary().isEmpty())

        AppMetrics.record(LogLevel.WARNING, "上行背压丢段 ·a.com — 1448B")
        AppMetrics.record(LogLevel.ERROR, "组包失败 · ACK — 空指针")
        assertEquals(2, AppMetrics.countsForTest().size)
    }

    @Test
    fun `dynamic details are collapsed into one static key`() {
        AppMetrics.record(LogLevel.WARNING, "上行背压丢段 ·a.example.com — 本次 1448B")
        AppMetrics.record(LogLevel.WARNING, "上行背压丢段 ·b.example.com — 本次 200B")
        AppMetrics.record(LogLevel.WARNING, "上行背压丢段")
        assertEquals(mapOf("上行背压丢段" to 3L), AppMetrics.countsForTest())
    }

    @Test
    fun `normalize keeps an already normalised key unchanged`() {
        assertEquals("回程窗口耗尽", AppMetrics.normalize("回程窗口耗尽"))
        assertEquals("TUN 写入失败", AppMetrics.normalize("TUN 写入失败 · java.io.IOException: reset"))
        assertEquals("组包失败", AppMetrics.normalize("组包失败 · SYN-ACK — 数组越界"))
        assertEquals("", AppMetrics.normalize("   "))
    }

    @Test
    fun `summary is ordered by count and reports the total`() {
        repeat(12) { AppMetrics.record(LogLevel.WARNING, "上行背压丢段") }
        repeat(4) { AppMetrics.record(LogLevel.WARNING, "回程窗口耗尽") }
        AppMetrics.record(LogLevel.ERROR, "TUN 写入失败")
        val summary = AppMetrics.summary()
        assertTrue(summary, summary.startsWith("故障归因 · 本会话 3 类 17 次 — "))
        assertEquals(
            "故障归因 · 本会话 3 类 17 次 — 上行背压丢段 ×12 · 回程窗口耗尽 ×4 · TUN 写入失败 ×1",
            summary,
        )
    }

    @Test
    fun `summary truncates to the top N and says how many were hidden`() {
        repeat(3) { AppMetrics.record(LogLevel.WARNING, "少见的故障") }
        repeat(9) { AppMetrics.record(LogLevel.WARNING, "常见的故障") }
        val summary = AppMetrics.summary(topN = 1)
        assertTrue(summary, summary.contains("常见的故障 ×9"))
        assertFalse("超出 topN 的类别必须显式提示还有多少类", summary.contains("少见的故障"))
        assertTrue(summary, summary.contains("…另 1 类"))
    }

    @Test
    fun `new keys are ignored at the cap so already attributed failures are never lost`() {
        val capped = 64
        repeat(capped) { i -> AppMetrics.record(LogLevel.WARNING, "故障类$i") }
        repeat(5) { AppMetrics.record(LogLevel.WARNING, "故障类0") }
        // 超上限的新键被忽略...
        AppMetrics.record(LogLevel.WARNING, "溢出的新类")
        assertEquals(capped, AppMetrics.countsForTest().size)
        assertFalse(AppMetrics.countsForTest().containsKey("溢出的新类"))
        // ...但已存在的键照常累加 (绝不清表)
        assertEquals(6L, AppMetrics.countsForTest()["故障类0"])
    }

    @Test
    fun `resetSession clears everything for the next session`() {
        AppMetrics.record(LogLevel.ERROR, "组包失败")
        AppMetrics.resetSession()
        assertTrue(AppMetrics.summary().isEmpty())
        assertTrue(AppMetrics.countsForTest().isEmpty())
    }
}
