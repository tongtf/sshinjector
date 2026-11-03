package cn.srv0.sshinjector.domain.vpn.routing

import java.net.InetAddress

/**
 * 规则编辑器里的"测试框" (设计 §13.3): 把用户输入解析成 [RouteTarget] 再走一遍 [RoutingTable.select]。
 *
 * 为什么要有它: 规则是纯文本 + 行序即优先级 + 后缀匹配 + 出口可能不存在, 用户无法靠肉眼判断
 * "这条规则到底命中没有"。测试框用**同一份**解析结果与匹配算法回答, 不另写一套判定
 * (两套判定的分歧就是最坏的一种 bug: UI 说命中, 数据面走 default)。
 *
 * 支持的输入形态 (与规则目标语法同构):
 * - `example.com` / `example.com:443` / `*.example.com`
 * - `1.2.3.4` / `1.2.3.4:443` / `2001:db8::1` / `[2001:db8::1]:443`
 * - `app:com.example.bank` / `app:com.example.bank:443`
 * - 直接粘贴 URL (`https://example.com/path`) —— 取 host 部分
 */
object RoutingProbe {
    /** 域名输入没有真实 IP; 用 0.0.0.0 占位 (`0.0.0.0/0` 规则已在解析期被拒, 不会误命中)。 */
    private val PLACEHOLDER_IP: InetAddress = InetAddress.getByName("0.0.0.0")

    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    const val DEFAULT_PORT = 443

    data class Parsed(
        val target: RouteTarget?,
        val error: String?,
    )

    data class Evaluation(
        /** 命中的出口名; null 且 [error] 为 null = 未命中任何规则 → 走 default。 */
        val exitId: String?,
        val error: String?,
        val target: RouteTarget?,
    )

    fun parse(
        query: String,
        defaultPort: Int = DEFAULT_PORT,
    ): Parsed {
        var text = query.trim()
        if (text.isEmpty()) return Parsed(null, "请输入域名、IP 或 app:包名")

        // 允许直接粘贴 URL: 只取 scheme 之后、路径之前的部分
        if (text.contains("://")) text = text.substringAfter("://").substringBefore('/').substringBefore('?')

        return when {
            text.startsWith("app:") -> parseApp(text, defaultPort)
            text.startsWith("[") -> parseBracketed(text, defaultPort)
            else -> parseHost(text, defaultPort)
        }
    }

    fun evaluate(
        table: RoutingTable,
        query: String,
        defaultPort: Int = DEFAULT_PORT,
    ): Evaluation {
        val parsed = parse(query, defaultPort)
        val target = parsed.target ?: return Evaluation(null, parsed.error, null)
        return Evaluation(exitId = table.select(target), error = null, target = target)
    }

    private fun parseApp(
        text: String,
        defaultPort: Int,
    ): Parsed {
        val split = ExitRuleParser.splitPort(text.removePrefix("app:"))
        if (split == null) return Parsed(null, "端口需为 1-65535")
        if (!ExitRuleParser.isValidPackage(split.first)) return Parsed(null, "包名非法 (如 com.example.app)")
        return Parsed(
            RouteTarget(pkg = split.first, domain = null, ip = PLACEHOLDER_IP, port = split.second ?: defaultPort),
            null,
        )
    }

    private fun parseBracketed(
        text: String,
        defaultPort: Int,
    ): Parsed {
        val close = text.indexOf(']')
        if (close < 0) return Parsed(null, "IPv6 缺少 \"]\"")
        val ip = ExitRuleParser.parseIpLiteral(text.substring(1, close))
        if (ip == null) return Parsed(null, "方括号里必须是 IPv6 地址")
        val rest = text.substring(close + 1)
        val port = if (rest.isEmpty()) defaultPort else bracketedPort(rest)
        if (port == null) return Parsed(null, "方括号后只能跟 \":端口\"")
        if (port !in PORT_MIN..PORT_MAX) return Parsed(null, "端口需为 1-65535")
        return Parsed(RouteTarget(domain = null, ip = ip, port = port), null)
    }

    private fun bracketedPort(rest: String): Int? = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null

    private fun parseHost(
        text: String,
        defaultPort: Int,
    ): Parsed {
        // 纯 IP 先判: IPv6 若先按最后一个 ':' 切端口会被切坏
        val literal = ExitRuleParser.parseIpLiteral(text)
        if (literal != null) return Parsed(RouteTarget(domain = null, ip = literal, port = defaultPort), null)

        val split = ExitRuleParser.splitPort(text)
        if (split == null) return Parsed(null, "端口需为 1-65535")
        val port = split.second ?: defaultPort
        val splitIp = ExitRuleParser.parseIpLiteral(split.first)
        if (splitIp != null) return Parsed(RouteTarget(domain = null, ip = splitIp, port = port), null)

        val suffix =
            split.first
                .removePrefix("*.")
                .trimEnd('.')
                .lowercase()
        if (!ExitRuleParser.isValidDomain(suffix)) return Parsed(null, "域名非法 (如 example.com)")
        return Parsed(RouteTarget(domain = suffix, ip = PLACEHOLDER_IP, port = port), null)
    }
}
