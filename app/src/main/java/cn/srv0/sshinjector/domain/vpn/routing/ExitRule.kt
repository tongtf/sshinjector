package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.CidrRoute

/**
 * 一条路由规则 (设计 §6.2 / 附录 A)。不可变值对象, 解析一次常驻。
 *
 * 顺序即优先级 (first-match-wins): 规则在 [RoutingTable] 里的下标就是用户看到的行序,
 * 拖动行 = 改优先级。不做"最长后缀/最长前缀"打分 —— 两套尺度混在一起没有可解释的优先级。
 *
 * [exitId] 是否"真实存在"**不在规则里判断**: 出口在 Room、规则在 DataStore, 两者没有事务。
 * 引用不存在出口的行必须**保留** (改名/迁移/导入时序不一致时删行 = 永久丢配置),
 * 由 [RoutingTable] 按当前出口集合标记跳过。
 */
sealed interface ExitRule {
    /** 目标出口名 (已规范化小写; `default` = 主出口)。 */
    val exitId: String

    /** 端口限定; null = 任意端口。 */
    val port: Int?

    /** 原文行号 (1-based), UI 行内标记用。 */
    val lineNo: Int

    fun matches(t: RouteTarget): Boolean

    /** `app:com.example.app[:port]` — **包名精确匹配**, 不是 uid (重装/多用户会变)。 */
    data class App(
        override val exitId: String,
        val pkg: String,
        override val port: Int?,
        override val lineNo: Int,
    ) : ExitRule {
        override fun matches(t: RouteTarget): Boolean = t.pkg == pkg && portMatches(t.port, port)
    }

    /** `example.com[:port]` / `*.example.com[:port]` — 后缀匹配 (apex + 子域)。 */
    data class Domain(
        override val exitId: String,
        val suffix: String,
        override val port: Int?,
        override val lineNo: Int,
    ) : ExitRule {
        override fun matches(t: RouteTarget): Boolean {
            val domain = t.domain ?: return false
            return domainSuffixMatch(domain, suffix) && portMatches(t.port, port)
        }
    }

    /** CIDR 或精确 IP (精确 IP = /32、/128)。 */
    data class Network(
        override val exitId: String,
        val route: CidrRoute,
        override val port: Int?,
        override val lineNo: Int,
    ) : ExitRule {
        override fun matches(t: RouteTarget): Boolean {
            if (!portMatches(t.port, port)) return false
            return CidrRoute.matches(t.ip, route)
        }
    }

    /** `default -> x` — 未命中兜底, 只允许一条且必须是最后一行。 */
    data class Default(
        override val exitId: String,
        override val lineNo: Int,
    ) : ExitRule {
        override val port: Int? = null

        override fun matches(t: RouteTarget): Boolean = true
    }
}

/** 端口限定: 规则未限定 = 命中任意端口; 限定了就必须相等。 */
internal fun portMatches(
    actual: Int,
    expected: Int?,
): Boolean = expected == null || actual == expected

/**
 * 域名后缀匹配: 命中 apex 本身或任意子域。
 * `*.example.com` 与 `example.com` **等价** (解析时已剥掉 `*.`)。
 */
internal fun domainSuffixMatch(
    domain: String,
    suffix: String,
): Boolean {
    val d = domain.trimEnd('.').lowercase()
    if (d.isEmpty() || suffix.isEmpty()) return false
    return d == suffix || d.endsWith(".$suffix")
}
