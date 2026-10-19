package cn.srv0.sshinjector.domain.vpn

/**
 * 域名后缀集合匹配 (代理名单叠加层与统计名单共用)。
 *
 * 语义与 [GfwListMatcher] 的后缀匹配一致: `example.com` 命中 `example.com` 与 `a.example.com`,
 * 但不命中 `notexample.com`。纯逻辑、无 Android 依赖 → JVM 单测直接验证。
 *
 * **集合元素必须已 [normalize]** (写入侧保证); [matches] 只规范化被查询的 host, 避免逐元素分配。
 */
object DomainSuffixSet {
    /** 规范化: 去空白 + 小写 + 去尾部点。空串返回空串。 */
    fun normalize(host: String): String = host.trim().lowercase().trimEnd('.')

    /** 逐级剥离最左标签做哈希查找: O(标签数), 与 `GfwListMatcher.matchSuffix` 同构。 */
    fun matches(
        suffixes: Set<String>,
        host: String,
    ): Boolean {
        if (suffixes.isEmpty()) return false
        var cur = normalize(host)
        if (cur.isEmpty()) return false
        while (cur.isNotEmpty()) {
            if (cur in suffixes) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
        }
        return false
    }
}
