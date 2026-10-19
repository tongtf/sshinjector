package cn.srv0.sshinjector.domain.vpn

/**
 * 统计生成的广告规则文本编辑器 (`ad_block_rules_from_stats` 键的读-改-写)。
 *
 * 只做**行级**增删, 且与用户手写规则 (`ad_block_rules`) 分键存放 —— 所以"移出"永远是精确删行,
 * 绝不会误删用户手写规则 (设计 §8.0 纪律 ①)。
 *
 * 行格式:
 *  - `||domain^`   命中该域名及其所有子域名 (gfwlist 后缀语法);
 *  - `@@||domain^` 例外规则 (优先级高于命中规则) —— 用于"基础清单/用户手写命中"的误杀救援,
 *    同时它也让 `REMOVE` 对"不是统计加成名单的域名"依然可逆。
 */
object StatsRuleText {
    fun blockRule(domain: String): String = "||${DomainSuffixSet.normalize(domain)}^"

    fun exceptionRule(domain: String): String = "@@${blockRule(domain)}"

    /** 追加一行 (幂等)。空行/空白行被丢弃, 输出不带尾换行。 */
    fun addLine(
        text: String,
        line: String,
    ): String {
        val target = line.trim()
        if (target.isEmpty()) return render(lines(text))
        val current = lines(text)
        if (target in current) return render(current)
        return render(current + target)
    }

    /** 删除与 [line] **完全相同**的行 (幂等)。 */
    fun removeLine(
        text: String,
        line: String,
    ): String {
        val target = line.trim()
        if (target.isEmpty()) return render(lines(text))
        return render(lines(text).filterNot { it == target })
    }

    fun contains(
        text: String,
        line: String,
    ): Boolean {
        val target = line.trim()
        return target.isNotEmpty() && target in lines(text)
    }

    /** 非空行数 (UI 展示用)。 */
    fun ruleCount(text: String): Int = lines(text).size

    private fun lines(text: String): List<String> =
        text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun render(lines: List<String>): String = lines.joinToString("\n")
}
