package cn.srv0.sshinjector.data.remote.ssh

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM 凭据加解密（纯 JVM，无 Android 依赖，可单元测试）。
 * 与 CredentialCrypto 共用同一线格式：enc:v1:<base64(iv + ciphertext)>。
 * 写端不降级: encrypt 失败直接抛出, 绝不落明文 (调用方 saveServerEdit/ViewModel 报错可重试)。
 * 读端兼容: decrypt 遇非 enc:v1: 前缀的历史明文原样返回 (存量数据), 解密失败返回 null。
 */
class AesGcmCipher(
    private val key: SecretKey,
) {
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        if (isEncrypted(plain)) return plain
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        if (!isEncrypted(stored)) return stored
        return try {
            val payload = Base64.getDecoder().decode(stored.removePrefix(ENCRYPTED_PREFIX))
            if (payload.size < IV_SIZE + TAG_BITS / 8) return null
            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith(ENCRYPTED_PREFIX) == true

    companion object {
        const val ENCRYPTED_PREFIX = "enc:v1:"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
