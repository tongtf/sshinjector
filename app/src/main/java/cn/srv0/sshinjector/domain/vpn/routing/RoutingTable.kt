package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.exit.ExitId

/**
 * 已解析的路由表: **有序 first-match-wins** (设计 §6.3)。
 *
 * 不可变快照: 文本变更时解析一次, 数据面只读 (以 `@Volatile` 引用交换, 不阻塞 SYN 路径)。
 *
 * 与出口集合的关系: 出口在 Room、规则在 DataStore, **没有事务**。所以规则里引用了不存在的出口时,
 * 这一行**保留在表里** (改名/迁移/导入时序不一致时不丢用户配置), 只在匹配时跳过 ——
 * [withKnownExits] 在出口增删/改名后重建快照即可。
 */
class RoutingTable private constructor(
    val rules: List<ExitRule>,
    val issues: List<RuleIssueInfo>,
    private val knownExits: Set<String>,
) {
    val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * 是否存在**生效的** `app:` 规则。
     *
     * 没有就不必等 uid —— 50ms 预算只有在"有应用规则能命中"时才值得付 (规则里删掉所有 `app:`
     * 行 = 应用分流关掉, 数据面不该为此变慢)。未知出口的 app 规则是惰性的, 不算。
     */
    val hasAppRule: Boolean get() = rules.any { it is ExitRule.App && isResolved(it) }

    /** 引用了不存在出口的规则 (UI 黄标 + 汇总 WARNING 用)。 */
    val unresolvedRules: List<ExitRule> get() = rules.filterNot { isResolved(it) }

    fun isResolved(rule: ExitRule): Boolean = rule.exitId == ExitId.DEFAULT || rule.exitId in knownExits

    /**
     * 选出目标出口。
     * @return 出口 id; **null = 未命中任何规则 → 走 default** (调用方把 null 当 `ExitId.DEFAULT`)
     */
    fun select(t: RouteTarget): String? {
        for (rule in rules) {
            // 未知出口不是"命中 default": 用户本意是走别的出口, 这一行暂时等于不存在
            if (!isResolved(rule)) continue
            if (rule.matches(t)) return rule.exitId
        }
        return null
    }

    /**
     * 域名是否有出口规则 (D-5: 显式域名规则 = 显式要求走隧道)。
     *
     * **忽略端口、忽略 app 规则**: `example.com:443 -> hk` 也必须能把 `example.com` 提升进隧道,
     * 否则 DNS 阶段会直连解析、拿到真实 IP, 而真实 IP 在分流模式下根本不进 TUN —— 规则永远匹配不上。
     */
    fun hasDomainRule(domain: String): Boolean = domainExitFor(domain) != null

    /**
     * 命中该域名的出口 id (`default` = 主出口); null = 没有生效的域名规则命中。
     *
     * D-5 隧道提升用它拿到**具体出口**: 只有该出口真的可用时才值得把域名提升进隧道,
     * 否则提升只会把本应直连的流量绕进主出口。
     */
    fun domainExitFor(domain: String): String? {
        val d = domain.trimEnd('.').lowercase()
        if (d.isEmpty()) return null
        return rules
            .firstOrNull { it is ExitRule.Domain && isResolved(it) && domainSuffixMatch(d, it.suffix) }
            ?.exitId
    }

    /** 出口集合变化 (增删/改名/启停) 后重建快照; 规则与解析问题原样保留。 */
    fun withKnownExits(exits: Set<String>): RoutingTable = RoutingTable(rules, issues, exits)

    companion object {
        val EMPTY = RoutingTable(emptyList(), emptyList(), emptySet())

        /**
         * 解析文本并绑定当前出口集合。
         * @param knownExits 当前**可用**出口名 (含禁用过滤后的集合); `default` 恒可用, 不必传
         */
        fun fromText(
            text: String,
            knownExits: Set<String>,
        ): RoutingTable {
            val parsed = ExitRuleParser.parse(text)
            return RoutingTable(parsed.rules, parsed.issues, knownExits)
        }
    }
}
