package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.exit.ExitId

/** 简单模式下的应用例外: 包名 → 出口。包名精确匹配, 不含端口。 */
data class SimpleAppRoute(
    val packageName: String,
    val exitId: String,
)

/** 简单模式下的域名例外: 后缀 → 出口 (`*.` 已剥掉, 与 [ExitRule.Domain] 同义)。 */
data class SimpleDomainRoute(
    val suffix: String,
    val exitId: String,
)

/**
 * 简单路由模型: 一个默认出口 + 两个例外列表 (应用 / 域名)。
 *
 * 它只是 `multi_exit_rules` 文本的**视图**, 不是第二份权威数据 —— 落盘仍然只有文本
 * (见 [SimpleRoutingPolicyCodec.toText])。这样数据面、匹配语义、存储格式逐字节不变。
 *
 * 列表顺序即界面顺序; `toText` 会按优先级规则重新排版 (应用保序, 域名最长后缀优先,
 * `default` 恒在最后)。`upsert*` 一律就地替换 (位置不变), 避免用户改一个出口后整行跳位。
 */
data class SimpleRoutingPolicy(
    val defaultExit: String = ExitId.DEFAULT,
    val apps: List<SimpleAppRoute> = emptyList(),
    val domains: List<SimpleDomainRoute> = emptyList(),
) {
    /** 新增/更新一条应用例外 (同包名只留一条, 就地替换)。非法输入返回自身。 */
    fun upsertApp(
        packageName: String,
        exitId: String,
    ): SimpleRoutingPolicy {
        val pkg = packageName.trim()
        val exit = normalizeExitRef(exitId) ?: return this
        if (!SimpleRoutingPolicyCodec.isValidPackage(pkg)) return this
        val route = SimpleAppRoute(pkg, exit)
        val existing = apps.indexOfFirst { it.packageName == pkg }
        val next = if (existing < 0) apps + route else apps.toMutableList().also { it[existing] = route }
        return copy(apps = next)
    }

    /** 删除一条应用例外 (不存在时返回自身)。 */
    fun removeApp(packageName: String): SimpleRoutingPolicy {
        val pkg = packageName.trim()
        val next = apps.filterNot { it.packageName == pkg }
        return if (next.size == apps.size) this else copy(apps = next)
    }

    /** 新增/更新域名例外 (同后缀只留一条, 就地替换; `*.example.com` 等价 `example.com`)。 */
    fun upsertDomain(
        suffix: String,
        exitId: String,
    ): SimpleRoutingPolicy {
        val domain = SimpleRoutingPolicyCodec.normalizeDomain(suffix) ?: return this
        val exit = normalizeExitRef(exitId) ?: return this
        val route = SimpleDomainRoute(domain, exit)
        val existing = domains.indexOfFirst { it.suffix == domain }
        val next = if (existing < 0) domains + route else domains.toMutableList().also { it[existing] = route }
        return copy(domains = next)
    }

    /** 删除一条域名例外 (不存在时返回自身)。 */
    fun removeDomain(suffix: String): SimpleRoutingPolicy {
        val domain = SimpleRoutingPolicyCodec.normalizeDomain(suffix) ?: return this
        val next = domains.filterNot { it.suffix == domain }
        return if (next.size == domains.size) this else copy(domains = next)
    }

    /** 改默认出口; 非法出口名返回自身。 */
    fun withDefault(exitId: String): SimpleRoutingPolicy {
        val exit = normalizeExitRef(exitId) ?: return this
        return copy(defaultExit = exit)
    }

    private fun normalizeExitRef(raw: String): String? {
        val exit = raw.trim().lowercase()
        return if (ExitId.isValidReference(exit)) exit else null
    }
}

/** 简单模式不支持的形态 (UI 据此切高级模式并给出说明)。 */
enum class SimplePolicyUnsupported {
    /** 网络/IP 规则或带端口的应用/域名规则 —— 简单模式无法表达。 */
    ADVANCED_RULE,

    /** 文本本身有语法错 (含 default 不在最后、超行数)。 */
    INVALID_RULE,

    /** 预留: 规则条数超过简单模式的展示上限。 */
    TOO_MANY_RULES,
}

/** [SimpleRoutingPolicyCodec.parse] 的结果。 */
sealed interface SimplePolicyResult {
    data class Ok(
        val policy: SimpleRoutingPolicy,
    ) : SimplePolicyResult

    data class Unsupported(
        val reason: SimplePolicyUnsupported,
    ) : SimplePolicyResult
}

/**
 * 简单模型 ↔ 规则文本的编解码 (纯 JVM, 无 Android 依赖)。
 *
 * 解析复用 [ExitRuleParser] —— 只有一份语法, 简单模式不可能与高级模式理解不一致。
 * 任何"简单模式表达不了"的合法规则都归 [SimplePolicyUnsupported.ADVANCED_RULE], 绝不静默丢弃:
 * 静默丢弃会让用户切一次模式就永久丢配置 (规则文本是唯一权威)。
 */
object SimpleRoutingPolicyCodec {
    /** 这些 issue 意味着文本有语法错, 简单模式不能假装读懂了。 */
    private val FATAL_ISSUES =
        setOf(
            RuleIssue.INVALID_TARGET,
            RuleIssue.INVALID_PORT,
            RuleIssue.INVALID_CIDR,
            RuleIssue.INVALID_PACKAGE,
            RuleIssue.INVALID_EXIT,
            RuleIssue.DEFAULT_ROUTE_REJECTED,
            RuleIssue.DEFAULT_NOT_LAST,
            RuleIssue.TOO_MANY_RULES,
        )

    private val ADVANCED = SimplePolicyResult.Unsupported(SimplePolicyUnsupported.ADVANCED_RULE)

    /** 解析规则文本; 无 `default` 行时默认出口为 [ExitId.DEFAULT]。 */
    fun parse(text: String): SimplePolicyResult {
        val parsed = ExitRuleParser.parse(text)
        if (parsed.issues.any { it.issue in FATAL_ISSUES }) {
            return SimplePolicyResult.Unsupported(SimplePolicyUnsupported.INVALID_RULE)
        }
        val apps = ArrayList<SimpleAppRoute>()
        val domains = ArrayList<SimpleDomainRoute>()
        var defaultExit = ExitId.DEFAULT
        for (rule in parsed.rules) {
            when (rule) {
                is ExitRule.App -> {
                    if (rule.port != null) return ADVANCED
                    apps += SimpleAppRoute(rule.pkg, rule.exitId)
                }
                is ExitRule.Domain -> {
                    if (rule.port != null) return ADVANCED
                    domains += SimpleDomainRoute(rule.suffix, rule.exitId)
                }
                is ExitRule.Network -> return ADVANCED
                is ExitRule.Default -> defaultExit = rule.exitId
            }
        }
        return SimplePolicyResult.Ok(SimpleRoutingPolicy(defaultExit, apps, domains))
    }

    /**
     * 序列化为规则文本。
     *
     * 排版规则即优先级: 应用行保持列表顺序 (最先匹配), 域名行按后缀长度降序
     * (最长后缀最具体, 同长按字母保证稳定), `default` 永远最后 (否则解析时整行被丢)。
     */
    fun toText(policy: SimpleRoutingPolicy): String {
        val lines = ArrayList<String>(policy.apps.size + policy.domains.size + 1)
        for (app in policy.apps) lines += "app:${app.packageName} -> ${app.exitId}"
        val sortedDomains =
            policy.domains.sortedWith(
                compareByDescending<SimpleDomainRoute> { it.suffix.length }.thenBy { it.suffix },
            )
        for (domain in sortedDomains) lines += "${domain.suffix} -> ${domain.exitId}"
        lines += "default -> ${policy.defaultExit}"
        return lines.joinToString("\n")
    }

    /** 规范化域名: trim / 小写 / 去 `*.` 与末尾点; 非法 (空、IP、非法标签) 返回 null。 */
    fun normalizeDomain(raw: String): String? {
        val trimmed =
            raw
                .trim()
                .removePrefix("*.")
                .trimEnd('.')
                .lowercase()
        if (trimmed.isEmpty()) return null
        return if (ExitRuleParser.isValidDomain(trimmed)) trimmed else null
    }

    /** 包名是否合法 (与规则解析器同一判据, 允许首尾空白)。 */
    fun isValidPackage(raw: String): Boolean = ExitRuleParser.isValidPackage(raw.trim())
}
