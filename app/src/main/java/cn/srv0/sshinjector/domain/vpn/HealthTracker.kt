package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.HealthStep

/**
 * 端到端探测的降级/恢复计数 (纯逻辑, 可单测)。
 *
 * 节奏约定 (spec D2): 连续失败 [failThreshold] 次才降级 (防闪断误报), 1 次成功即恢复。
 * SSH 层已知不健康 (immediate=true) 时首败即降级 —— 那是直接事实, 不必等探测凑次数。
 */
class HealthTracker(
    private val failThreshold: Int = FAIL_THRESHOLD,
) {
    var verified: Boolean = false
        private set

    var failedStep: HealthStep? = null
        private set

    private var consecutiveFailures = 0

    // 并发保护: 周期循环与事件触发探测可能并发回调 (K6), 计数与状态必须原子更新
    @Synchronized
    fun onSuccess() {
        consecutiveFailures = 0
        verified = true
        failedStep = null
    }

    @Synchronized
    fun onFailure(
        step: HealthStep,
        immediate: Boolean = false,
    ) {
        consecutiveFailures++
        if (immediate || consecutiveFailures >= failThreshold) {
            verified = false
            failedStep = step
        }
    }

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        verified = false
        failedStep = null
    }

    companion object {
        const val FAIL_THRESHOLD = 2
    }
}
