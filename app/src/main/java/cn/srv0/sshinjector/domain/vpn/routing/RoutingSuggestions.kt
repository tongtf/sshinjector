package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.DomainStatSnapshot

/** 建议成因: 连接失败多, 或下行流量大。 */
enum class RoutingSuggestionReason {
    FAILED,
    TRAFFIC,
}

/** 一条"要不要给它单独指定出口"的建议 (纯展示/一键应用用, 不参与数据面决策)。 */
data class RoutingSuggestion(
    val domain: String,
    val reason: RoutingSuggestionReason,
    val failedConnCount: Long,
    val downBytes: Long,
    val lastSeenAt: Long,
)

/**
 * 从域名统计里挑出值得单独指定出口的候选 (设计 A 节)。
 *
 * 只做**只读筛选**, 不写任何名单: 是否应用由用户点一下决定 (统计页建议卡片)。
 *
 * 过滤顺序即产品语义:
 *  - `canRoute=false` (多出口未启用/没有可用附加出口) → 空, UI 不展示建议;
 *  - IP 字面量条目没有域名可写规则 → 跳过;
 *  - 已有域名规则的 → 跳过 (不要建议用户重复配置);
 *  - 失败 ≥ [MIN_FAILED] 或下行 ≥ [MIN_TRAFFIC_BYTES] 才算候选 (单次失败是抖动, 不是问题)。
 *
 * 排序: 失败次数降序 → 下行字节降序 → 最近访问降序, 最多 [limit] 条。
 */
object RoutingSuggestions {
    /** 失败连接数门槛: 1 次是常态抖动, 2 次起才值得单独出口。 */
    const val MIN_FAILED = 2

    /** 下行流量门槛 (10MB): 走错出口代价最大的一类。 */
    const val MIN_TRAFFIC_BYTES = 10L * 1024 * 1024

    fun pick(
        rows: List<DomainStatSnapshot>,
        hasDomainRule: (String) -> Boolean,
        canRoute: Boolean,
        limit: Int = 5,
    ): List<RoutingSuggestion> {
        if (!canRoute || limit <= 0) return emptyList()
        return rows
            .asSequence()
            .filter { isCandidate(it, hasDomainRule) }
            .map { row ->
                RoutingSuggestion(
                    domain = row.domain,
                    reason = reasonOf(row),
                    failedConnCount = row.failedConnCount,
                    downBytes = row.downBytes,
                    lastSeenAt = row.lastSeenAt,
                )
            }.distinctBy { it.domain }
            .sortedWith(
                compareByDescending<RoutingSuggestion> { it.failedConnCount }
                    .thenByDescending { it.downBytes }
                    .thenByDescending { it.lastSeenAt },
            ).take(limit)
            .toList()
    }

    private fun isCandidate(
        row: DomainStatSnapshot,
        hasDomainRule: (String) -> Boolean,
    ): Boolean {
        if (row.isIpLiteral || row.domain.isBlank()) return false
        if (hasDomainRule(row.domain)) return false
        return row.failedConnCount >= MIN_FAILED || row.downBytes >= MIN_TRAFFIC_BYTES
    }

    /** 失败优先: 同时超两个门槛时按"失败"归因 (那才是用户体感上的问题)。 */
    private fun reasonOf(row: DomainStatSnapshot): RoutingSuggestionReason =
        if (row.failedConnCount >= MIN_FAILED) RoutingSuggestionReason.FAILED else RoutingSuggestionReason.TRAFFIC
}
