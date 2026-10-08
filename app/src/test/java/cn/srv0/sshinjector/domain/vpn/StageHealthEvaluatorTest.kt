package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.HealthStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageHealthEvaluatorTest {
    /** 空闲基线 (全部计数为 0, TUN 通); 各场景用 copy 覆盖需要的字段。 */
    private val idle =
        StageSignals(
            packetLoopActive = true,
            dnsQueries = 0,
            tunWritesOk = 0,
            tunWritesFailed = 0,
            dnsDelivered = 0,
            dnsErrors = 0,
            forwardAttempts = 0,
            forwardEstablished = 0,
        )

    @Test
    fun `first sample is baseline only and never degrades`() {
        val evaluator = StageHealthEvaluator()
        assertNull(
            evaluator.evaluate(
                idle.copy(packetLoopActive = false, dnsQueries = 9, tunWritesFailed = 9, forwardAttempts = 9),
            ),
        )
    }

    @Test
    fun `packet loop down degrades to TUN after threshold and recovers on writes`() {
        val evaluator = StageHealthEvaluator()
        assertNull(evaluator.evaluate(idle.copy(packetLoopActive = false)))
        assertNull(evaluator.evaluate(idle.copy(packetLoopActive = false)))
        assertEquals(HealthStep.TUN, evaluator.evaluate(idle.copy(packetLoopActive = false)))
        assertNull(evaluator.evaluate(idle.copy(tunWritesOk = 12)))
    }

    @Test
    fun `all tun writes failing degrades to TUN and partial success recovers`() {
        val evaluator = StageHealthEvaluator()
        assertNull(evaluator.evaluate(idle))
        assertNull(evaluator.evaluate(idle.copy(tunWritesFailed = 1)))
        assertEquals(HealthStep.TUN, evaluator.evaluate(idle.copy(tunWritesFailed = 3)))
        // 出现成功写 = 输出流没死, 立即恢复
        assertNull(evaluator.evaluate(idle.copy(tunWritesFailed = 4, tunWritesOk = 4)))
    }

    @Test
    fun `dns queries with no answers degrades to DNS then recovers on delivery`() {
        val evaluator = StageHealthEvaluator()
        assertNull(evaluator.evaluate(idle))
        assertNull(evaluator.evaluate(idle.copy(dnsQueries = 5)))
        assertEquals(HealthStep.DNS, evaluator.evaluate(idle.copy(dnsQueries = 10)))
        assertNull(evaluator.evaluate(idle.copy(dnsQueries = 15, dnsDelivered = 6)))
    }

    @Test
    fun `dns answered only with error responses still degrades to DNS`() {
        val evaluator = StageHealthEvaluator()
        assertNull(evaluator.evaluate(idle))
        assertNull(evaluator.evaluate(idle.copy(dnsQueries = 4, dnsDelivered = 4, dnsErrors = 4)))
        assertEquals(
            HealthStep.DNS,
            evaluator.evaluate(idle.copy(dnsQueries = 8, dnsDelivered = 8, dnsErrors = 8)),
        )
    }

    @Test
    fun `too few dns queries is no signal`() {
        val evaluator = StageHealthEvaluator()
        assertNull(evaluator.evaluate(idle))
        assertNull(evaluator.evaluate(idle.copy(dnsQueries = 1)))
        assertNull(evaluator.evaluate(idle.copy(dnsQueries = 2)))
    }

    @Test
    fun `forward attempts without established degrades to FORWARD`() {
        val evaluator = StageHealthEvaluator()
        assertNull(evaluator.evaluate(idle))
        assertNull(evaluator.evaluate(idle.copy(forwardAttempts = 2)))
        assertEquals(
            HealthStep.FORWARD,
            evaluator.evaluate(idle.copy(forwardAttempts = 4)),
        )
        assertNull(evaluator.evaluate(idle.copy(forwardAttempts = 5, forwardEstablished = 1)))
    }

    @Test
    fun `idle window holds previous degradation instead of recovering`() {
        val evaluator = StageHealthEvaluator()
        evaluator.evaluate(idle)
        evaluator.evaluate(idle.copy(dnsQueries = 5))
        assertEquals(HealthStep.DNS, evaluator.evaluate(idle.copy(dnsQueries = 10)))
        // 空闲窗口 (无查询/连接): 保持原判
        assertEquals(HealthStep.DNS, evaluator.evaluate(idle.copy(dnsQueries = 10)))
    }

    @Test
    fun `earliest path step wins when several degrade`() {
        val evaluator = StageHealthEvaluator()
        evaluator.evaluate(idle)
        evaluator.evaluate(
            idle.copy(packetLoopActive = false, dnsQueries = 5, forwardAttempts = 5),
        )
        assertEquals(
            HealthStep.TUN,
            evaluator.evaluate(
                idle.copy(packetLoopActive = false, dnsQueries = 10, forwardAttempts = 10),
            ),
        )
    }

    @Test
    fun `reset clears window and degradation state`() {
        val evaluator = StageHealthEvaluator()
        evaluator.evaluate(idle)
        evaluator.evaluate(idle.copy(dnsQueries = 5))
        evaluator.evaluate(idle.copy(dnsQueries = 10))
        assertEquals(HealthStep.DNS, evaluator.evaluate(idle.copy(dnsQueries = 10)))

        evaluator.reset()
        // reset 后首个样本重新作为基线
        assertNull(evaluator.evaluate(idle.copy(dnsQueries = 10)))
    }
}
