package cn.srv0.sshinjector.domain.vpn.exit

/**
 * 出口身份 (多出口维度)。与协议名 (`TunnelPlugin.id == "socks5"`) **正交** ——
 * 一个出口可以换协议实现, 一个协议也可以有多个出口实例。
 *
 * 命名规则 (设计 §5.3 / 附录 A):
 * - 小写字母/数字/`_`/`-`, 长度 1..32;
 * - [DEFAULT] 是保留字, 用户不能把附加出口命名为 `default`;
 * - 大小写不敏感: 输入先 [normalize] 再存, 规则里的引用也按规范化后的值比较。
 */
object ExitId {
    /** 主出口: 恒指向"当前连接的服务器", 不落库、不可删、不可禁用。 */
    const val DEFAULT = "default"

    const val MAX_LENGTH = 32

    private val ALLOWED = Regex("[a-z0-9_-]+")

    /**
     * 规范化用户输入: 去首尾空白 + 转小写。
     * @return 合法出口名; null = 非法 (空/超长/含非法字符) 或等于保留字 `default`
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        if (!ALLOWED.matches(trimmed)) return null
        if (trimmed == DEFAULT) return null
        return trimmed
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    /** 规则引用里的出口名是否合法 (允许 `default`)。 */
    fun isValidReference(raw: String): Boolean {
        val trimmed = raw.trim().lowercase()
        if (trimmed == DEFAULT) return true
        return normalize(trimmed) != null
    }
}
