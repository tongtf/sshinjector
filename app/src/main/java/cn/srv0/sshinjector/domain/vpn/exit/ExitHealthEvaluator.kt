package cn.srv0.sshinjector.domain.vpn.exit

/** 一次巡检带来的状态变化 (用于日志分级: 降级 WARNING / 恢复 SUCCESS / 无变化不打日志)。 */
enum class ExitHealthChange {
    NONE,
    DEGRADED,
    RECOVERED,
}

data class ExitHealthDecision(
    val health: ExitHealth,
    /** 下一次巡检要带回来的基线: Degraded 状态下第一次成功记 `now`, 第二次才恢复。 */
    val lastProbeOkAt: Long,
    val change: ExitHealthChange,
)

/**
 * 附加出口健康判定 (设计 §9.1 / §9.3), 纯逻辑、时钟由调用方注入 → 确定性单测。
 *
 * 判据只有一条: 插件现在还能不能接新连接 (`isHealthy` = 本地监听在 + SSH 池里有活口)。
 * 语义:
 * - `Ready → Degraded`: 立即可见 (新连接回退主出口);
 * - `Degraded → Ready`: **连续 2 次**巡检健康才恢复 —— 单次抖动就来回切换会让"降级"变成噪声,
 *   而且每次切换都会改变新连接的出口 (用户看到的行为在两条线路之间跳);
 * - 其它状态 (`Starting`/`Failed`/`Stopped`) 一律不动: 它们由启动流程/用户操作驱动, 不是巡检的对象。
 */
object ExitHealthEvaluator {
    /**
     * 是否值得对这条出口做端到端探测。
     * `Starting` 由启动流程驱动; `Failed` 是本次会话终态 (不复活); `Stopped` 已移除。
     */
    fun shouldProbe(current: ExitHealth): Boolean = current == ExitHealth.Ready || current == ExitHealth.Degraded

    fun decide(
        current: ExitHealth,
        healthy: Boolean,
        lastProbeOkAt: Long,
        now: Long,
    ): ExitHealthDecision =
        when {
            !healthy && current == ExitHealth.Ready ->
                ExitHealthDecision(ExitHealth.Degraded, 0L, ExitHealthChange.DEGRADED)
            !healthy -> ExitHealthDecision(current, 0L, ExitHealthChange.NONE)
            current == ExitHealth.Degraded && lastProbeOkAt == 0L ->
                ExitHealthDecision(ExitHealth.Degraded, now, ExitHealthChange.NONE)
            current == ExitHealth.Degraded ->
                ExitHealthDecision(ExitHealth.Ready, now, ExitHealthChange.RECOVERED)
            else -> ExitHealthDecision(current, lastProbeOkAt, ExitHealthChange.NONE)
        }
}
