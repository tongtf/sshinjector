package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.HealthStep

/** 一个健康窗口内的分阶段计数快照 (累计值; 评估器自行做窗口增量)。 */
data class StageSignals(
    val packetLoopActive: Boolean,
    val dnsQueries: Long,
    val tunWritesOk: Long,
    val tunWritesFailed: Long,
    val dnsDelivered: Long,
    val dnsErrors: Long,
    val forwardAttempts: Long,
    val forwardEstablished: Long,
)

/**
 * 分阶段健康评估 (纯逻辑, 可单测): 端到端探测走 loopback + 域名型 CONNECT, 探不到
 * TUN 写回 / DNS 回程 / 本地 SOCKS 转发三段 —— 这里用 [StageCounters] 的窗口增量补盲区。
 *
 * 节奏与 [HealthTracker] 一致: 连续 [failThreshold] 个失败窗口才降级 (防单窗口抖动误报),
 * 一个成功窗口即恢复; 无信号窗口 (空闲, 没有查询/连接) 保持原判, 不误恢复。
 * 多段同时降级时取链路最早者 (TUN → DNS → FORWARD)。
 */
class StageHealthEvaluator(
    private val failThreshold: Int = FAIL_THRESHOLD,
) {
    private enum class Verdict { OK, FAIL }

    private var prev: StageSignals? = null
    private val consecutiveFailures = mutableMapOf<HealthStep, Int>()
    private val degraded = mutableSetOf<HealthStep>()

    /**
     * 评估一个窗口; 返回当前应上报的分阶段故障步骤, null = 分阶段健康/无信号。
     * 首次调用仅作基线 (无增量可比), 永不降级。
     */
    @Synchronized
    fun evaluate(cur: StageSignals): HealthStep? {
        val before = prev
        prev = cur
        if (before != null) {
            apply(HealthStep.TUN, tunVerdict(cur, before))
            apply(HealthStep.DNS, dnsVerdict(cur, before))
            apply(HealthStep.FORWARD, forwardVerdict(cur, before))
        }
        return STEP_ORDER.firstOrNull { it in degraded }
    }

    @Synchronized
    fun reset() {
        prev = null
        consecutiveFailures.clear()
        degraded.clear()
    }

    private fun apply(
        step: HealthStep,
        verdict: Verdict?,
    ) {
        when (verdict) {
            Verdict.FAIL -> {
                val count = (consecutiveFailures[step] ?: 0) + 1
                consecutiveFailures[step] = count
                if (count >= failThreshold) degraded.add(step)
            }
            Verdict.OK -> {
                consecutiveFailures.remove(step)
                degraded.remove(step)
            }
            null -> Unit // 空闲窗口: 保持原判
        }
    }

    private fun tunVerdict(
        cur: StageSignals,
        before: StageSignals,
    ): Verdict? {
        if (!cur.packetLoopActive) return Verdict.FAIL
        val okDelta = cur.tunWritesOk - before.tunWritesOk
        val failDelta = cur.tunWritesFailed - before.tunWritesFailed
        return when {
            failDelta > 0 && okDelta == 0L -> Verdict.FAIL // 本窗口只失败不成功 = 输出流写死
            okDelta > 0L -> Verdict.OK
            else -> null
        }
    }

    private fun dnsVerdict(
        cur: StageSignals,
        before: StageSignals,
    ): Verdict? {
        val queries = cur.dnsQueries - before.dnsQueries
        val delivered = cur.dnsDelivered - before.dnsDelivered
        val errors = cur.dnsErrors - before.dnsErrors
        return when {
            // 有查询却零有效应答: 完全没回包, 或回的全是错误应答 (上游解析全挂)
            queries >= MIN_DNS_QUERIES && delivered == errors -> Verdict.FAIL
            delivered > errors -> Verdict.OK
            else -> null
        }
    }

    private fun forwardVerdict(
        cur: StageSignals,
        before: StageSignals,
    ): Verdict? {
        val attempts = cur.forwardAttempts - before.forwardAttempts
        val established = cur.forwardEstablished - before.forwardEstablished
        return when {
            attempts >= MIN_FORWARD_ATTEMPTS && established == 0L -> Verdict.FAIL
            established > 0L -> Verdict.OK
            else -> null
        }
    }

    companion object {
        const val FAIL_THRESHOLD = 2

        /** DNS 归因所需的最少查询数 (1 个应用解析 1 个域名不足以说明链路坏了)。 */
        const val MIN_DNS_QUERIES = 3

        /** 转发归因所需的最少新连接数。 */
        const val MIN_FORWARD_ATTEMPTS = 2

        /** 同窗多段失败时的取舍顺序: 越靠前越接近链路源头。 */
        private val STEP_ORDER = listOf(HealthStep.TUN, HealthStep.DNS, HealthStep.FORWARD)
    }
}
