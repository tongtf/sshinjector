package cn.srv0.sshinjector.domain.vpn

/**
 * gfwlist 风格域名列表匹配器
 *
 * 支持的规则:
 *  - `||domain^`           域名及其所有子域名(后缀匹配)
 *  - `.domain` / 裸 `domain`  域名后缀匹配
 *  - `|http(s)://host/path`   提取 host 做后缀匹配
 *  - 含 `*` 的规则          转为正则匹配
 *  - `@@` 前缀              例外规则, 优先级高于命中规则
 *
 * 忽略的规则: `!` 注释、`[AutoProxy ...]` 头部、`/regex/` 正则、IP 地址、空行。
 */
class GfwListMatcher(
    private val blockSuffixes: Set<String>,
    private val exceptSuffixes: Set<String>,
    private val blockPatterns: List<Regex>,
    private val exceptPatterns: List<Regex>,
) {
    val ruleCount: Int
        get() = blockSuffixes.size + exceptSuffixes.size + blockPatterns.size + exceptPatterns.size

    /**
     * @return true 表示域名应走代理
     */
    fun matches(host: String): Boolean {
        val h = normalize(host) ?: return false
        if (matchSuffix(exceptSuffixes, h)) return false
        if (matchSuffix(blockSuffixes, h)) return true
        if (exceptPatterns.any { it.matches(h) }) return false
        return blockPatterns.any { it.matches(h) }
    }

    private fun normalize(host: String): String? {
        val h = host.trim().lowercase().trimEnd('.')
        return h.ifEmpty { null }
    }

    private fun matchSuffix(
        set: Set<String>,
        host: String,
    ): Boolean {
        var h = host
        while (h.isNotEmpty()) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        return false
    }

    companion object {
        private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun parse(rawText: String): GfwListMatcher {
            val builder =
                GfwListBuilder(
                    blockSuffix = HashSet(),
                    exceptSuffix = HashSet(),
                    blockPatterns = mutableListOf(),
                    exceptPatterns = mutableListOf(),
                )

            val text = if (rawText.startsWith("\uFEFF")) rawText.substring(1) else rawText
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    if (line.startsWith("!") || line.startsWith("[")) return@forEach
                    if (line.startsWith("/")) return@forEach // 忽略 /regex/ 规则
                    val isException = line.startsWith("@@")
                    val rule = if (isException) line.substring(2) else line
                    addRule(builder, rule, isException)
                }

            return GfwListMatcher(
                builder.blockSuffix,
                builder.exceptSuffix,
                builder.blockPatterns,
                builder.exceptPatterns,
            )
        }

        private data class GfwListBuilder(
            val blockSuffix: MutableSet<String>,
            val exceptSuffix: MutableSet<String>,
            val blockPatterns: MutableList<Regex>,
            val exceptPatterns: MutableList<Regex>,
        )

        private fun addRule(
            builder: GfwListBuilder,
            rule: String,
            isException: Boolean,
        ) {
            val host = extractHost(rule) ?: return
            if (host.contains('*')) {
                val regex = wildcardToRegex(host)
                if (isException) {
                    builder.exceptPatterns.add(regex)
                } else {
                    builder.blockPatterns.add(regex)
                }
            } else {
                if (isException) {
                    builder.exceptSuffix.add(host)
                } else {
                    builder.blockSuffix.add(host)
                }
            }
        }

        private fun extractHost(rule: String): String? {
            var r = rule.trimEnd('^')
            if (r.startsWith("||")) r = r.substring(2)
            if (r.startsWith("|")) r = r.substring(1)
            val schemeIdx = r.indexOf("://")
            if (schemeIdx >= 0) r = r.substring(schemeIdx + 3)
            val pathIdx = r.indexOf('/')
            if (pathIdx >= 0) r = r.substring(0, pathIdx)
            r = r.removePrefix(".").trim()
            if (r.isEmpty()) return null
            if (r.contains(':')) return null // IP:port 行
            if (IPV4_REGEX.matches(r)) return null // IP 地址行
            return r.lowercase()
        }

        private fun wildcardToRegex(pattern: String): Regex {
            if (pattern.startsWith("*.")) {
                // *.example.com 语义: 匹配裸域名及所有子域名
                return Regex("^(.*\\.)?${Regex.escape(pattern.substring(2))}$")
            }
            val sb = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '.', '?', '+', '(', ')', '[', ']', '{', '}', '\\', '^', '$', '|', '-' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}
