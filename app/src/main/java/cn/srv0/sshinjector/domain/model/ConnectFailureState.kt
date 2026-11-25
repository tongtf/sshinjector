package cn.srv0.sshinjector.domain.model

/**
 * 建连**早期**失败 (服务器不存在 / establish TUN 失败 / 配置读取失败) 的终态。
 *
 * 这些失败发生在 `VpnController.connect()` 之前: 此时 `isRunning` 仍为 false,
 * `disconnectInternal` 会跳过 `vpnController.disconnect()`, 而 `reportHealth` 只改
 * verified/failedStep —— 不补这一帧的话 status 会永久停在 Connecting, UI 一直转圈。
 *
 * 纯函数便于单测; 调用点必须在资源清理**之后** (Failed 会触发失败观察者)。
 */
internal fun connectFailureState(
    base: VpnState,
    step: HealthStep?,
): VpnState =
    base.copy(
        status = VpnState.VpnStatus.Failed,
        verified = false,
        failedStep = step,
        connectStage = null,
    )
