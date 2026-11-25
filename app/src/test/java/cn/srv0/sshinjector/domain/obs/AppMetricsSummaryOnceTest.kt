package cn.srv0.sshinjector.domain.obs

import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 建连早期失败路径没有 disconnect(), 汇总必须能单独输出且只输出一次。 */
class AppMetricsSummaryOnceTest {
    @After
    fun tearDown() = AppMetrics.resetSession()

    @Test
    fun `summary once returns text only on the first call`() {
        AppMetrics.resetSession()
        AppMetrics.record(LogLevel.ERROR, "连接失败")
        assertTrue(AppMetrics.summaryOnce().isNotEmpty())
        assertEquals("", AppMetrics.summaryOnce())
    }

    @Test
    fun `resetSession allows a new session to emit again`() {
        AppMetrics.resetSession()
        AppMetrics.record(LogLevel.WARNING, "上行背压丢段")
        assertTrue(AppMetrics.summaryOnce().isNotEmpty())
        AppMetrics.resetSession()
        AppMetrics.record(LogLevel.WARNING, "上行背压丢段")
        assertTrue(AppMetrics.summaryOnce().isNotEmpty())
    }
}
