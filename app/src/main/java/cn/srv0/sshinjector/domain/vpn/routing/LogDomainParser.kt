package cn.srv0.sshinjector.domain.vpn.routing

/**
 * 从**应用内日志**里提取连接目标域名 (日志页"一键固定域名"用)。
 *
 * 只认数据面自己打的那几条固定文案, 不做通用文本猜测 —— 日志里域名与 IP 字面量
 * 混在一起 (`target()` 在拿不到假 IP 反查结果时会打 `1.2.3.4:443`), 猜错就会给 IP
 * 写一条永不命中的域名规则。
 *
 * 支持的形态 (分隔符是 ` · `, 目标后可能跟 ` — <细节>`):
 *  - `TCP 连接结束 · example.com(443) — …`
 *  - `隧道通道就绪 · example.com(443) — …`
 *  - `远端不解析该目标 (…) · example.com:443 — …`
 *  - `SOCKS 连接被拒 · example.com(443) — …`
 *
 * IP 字面量 (v4/v6, 含方括号)、占位符 `?`/`-`、空串一律返回 null。
 */
object LogDomainParser {
    private val PREFIXES =
        listOf(
            "TCP 连接结束",
            "隧道通道就绪",
            "远端不解析该目标",
            "SOCKS 连接被拒",
        )

    fun domainOf(message: String): String? {
        val prefix = PREFIXES.firstOrNull { message.startsWith(it) } ?: return null
        val separator = message.indexOf(" · ", prefix.length)
        if (separator < 0) return null
        // 目标标识是分隔符后的第一个空白前 token: `host(443)` / `host:port` / `ip:port`
        val token = message.substring(separator + 3).trim().takeWhile { !it.isWhitespace() }
        return hostOf(token)
    }

    @Suppress("ReturnCount") // 每种非域名形态一个早退出口, 合并反而绕
    private fun hostOf(token: String): String? {
        if (token.isEmpty()) return null
        // 方括号 = IPv6; 多个冒号 = 未加方括号的 IPv6 (域名最多一个 `:port`)
        if (token.startsWith("[")) return null
        if (token.count { it == ':' } > 1) return null
        val host =
            (if (token.contains('(')) token.substringBefore('(') else token.substringBefore(':'))
                .trim()
                .trimEnd('.')
                .lowercase()
        if (host.isEmpty() || host == "?" || host == "-") return null
        if (isIpLiteral(host)) return null
        // 单标签主机名 (localhost) 写不成路由规则 (ExitRuleParser 要求至少两级);
        // 在这里就拒绝, 避免日志页显示"已固定"但实际什么都没写。
        return host.takeIf { ExitRuleParser.isValidDomain(it) }
    }

    /** IP 字面量判定: 不触发 DNS (日志页收集时绝不能卡在解析上)。 */
    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        if (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) return true
        return ExitRuleParser.parseIpLiteral(host) != null
    }
}
