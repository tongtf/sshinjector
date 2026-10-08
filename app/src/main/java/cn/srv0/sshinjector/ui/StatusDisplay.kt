package cn.srv0.sshinjector.ui

import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.model.VpnState

/**
 * 状态展示文案的唯一渲染点 (状态卡 + 通知栏共用): Connected 也分「已验证可用/验证中/异常·步骤」;
 * 连接失败带步骤归因。纯函数, 文案经 [resolve] (通常为 context::getString) 走字符串资源, 不硬编码。
 */
object StatusDisplay {
    fun build(
        state: VpnState,
        resolve: (Int) -> String,
    ): String {
        val step = state.failedStep
        val stepText = step?.let { resolve(it.labelRes) }
        return when (state.status) {
            VpnState.VpnStatus.Connected ->
                when {
                    stepText != null -> resolve(R.string.status_degraded).format(stepText)
                    !state.verified -> resolve(R.string.status_verifying)
                    else -> resolve(R.string.status_connected)
                }
            VpnState.VpnStatus.Connecting ->
                when {
                    // 连接期失败 (如 TUN 建立失败) 归因补写时 status 仍停留 Connecting, 必须优先显示
                    stepText != null -> resolve(R.string.status_failed_step).format(stepText)
                    else -> state.connectStage?.let { resolve(it.labelRes) } ?: resolve(R.string.status_connecting)
                }
            VpnState.VpnStatus.Disconnecting -> resolve(R.string.status_disconnecting)
            VpnState.VpnStatus.Failed ->
                if (stepText != null) {
                    resolve(R.string.status_failed_step).format(stepText)
                } else {
                    resolve(R.string.status_failed)
                }
            VpnState.VpnStatus.Disconnected ->
                if (stepText != null) {
                    resolve(R.string.status_failed_step).format(stepText)
                } else {
                    resolve(R.string.status_disconnected)
                }
        }
    }
}
