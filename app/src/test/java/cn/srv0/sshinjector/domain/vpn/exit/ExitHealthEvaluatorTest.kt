package cn.srv0.sshinjector.domain.vpn.exit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出口健康判定 (设计 §9.1 / §9.3)。
 *
 * 锁住两条容易写错的语义:
 * 1. **降级立即可见** (一次巡检失健康就 Degraded) —— 否则死出口还会继续接新连接;
 * 2. **恢复要连续 2 次** —— 单次抖动就回来会让新连接在两条线路之间反复跳。
 */
class ExitHealthEvaluatorTest {
    @Test
    fun `a healthy exit stays ready`() {
        val d = ExitHealthEvaluator.decide(ExitHealth.Ready, healthy = true, lastProbeOkAt = 0L, now = 1000L)

        assertEquals(ExitHealth.Ready, d.health)
        assertEquals(ExitHealthChange.NONE, d.change)
    }

    @Test
    fun `losing health degrades immediately`() {
        val d = ExitHealthEvaluator.decide(ExitHealth.Ready, healthy = false, lastProbeOkAt = 0L, now = 1000L)

        assertEquals(ExitHealth.Degraded, d.health)
        assertEquals(ExitHealthChange.DEGRADED, d.change)
    }

    @Test
    fun `first healthy probe only records the baseline`() {
        val d = ExitHealthEvaluator.decide(ExitHealth.Degraded, healthy = true, lastProbeOkAt = 0L, now = 1000L)

        assertEquals("一次成功还不够, 保持降级", ExitHealth.Degraded, d.health)
        assertEquals(ExitHealthChange.NONE, d.change)
        assertEquals(1000L, d.lastProbeOkAt)
    }

    @Test
    fun `second consecutive healthy probe recovers`() {
        val d = ExitHealthEvaluator.decide(ExitHealth.Degraded, healthy = true, lastProbeOkAt = 1000L, now = 16_000L)

        assertEquals(ExitHealth.Ready, d.health)
        assertEquals(ExitHealthChange.RECOVERED, d.change)
    }

    @Test
    fun `still unhealthy clears the recovery baseline`() {
        // 恢复过程中又失败一次 → 基线清零, 下次成功重新从"第一次"开始数
        val d = ExitHealthEvaluator.decide(ExitHealth.Degraded, healthy = false, lastProbeOkAt = 1000L, now = 16_000L)

        assertEquals(ExitHealth.Degraded, d.health)
        assertEquals(0L, d.lastProbeOkAt)
        assertEquals(ExitHealthChange.NONE, d.change)
    }

    @Test
    fun `terminal states are not resurrected by the probe`() {
        listOf(ExitHealth.Starting, ExitHealth.Failed, ExitHealth.Stopped).forEach { state ->
            val d = ExitHealthEvaluator.decide(state, healthy = true, lastProbeOkAt = 0L, now = 1000L)
            assertEquals("$state 由启动/用户流程驱动, 巡检不得改写", state, d.health)
            assertEquals(ExitHealthChange.NONE, d.change)
        }
    }
}

/** 只有 Ready/Degraded 值得探测; Starting 由启动流程驱动, Failed/Stopped 是终态。 */
class ExitProbePolicyTest {
    @Test
    fun `only ready and degraded exits are probed`() {
        assertTrue(ExitHealthEvaluator.shouldProbe(ExitHealth.Ready))
        assertTrue(ExitHealthEvaluator.shouldProbe(ExitHealth.Degraded))
        assertFalse(ExitHealthEvaluator.shouldProbe(ExitHealth.Starting))
        assertFalse(ExitHealthEvaluator.shouldProbe(ExitHealth.Failed))
        assertFalse(ExitHealthEvaluator.shouldProbe(ExitHealth.Stopped))
    }

    @Test
    fun `failed exits are never revived by a healthy probe`() {
        val decision = ExitHealthEvaluator.decide(ExitHealth.Failed, healthy = true, lastProbeOkAt = 0L, now = 1L)
        assertEquals(ExitHealth.Failed, decision.health)
        assertEquals(ExitHealthChange.NONE, decision.change)
    }
}
