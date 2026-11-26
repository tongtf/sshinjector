package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.exit.ExitId

/**
 * 单条规则的**行级**文本编辑 (一键路由用)。
 *
 * 为什么不用"解析 → 改模型 → 重新序列化": 那会把用户的注释、空行、缩进、手写空格
 * 与高级规则 (IP/CIDR/端口) 全部抹掉。这里只删/插**目标那一行**, 其它行逐字节保留。
 *
 * 插入位置由优先级决定 (行序即优先级):
 *  - 应用规则最先匹配 → 插到开头注释区之后 ([ExitRuleText.prependAppRule] 的同一位置语义);
 *  - 域名规则最长后缀优先 → 插到第一个"不更长"的域名规则之前; 没有域名规则则插到
 *    `default` 之前; 连 `default` 都没有则追加到末尾。
 *
 * 入口参数非法 (出口名/包名/域名不合法) 时**原样返回** —— 调用方据此判断没写成功。
 */
object RoutingRuleText {
    /**
     * 新增/更新一条 `app:` 规则。
     *
     * 先删掉所有同包名行 (含 `app:pkg:443` 这种带端口的变体 —— 简单模式只表达"整包"),
     * 再插到开头注释区之后。
     */
    fun upsertApp(
        text: String,
        packageName: String,
        exitId: String,
    ): String {
        val pkg = packageName.trim()
        val exit = exitId.trim().lowercase()
        if (!SimpleRoutingPolicyCodec.isValidPackage(pkg) || !ExitId.isValidReference(exit)) return text
        val removed =
            text
                .lineSequence()
                .filterNot { appPackageOf(it) == pkg }
                .joinToString("\n")
        val kept =
            when {
                removed.isEmpty() -> ""
                text.endsWith("\n") && !removed.endsWith("\n") -> "$removed\n"
                else -> removed
            }
        return matchTrailingNewline(text, ExitRuleText.prependAppRule(kept, pkg, exit))
    }

    /**
     * 新增/更新一条域名规则。
     *
     * 先删掉同名后缀的行 (`example.com` / `*.example.com` / `example.com:443` 视为同一条),
     * 再按最长后缀优先插到合适位置。
     */
    fun upsertDomain(
        text: String,
        domain: String,
        exitId: String,
    ): String {
        val suffix = SimpleRoutingPolicyCodec.normalizeDomain(domain) ?: return text
        val exit = exitId.trim().lowercase()
        if (!ExitId.isValidReference(exit)) return text
        val lines = text.split("\n").toMutableList()
        lines.removeAll { domainSuffixOf(it) == suffix }
        lines.add(domainInsertIndex(lines, suffix.length), "$suffix -> $exit")
        return matchTrailingNewline(text, lines.joinToString("\n"))
    }

    /**
     * 插入下标: 第一条"后缀不更长"的域名规则之前; 否则 `default` 之前;
     * 否则最后一个非空行之后。
     */
    private fun domainInsertIndex(
        lines: List<String>,
        newLength: Int,
    ): Int {
        for (index in lines.indices) {
            val suffix = domainSuffixOf(lines[index])
            if (suffix != null) {
                if (suffix.length <= newLength) return index
            } else if (isDefaultLine(lines[index])) {
                return index
            }
        }
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        return end
    }

    /** 该行是域名规则时返回规范化后缀 (`*.` 已剥、小写), 否则 null。 */
    @Suppress("ReturnCount") // 五种"不是域名规则"的形态各自早退, 合并反而绕
    private fun domainSuffixOf(line: String): String? {
        val target = targetOf(line) ?: return null
        if (target == "default" || target.startsWith("app:") || target.startsWith("[")) return null
        if (target.contains('/')) return null
        val split = ExitRuleParser.splitPort(target) ?: return null
        if (ExitRuleParser.parseIpLiteral(split.first) != null) return null
        val suffix =
            split.first
                .removePrefix("*.")
                .trimEnd('.')
                .lowercase()
        return if (ExitRuleParser.isValidDomain(suffix)) suffix else null
    }

    /** 该行是 `app:` 规则时返回包名 (带端口也照常返回), 否则 null。 */
    private fun appPackageOf(line: String): String? {
        val target = targetOf(line) ?: return null
        if (!target.startsWith("app:")) return null
        return ExitRuleParser.splitPort(target.removePrefix("app:"))?.first
    }

    /** 取 `->` 左侧的目标文本 (去掉行尾注释); 没有箭头返回 null。 */
    private fun targetOf(line: String): String? {
        val body = line.substringBefore('#').trim()
        val arrow = body.indexOf("->")
        if (arrow <= 0) return null
        return body.substring(0, arrow).trim()
    }

    private fun isDefaultLine(line: String): Boolean = targetOf(line) == "default"

    /**
     * `lineSequence()`/`split()` 会制造/吞掉末尾换行; 文本改写必须与用户原来的结尾一致
     * (原来有就保留, 原来没有就不新增)。
     */
    private fun matchTrailingNewline(
        original: String,
        rewritten: String,
    ): String =
        when {
            original.endsWith("\n") -> if (rewritten.endsWith("\n")) rewritten else "$rewritten\n"
            rewritten.endsWith("\n") -> rewritten.dropLast(1)
            else -> rewritten
        }
}
