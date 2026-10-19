package cn.srv0.sshinjector.domain.vpn

/**
 * 统计名单的**纯变换**: 把一批 [Suggestion] 套用到"广告规则文本 + 代理名单两集合"上。
 *
 * 无 Android / 无 IO / 无锁 → JVM 单测直接验证方向语义、幂等与收敛性;
 * IO 与串行化在 [cn.srv0.sshinjector.domain.usecase.StatsListApplier] 里。
 */
object StatsListOps {
    /**
     * 三个"统计生成"键的快照 —— **它就是撤销 token**: 应用前存一份, 撤销时原样写回,
     * 所以撤销是精确可逆的 (不依赖历史栈, 也不是"反向操作一遍")。
     */
    data class State(
        val adBlockRulesFromStats: String?,
        val domainListAdded: Set<String>,
        val domainListRemoved: Set<String>,
    )

    fun apply(
        state: State,
        items: List<Suggestion>,
    ): State {
        var rules = state.adBlockRulesFromStats.orEmpty()
        val added = state.domainListAdded.toMutableSet()
        val removed = state.domainListRemoved.toMutableSet()
        for (s in items) {
            val d = DomainSuffixSet.normalize(s.domain)
            if (d.isEmpty()) continue
            when (s.target) {
                SuggestionTarget.AD_BLOCK -> rules = applyAdBlock(rules, d, s.direction)
                SuggestionTarget.DOMAIN_LIST -> applyDomainList(added, removed, d, s.direction)
            }
        }
        return State(
            adBlockRulesFromStats = rules.ifBlank { null },
            domainListAdded = added,
            domainListRemoved = removed,
        )
    }

    private fun applyAdBlock(
        rules: String,
        domain: String,
        direction: SuggestionDirection,
    ): String {
        val blockLine = StatsRuleText.blockRule(domain)
        val exceptLine = StatsRuleText.exceptionRule(domain)
        return when (direction) {
            // 加入: 先摘掉可能残留的"统计例外行" —— 例外优先级高于命中, 不摘会出现"点了加入却没反应"
            SuggestionDirection.ADD -> StatsRuleText.addLine(StatsRuleText.removeLine(rules, exceptLine), blockLine)
            // 移出: 统计自己加的就精确删行; 命中来自基础清单/用户手写 → 加例外 (误杀救援), 同样可逆
            SuggestionDirection.REMOVE ->
                if (StatsRuleText.contains(rules, blockLine)) {
                    StatsRuleText.removeLine(rules, blockLine)
                } else {
                    StatsRuleText.addLine(rules, exceptLine)
                }
        }
    }

    private fun applyDomainList(
        added: MutableSet<String>,
        removed: MutableSet<String>,
        domain: String,
        direction: SuggestionDirection,
    ) {
        when (direction) {
            SuggestionDirection.ADD -> {
                added.add(domain)
                removed.remove(domain)
            }

            SuggestionDirection.REMOVE -> {
                removed.add(domain)
                added.remove(domain)
            }
        }
    }
}
