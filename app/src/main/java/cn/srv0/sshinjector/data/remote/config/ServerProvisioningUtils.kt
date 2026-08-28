package cn.srv0.sshinjector.data.remote.config

import java.security.MessageDigest

/** 从 `sha256sum` 输出中提取 64 位十六进制哈希（忽略文件名部分） */
internal fun extractSha256(stdout: String): String? =
    stdout.trim().split(Regex("\\s+")).firstOrNull()?.takeIf {
        it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
    }

/** 校验 OpenSSH 公钥格式：允许 ssh-ed25519 / ssh-rsa / ecdsa-sha2-*，格式 "<algo> <base64> [comment]" */
internal fun isValidPublicKey(key: String): Boolean {
    val parts = key.trim().split(Regex("\\s+"))
    if (parts.size < 2) return false
    val algo = parts[0]
    if (algo !in setOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521")) {
        return false
    }
    val b64 = parts[1]
    return b64.length >= 32 && b64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
}

/** 计算 SHA-256 十六进制摘要 */
internal fun sha256(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(data).joinToString("") { "%02x".format(it) }
}
