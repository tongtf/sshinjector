package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.HealthStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级/恢复节奏 (spec D2): 连续 2 败才降级, 1 成即恢复, SSH 层不健康首败即降级。
 */
class HealthTrackerTest {
    private val tracker = HealthTracker()

    @Test
    fun `starts unverified without failure attribution`() {
        assertFalse(tracker.verified)
        assertNull(tracker.failedStep)
    }

    @Test
    fun `reset clears verification and attribution`() {
        tracker.onSuccess()
        tracker.onFailure(HealthStep.REMOTE, immediate = true)
        tracker.reset()
        assertFalse(tracker.verified)
        assertNull(tracker.failedStep)
    }

    @Test
    fun `single failure does not degrade (anti-flap threshold)`() {
        tracker.onFailure(HealthStep.TUNNEL)
        assertFalse(tracker.verified)
        assertNull("first failure keeps 验证中 state", tracker.failedStep)
    }

    @Test
    fun `two consecutive failures degrade with the failing step`() {
        tracker.onFailure(HealthStep.TUNNEL)
        tracker.onFailure(HealthStep.TUNNEL)
        assertFalse(tracker.verified)
        assertEquals(HealthStep.TUNNEL, tracker.failedStep)
    }

    @Test
    fun `success recovers immediately and resets failure counter`() {
        tracker.onFailure(HealthStep.TUNNEL)
        tracker.onFailure(HealthStep.TUNNEL)
        tracker.onSuccess()
        assertTrue(tracker.verified)
        assertNull(tracker.failedStep)

        // 计数已清零: 再败一次不降级
        tracker.onFailure(HealthStep.SSH)
        assertNull(tracker.failedStep)
    }

    @Test
    fun `immediate failure degrades on first败 because ssh pool unhealthy is direct fact`() {
        tracker.onFailure(HealthStep.SSH, immediate = true)
        assertFalse(tracker.verified)
        assertEquals(HealthStep.SSH, tracker.failedStep)
    }

    @Test
    fun `degraded step reflects the latest failing step`() {
        tracker.onFailure(HealthStep.TUNNEL)
        tracker.onFailure(HealthStep.TUNNEL)
        tracker.onSuccess()
        tracker.onFailure(HealthStep.DNS)
        tracker.onFailure(HealthStep.DNS)
        assertEquals(HealthStep.DNS, tracker.failedStep)
    }
}
