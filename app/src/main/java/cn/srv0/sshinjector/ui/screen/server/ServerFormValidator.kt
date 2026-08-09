package cn.srv0.sshinjector.ui.screen.server

/**
 * 服务器表单输入校验。每个校验函数返回第一个错误，null 表示通过。
 * 校验规则同时服务于 ServerWizardViewModel（请求参数边界）与 ServerEditScreen（输入框）。
 */
enum class ServerFormError {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    HOST_REQUIRED,
    HOST_INVALID,
    PORT_RANGE,
    SOCKS_PORT_RANGE,
    MTU_RANGE,
    KEEPALIVE_RANGE,
    USERNAME_REQUIRED,
    USERNAME_INVALID,
}

object ServerFormValidator {
    const val MAX_NAME_LENGTH = 64
    const val MAX_HOST_LENGTH = 253
    const val MAX_USERNAME_LENGTH = 64
    const val PORT_MIN = 1
    const val PORT_MAX = 65535
    const val SOCKS_PORT_MIN = 1024
    const val SOCKS_PORT_MAX = 65535
    const val MTU_MIN = 576
    const val MTU_MAX = 1500
    const val KEEPALIVE_MIN = 0
    const val KEEPALIVE_MAX = 3600

    private val HOSTNAME_REGEX =
        Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$")
    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val IPV6_REGEX = Regex("^[0-9a-fA-F:.%]+$")
    private val ILLEGAL_CHARS = Regex("[\\s\\p{Cntrl}]")

    fun nameError(name: String): ServerFormError? {
        if (name.isBlank()) return ServerFormError.NAME_REQUIRED
        if (name.length > MAX_NAME_LENGTH) return ServerFormError.NAME_TOO_LONG
        return null
    }

    fun hostError(host: String): ServerFormError? {
        val h = host.trim()
        if (h.isEmpty()) return ServerFormError.HOST_REQUIRED
        if (h.length > MAX_HOST_LENGTH || ILLEGAL_CHARS.containsMatchIn(h)) return ServerFormError.HOST_INVALID
        val valid =
            when {
                h.contains(':') -> IPV6_REGEX.matches(h)
                IPV4_REGEX.matches(h) -> h.split('.').all { it.toInt() in 0..255 }
                else -> HOSTNAME_REGEX.matches(h)
            }
        return if (valid) null else ServerFormError.HOST_INVALID
    }

    fun portError(text: String): ServerFormError? {
        val p = text.toIntOrNull() ?: return ServerFormError.PORT_RANGE
        return if (p in PORT_MIN..PORT_MAX) null else ServerFormError.PORT_RANGE
    }

    fun socksPortError(text: String): ServerFormError? {
        val p = text.toIntOrNull() ?: return ServerFormError.SOCKS_PORT_RANGE
        return if (p in SOCKS_PORT_MIN..SOCKS_PORT_MAX) null else ServerFormError.SOCKS_PORT_RANGE
    }

    fun mtuError(text: String): ServerFormError? {
        val v = text.toIntOrNull() ?: return ServerFormError.MTU_RANGE
        return if (v in MTU_MIN..MTU_MAX) null else ServerFormError.MTU_RANGE
    }

    fun keepAliveError(text: String): ServerFormError? {
        val v = text.toIntOrNull() ?: return ServerFormError.KEEPALIVE_RANGE
        return if (v in KEEPALIVE_MIN..KEEPALIVE_MAX) null else ServerFormError.KEEPALIVE_RANGE
    }

    fun usernameError(username: String): ServerFormError? {
        if (username.isBlank()) return ServerFormError.USERNAME_REQUIRED
        if (username.length > MAX_USERNAME_LENGTH || ILLEGAL_CHARS.containsMatchIn(username)) {
            return ServerFormError.USERNAME_INVALID
        }
        return null
    }
}
