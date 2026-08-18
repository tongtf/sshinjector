package cn.srv0.sshinjector.data.remote.ssh

import java.util.Base64
import java.util.logging.Logger
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM 凭据加解密（纯 JVM，无 Android 依赖，可单元测试）。
 * 与 CredentialCrypto 共用同一线格式：enc:v1:<base64(iv + ciphertext)>。
 * 加密失败返回原明文、解密失败返回 null，保证旧数据与降级路径可用。
 * 注意: 加密失败降级为明文是有意为之的兼容策略, 但会记录日志以便排查。
 */
class AesGcmCipher(private val key: SecretKey) {
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        if (isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + ciphertext
            ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(payload)
        } catch (e: Exception) {
            // 加密失败降级为明文存储 (兼容旧版本), 但记录日志便于追踪 Keystore 异常
            LOG.warning("AesGcmCipher encrypt failed, falling back to plaintext: ${e.message}")
            plain
        }
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
        private val LOG = Logger.getLogger(AesGcmCipher::class.java.name)
    }
}
