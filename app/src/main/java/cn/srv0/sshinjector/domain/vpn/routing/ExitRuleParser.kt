package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.CidrRoute
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import java.net.Inet6Address
import java.net.InetAddress

/** 解析/校验问题 (设计 附录 A 错误表)。UI 按行号做行内标记, 汇总一条 WARNING。 */
enum class RuleIssue {
    /** 目标不是合法语法。 */
    INVALID_TARGET,

    /** 端口不是 1..65535。 */
    INVALID_PORT,

    /** CIDR 非法。 */
    INVALID_CIDR,

    /** `app:` 后面不是包名。 */
    INVALID_PACKAGE,

    /** 出口名非法 (字符/长度)。**未知**出口不算语法错, 由 [RoutingTable] 标记 unresolved。 */
    INVALID_EXIT,

    /** `default` 不在最后一行 → 丢弃该行。 */
    DEFAULT_NOT_LAST,

    /** 重复 target → 保留首行。 */
    DUPLICATE_TARGET,

    /** `0.0.0.0/0` / `::/0` → 拒绝, 提示改用 `default -> x`。 */
    DEFAULT_ROUTE_REJECTED,

    /** 超过 [ExitRuleParser.MAX_RULES] 行 → 拒绝保存。 */
    TOO_MANY_RULES,
}

data class RuleIssueInfo(
    val lineNo: Int,
    val issue: RuleIssue,
    val detail: String,
)

data class RuleParseResult(
    val rules: List<ExitRule>,
    val issues: List<RuleIssueInfo>,
) {
    /** false = 整体拒绝保存 (目前只有"超行数")。 */
    val ok: Boolean get() = issues.none { it.issue == RuleIssue.TOO_MANY_RULES }
}

/**
 * 规则文本解析器 (设计 §6.2 / 附录 A)。
 *
 * 纯函数、无 Android 依赖: 文本变更时解析一次, 结果以不可变快照交给数据面 (`@Volatile` 交换),
 * 绝不在 packetLoop / SYN 路径上解析。
 *
 * 容错原则 (与 `StatsRuleText` 一致):
 * - **语法错** → 丢弃该行 + 行号上报;
 * - **未知出口** → 保留该行 (不是语法错, 见 [ExitRule] KDoc), 匹配时跳过;
 * - **重复 target** → 保留首行, 不删用户数据;
 * - `default` 不在最后 → 只丢 default 行, 其余照常生效。
 */
object ExitRuleParser {
    /** 上限: 每行都会在**新建连接**时匹配一次 (不是每包), 512 行 ≈ 50µs 上限 (设计 §6.6)。 */
    const val MAX_RULES = 512

    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    private val PACKAGE_REGEX = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    private val DOMAIN_LABEL = Regex("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$")

    fun parse(text: String): RuleParseResult {
        val rules = ArrayList<ExitRule>()
        val issues = ArrayList<RuleIssueInfo>()
        val seenTargets = HashSet<String>()
        var ruleCount = 0
        var overflow = false
        // default 单独收集: 只有"文件中最后一条规则"才生效, 且多条 default 时**最后一条**胜出
        // (逐行 insert 再事后删会把"第一条不在末尾"变成"两条都没了")
        var lastDefault: ExitRule.Default? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            ruleCount++
            if (ruleCount > MAX_RULES) {
                overflow = true
                return@forEachIndexed
            }

            val arrow = line.indexOf("->")
            if (arrow <= 0) {
                issues += RuleIssueInfo(lineNo, RuleIssue.INVALID_TARGET, "缺少 \"-> <出口>\"")
                return@forEachIndexed
            }
            val targetText = line.substring(0, arrow).trim()
            val exitText = line.substring(arrow + 2).trim()
            val exitId = normalizeExit(exitText)
            if (exitId == null) {
                issues += RuleIssueInfo(lineNo, RuleIssue.INVALID_EXIT, "出口名非法 (小写字母/数字/`_`/`-`, ≤32)")
                return@forEachIndexed
            }

            when (val outcome = parseTarget(targetText, exitId, lineNo)) {
                is TargetParse.Bad ->
                    issues += RuleIssueInfo(lineNo, outcome.issue, outcome.detail)
                is TargetParse.Ok -> {
                    val rule = outcome.rule
                    if (rule is ExitRule.Default) {
                        lastDefault = rule
                    } else if (!seenTargets.add(targetKey(rule))) {
                        issues += RuleIssueInfo(lineNo, RuleIssue.DUPLICATE_TARGET, "重复目标, 保留首次出现的行")
                    } else {
                        rules += rule
                    }
                }
            }
        }

        if (overflow) {
            issues += RuleIssueInfo(MAX_RULES + 1, RuleIssue.TOO_MANY_RULES, "超过 $MAX_RULES 行, 拒绝保存")
            return RuleParseResult(emptyList(), issues)
        }

        // default 必须是最后一行: 它之后的规则永远不可达, 所以只保留"最后一条 default",
        // 且仅当它确实排在所有保留规则之后; 否则丢弃并给出明确提示。
        val default = lastDefault
        if (default != null) {
            val lastRuleLine = rules.lastOrNull()?.lineNo ?: 0
            if (default.lineNo > lastRuleLine) {
                rules += default
            } else {
                issues +=
                    RuleIssueInfo(default.lineNo, RuleIssue.DEFAULT_NOT_LAST, "default 必须是最后一行, 已忽略")
            }
        }

        return RuleParseResult(rules, issues)
    }

    private fun normalizeExit(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        if (trimmed == ExitId.DEFAULT) return ExitId.DEFAULT
        return ExitId.normalize(trimmed)
    }

    private sealed interface TargetParse {
        data class Ok(
            val rule: ExitRule,
        ) : TargetParse

        data class Bad(
            val issue: RuleIssue,
            val detail: String,
        ) : TargetParse
    }

    /**
     * 目标按形态分派。顺序即语法优先级:
     * `default` → `app:` → `[...]` (方括号 IPv6, 带端口或前缀) → 含 `/` (CIDR) → 其余 (IP / 域名)。
     */
    private fun parseTarget(
        target: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        if (target == "default") return TargetParse.Ok(ExitRule.Default(exitId, lineNo))
        if (target.startsWith("app:")) return parseAppTarget(target, exitId, lineNo)
        if (target.startsWith("[")) return parseBracketedTarget(target, exitId, lineNo)
        if (target.contains('/')) return parseCidrTarget(target, exitId, lineNo)
        return parseHostTarget(target, exitId, lineNo)
    }

    private fun parseAppTarget(
        target: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        val body = target.removePrefix("app:")
        val split = splitPort(body) ?: return TargetParse.Bad(RuleIssue.INVALID_PORT, "端口需为 1-65535")
        if (!PACKAGE_REGEX.matches(split.first)) {
            return TargetParse.Bad(RuleIssue.INVALID_PACKAGE, "只能填包名 (如 com.example.app), 不支持 uid/通配")
        }
        return TargetParse.Ok(ExitRule.App(exitId, split.first, split.second, lineNo))
    }

    private fun parseBracketedTarget(
        target: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        val close = target.indexOf(']')
        if (close < 0) return TargetParse.Bad(RuleIssue.INVALID_TARGET, "IPv6 缺少 \"]\"")
        val ip = parseIpLiteral(target.substring(1, close))
        if (ip !is Inet6Address) return TargetParse.Bad(RuleIssue.INVALID_TARGET, "方括号里必须是 IPv6 地址")
        val rest = target.substring(close + 1)
        // 设计文档示例写作 `[2001:db8::]/32`: 方括号后既可以是前缀长度, 也可以是端口
        return if (rest.startsWith("/")) {
            parseBracketedPrefix(ip, rest, exitId, lineNo)
        } else {
            parseBracketedPort(ip, rest, exitId, lineNo)
        }
    }

    private fun parseBracketedPrefix(
        ip: Inet6Address,
        rest: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        val prefix = rest.substring(1).toIntOrNull()
        if (prefix == null) return TargetParse.Bad(RuleIssue.INVALID_CIDR, "前缀长度非法")
        if (prefix == 0) return TargetParse.Bad(RuleIssue.DEFAULT_ROUTE_REJECTED, "请改用 default -> <出口>")
        if (prefix !in 1..128) return TargetParse.Bad(RuleIssue.INVALID_CIDR, "IPv6 前缀需为 1-128")
        return TargetParse.Ok(ExitRule.Network(exitId, CidrRoute(ip, prefix), null, lineNo))
    }

    private fun parseBracketedPort(
        ip: Inet6Address,
        rest: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        if (rest.isEmpty()) return TargetParse.Ok(ExitRule.Network(exitId, CidrRoute(ip, 128), null, lineNo))
        if (!rest.startsWith(":")) {
            return TargetParse.Bad(RuleIssue.INVALID_TARGET, "方括号后只能跟 \":端口\" 或 \"/前缀\"")
        }
        val port = rest.substring(1).toIntOrNull()
        if (port == null || port !in PORT_MIN..PORT_MAX) {
            return TargetParse.Bad(RuleIssue.INVALID_PORT, "端口需为 1-65535")
        }
        return TargetParse.Ok(ExitRule.Network(exitId, CidrRoute(ip, 128), port, lineNo))
    }

    /**
     * `a.b.c.d/n` / `2001:db8::/n`。
     *
     * **不能直接把整段交给 `CidrRoute.parse`**: 它内部用 `InetAddress.getByName`, 而目标是用户
     * 手打的文本 —— `example.com/24` 这种笔误会触发**真实 DNS 解析**, 解析器跑在编辑器每次按键
     * 与连接建立路径上, 于是表现为"输入几秒卡死"或连接超时。这里先用 [parseIpLiteral] 的字面量
     * 预检 (只放行数字/十六进制/点/冒号), 非字面量直接判 CIDR 非法。
     */
    @Suppress("ReturnCount") // 每种非法形态一个早退出口, 合并反而绕
    private fun parseCidrTarget(
        target: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        val slash = target.indexOf('/')
        val ip = parseIpLiteral(target.substring(0, slash))
        if (ip == null) {
            return TargetParse.Bad(RuleIssue.INVALID_CIDR, "CIDR 必须由 IP 字面量开头 (如 203.0.113.0/24)")
        }
        val prefix = target.substring(slash + 1).toIntOrNull()
        if (prefix == null) return TargetParse.Bad(RuleIssue.INVALID_CIDR, "前缀长度非法")
        if (prefix == 0) return TargetParse.Bad(RuleIssue.DEFAULT_ROUTE_REJECTED, "请改用 default -> <出口>")
        if (ip.isAnyLocalAddress) {
            return TargetParse.Bad(RuleIssue.INVALID_CIDR, "0.0.0.0 / :: 不是有效目标, 请改用 default")
        }
        val maxPrefix = if (ip is Inet6Address) 128 else 32
        if (prefix !in 1..maxPrefix) {
            return TargetParse.Bad(RuleIssue.INVALID_CIDR, "前缀长度需为 1-$maxPrefix")
        }
        return TargetParse.Ok(ExitRule.Network(exitId, CidrRoute(ip, prefix), null, lineNo))
    }

    @Suppress("ReturnCount") // 纯 IP / IP:端口 / 域名三条分支各自早退, 合并反而绕
    private fun parseHostTarget(
        target: String,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        // 无端口的纯 IP 字面量先判: IPv6 若先按最后一个 ':' 切端口, "2001:db8::1" 会被切坏
        parseIpLiteral(target)?.let { ip ->
            if (ip.isAnyLocalAddress) {
                // 0.0.0.0 / :: 是"未指定地址", 用它做规则会误命中规则编辑器的占位目标,
                // 生产环境却永远不命中 (conn.dstIp 不会是它) —— 直接拒绝, 让用户改 default。
                return TargetParse.Bad(RuleIssue.INVALID_TARGET, "0.0.0.0 / :: 不是有效目标, 请改用 default")
            }
            return TargetParse.Ok(ExitRule.Network(exitId, exactRoute(ip), null, lineNo))
        }
        val split = splitPort(target) ?: return TargetParse.Bad(RuleIssue.INVALID_PORT, "端口需为 1-65535")
        val ip = parseIpLiteral(split.first)
        if (ip != null) {
            if (ip.isAnyLocalAddress) {
                return TargetParse.Bad(RuleIssue.INVALID_TARGET, "0.0.0.0 / :: 不是有效目标, 请改用 default")
            }
            return TargetParse.Ok(ExitRule.Network(exitId, exactRoute(ip), split.second, lineNo))
        }
        return parseDomainTarget(split.first, split.second, exitId, lineNo)
    }

    private fun parseDomainTarget(
        name: String,
        port: Int?,
        exitId: String,
        lineNo: Int,
    ): TargetParse {
        val suffix = name.removePrefix("*.").trimEnd('.').lowercase()
        if (!isValidDomain(suffix)) {
            return TargetParse.Bad(RuleIssue.INVALID_TARGET, "域名非法 (如 example.com / *.example.com)")
        }
        return TargetParse.Ok(ExitRule.Domain(exitId, suffix, port, lineNo))
    }

    internal fun exactRoute(ip: InetAddress): CidrRoute = CidrRoute(ip, if (ip is Inet6Address) 128 else 32)

    /** `app:` 后面的包名是否合法 (规则编辑器与测试框共用同一判据)。 */
    internal fun isValidPackage(name: String): Boolean = PACKAGE_REGEX.matches(name)

    /**
     * 切出 `name:port`。
     * @return name 与端口 (无端口时 null); null = 端口非法 (调用方报 INVALID_PORT)
     */
    internal fun splitPort(text: String): Pair<String, Int?>? {
        val idx = text.lastIndexOf(':')
        if (idx < 0) return text to null
        val port = text.substring(idx + 1).toIntOrNull()
        if (port == null || port !in PORT_MIN..PORT_MAX) return null
        val name = text.substring(0, idx)
        if (name.isEmpty()) return null
        return name to port
    }

    /**
     * 只把**字面量**交给 `InetAddress.getByName`:
     * 域名走这里会触发 DNS 解析 (解析器跑在 UI/加载路径上, 卡顿 + 结果不可预测)。
     */
    internal fun parseIpLiteral(text: String): InetAddress? {
        if (text.isEmpty()) return null
        val looksV4 = text.count { it == '.' } == 3 && text.all { it.isDigit() || it == '.' }
        val looksV6 = text.contains(':') && text.all { it.isDigit() || it in "abcdefABCDEF:." }
        if (!looksV4 && !looksV6) return null
        return try {
            InetAddress.getByName(text)
        } catch (_: Exception) {
            null
        }
    }

    internal fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        // 全数字标签 (如 `1.2.3.4.5`, 用户多点了一个点) 不是合法域名: 放行只会得到一条
        // 永不命中的规则 —— 典型的"配了没反应"
        if (labels.all { label -> label.all { it.isDigit() } }) return false
        return labels.all { DOMAIN_LABEL.matches(it) }
    }

    private fun targetKey(rule: ExitRule): String =
        when (rule) {
            is ExitRule.App -> "app:${rule.pkg}:${rule.port ?: "*"}"
            is ExitRule.Domain -> "domain:${rule.suffix}:${rule.port ?: "*"}"
            is ExitRule.Network ->
                "net:${rule.route.network.hostAddress}/${rule.route.prefixLength}:${rule.port ?: "*"}"
            is ExitRule.Default -> "default"
        }
}
