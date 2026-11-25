package cn.srv0.sshinjector.domain.usecase

/** 删除服务器后的收尾决策 (纯逻辑, 单测覆盖)。 */
internal data class ServerDeletionPlan(
    /** 被删的是默认服务器时, 要提升的下一台; null = 不需要提升。 */
    val nextActiveId: Long?,
    /** 被删的正是 `lastServerId` 时必须清掉, 否则开机自启会连一个不存在的 id。 */
    val clearLastServerId: Boolean,
)

internal fun serverDeletionPlan(
    wasActive: Boolean,
    remainingIds: List<Long>,
    lastServerId: Long?,
    deletedId: Long,
): ServerDeletionPlan =
    ServerDeletionPlan(
        nextActiveId = if (wasActive) remainingIds.firstOrNull() else null,
        clearLastServerId = lastServerId == deletedId,
    )
