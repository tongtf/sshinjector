package cn.srv0.sshinjector.data.remote.ssh

/**
 * 远端解析的命令构造与输出解析 (**纯逻辑**, 单测直接锁死)。
 *
 * 安全前提: 域名来自 DNS 应答 (也就是来自网络, **攻击者可控**), 而它要进服务器上的 shell。
 * 所以进命令前必须过 [isValidHost] 的**字符白名单** —— 只允许 `[A-Za-z0-9.-]` 且标签合法,
 * shell 元字符 (`;` `|` `$` `` ` `` `&` 空格 换行 …) 一个都进不来, 无需再做引号转义。
 */
internal object RemoteLookup {
    /** 结果上限: 统计条目只留两槽 (UI 显示首个 + `+N`), 再多也没有展示位。 */
    const val MAX_IPS: Int = 8

    /** DNS 名总长上限 (RFC 1035)。 */
    private const val MAX_HOST_LENGTH = 253

    /** 标签首尾之间的字符数上限: 首 + 61 + 尾 = 63 = DNS 单标签上限。 */
    private const val LABEL_MIDDLE_MAX = 61

    private val HOST_PATTERN =
        Regex(
            "^[A-Za-z0-9]([A-Za-z0-9-]{0,$LABEL_MIDDLE_MAX}[A-Za-z0-9])?" +
                "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,$LABEL_MIDDLE_MAX}[A-Za-z0-9])?)*$",
        )

    /**
     * 是否可以安全地拼进远端命令。
     *
     * 拒绝: 空串 / 超长 / 前后导点 / 连续点 / 以 `-` 或 `.` 开头的标签 / 任何非
     * `[A-Za-z0-9.-]` 字符 (含空格与换行)。全小写不是要求 (DNS 大小写不敏感), 交给 `getent`。
     */
    fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
        return HOST_PATTERN.matches(host)
    }

    /** 池内会话上执行的命令; 调用方**必须**先用 [isValidHost] 校验。 */
    fun command(host: String): String = "getent ahosts $host"

    /**
     * 解析 `getent ahosts` 输出: 每行 ` <地址> <socket 类型> [名字]`, 同一地址会重复出现
     * (STREAM/DGRAM/RAW) —— 按首次出现顺序去重, 忽略非地址行 (告警/空行), 最多 [MAX_IPS] 条。
     *
     * **顺序即答案**: 不重排 v4/v6, 因为 `getent` 的顺序就是服务器 `getaddrinfo` 的偏好顺序 ——
     * 首个地址正是 SSH 通道实际会拨的那个 (gai.conf 修正后双栈域名 v6 优先)。重排会让展示的
     * "远端视角 IP" 与真实出口不一致。
     */
    fun parseGetent(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        return raw
            .lineSequence()
            .mapNotNull { addressOf(it) }
            .distinct()
            .take(MAX_IPS)
            .toList()
    }

    /** 取一行里的地址 (第一列); 不是地址行 (空行 / `getent: command not found`) 返回 null。 */
    private fun addressOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val end = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        val address = if (end < 0) trimmed else trimmed.substring(0, end)
        return address.takeIf { isIpLiteral(it) }
    }

    /**
     * 宽松的 IP 字面量判定 (只用来从 `getent` 输出里挑地址行, 不用于校验用户输入):
     * IPv6 允许 `:`/`.`/十六进制, IPv4 必须是四段数字点分。
     */
    fun isIpLiteral(text: String): Boolean {
        if (text.isEmpty() || text.length > 45) return false
        return if (text.contains(':')) {
            text.count { it == ':' } >= 2 &&
                text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        } else {
            text.count { it == '.' } == 3 && text.all { it.isDigit() || it == '.' }
        }
    }
}
