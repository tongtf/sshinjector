package cn.srv0.sshinjector.domain.vpn.routing

/**
 * 出口改名/删除时对**规则文本**的行级改写 (设计 §8.11: 规则文本只由用户在 UI 显式操作才改)。
 *
 * 为什么必须有它: 规则里的出口名和出口表是**两份数据**(DataStore 文本 vs Room 行), 改名不联动
 * 就会让 N 条规则静默失效 —— 用户看到的是"规则还在, 就是不生效", 这是最难自查的一类。
 *
 * 只用文本操作、不做完整解析: 保留行序、注释、缩进与用户手写的空格 (解析后重新序列化会把
 * 用户的排版全部抹掉)。
 */
object ExitRuleText {
    /** 把 `-> from` 逐行改写为 `-> to`; 没有引用时**原样返回** (含换行符)。 */
    fun renameExit(
        text: String,
        from: String,
        to: String,
    ): String {
        val target = from.trim().lowercase()
        if (target.isEmpty()) return text
        val renamed =
            text
                .lineSequence()
                .joinToString("\n") { line ->
                    val (body, comment) = splitLine(line)
                    val arrow = body.indexOf("->")
                    if (arrow <= 0) return@joinToString line
                    val exitStart = arrow + 2
                    val exitName = body.substring(exitStart).trim()
                    if (exitName.lowercase() != target) return@joinToString line
                    val ws = body.substring(exitStart).takeWhile { it.isWhitespace() }
                    val tail = body.substring(body.trimEnd().length)
                    body.substring(0, exitStart) + ws + to.trim() + tail + comment
                }
        return preserveTrailingNewline(text, renamed)
    }

    /**
     * 删除所有引用该出口的规则行。
     * @return 新文本 + 被删除的行数 (0 = 没有任何改动, 调用方据此决定是否写回)
     */
    fun removeExitRules(
        text: String,
        exitName: String,
    ): Pair<String, Int> {
        val target = exitName.trim().lowercase()
        if (target.isEmpty()) return text to 0
        var removed = 0
        val kept =
            text
                .lineSequence()
                .filter { line ->
                    val (body, _) = splitLine(line)
                    val arrow = body.indexOf("->")
                    val match = arrow > 0 && body.substring(arrow + 2).trim().lowercase() == target
                    if (match) removed++
                    !match
                }.joinToString("\n")
        return if (removed == 0) text to 0 else preserveTrailingNewline(text, kept) to removed
    }

    /** `lineSequence()` 会吃掉末尾换行; 文本改写必须逐字节保留用户原来的结尾。 */
    private fun preserveTrailingNewline(
        original: String,
        rewritten: String,
    ): String = if (original.endsWith("\n") && !rewritten.endsWith("\n")) rewritten + "\n" else rewritten

    /** 把一行拆成"内容"与"行尾注释" (`#` 之后原样保留, 含 `#`)。 */
    private fun splitLine(line: String): Pair<String, String> {
        val idx = line.indexOf('#')
        return if (idx < 0) line to "" else line.substring(0, idx) to line.substring(idx)
    }
}
