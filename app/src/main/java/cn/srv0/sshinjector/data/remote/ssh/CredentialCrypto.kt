package cn.srv0.sshinjector.data.remote.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密码加密/解密工具
 * 使用 Android Keystore 中的 AES-GCM 密钥，私钥永不出硬件安全模块
 */
@Singleton
class CredentialCrypto @Inject constructor() {

    companion object {
        private const val KEY_ALIAS = "ssh_credential_aes"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val ENCRYPTED_PREFIX = "enc:v1:"
    }

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * 加密密码
     * @return "enc:v1:<base64(iv + ciphertext)>"，失败时原样返回明文（保留旧数据可用）
     */
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        if (isEncrypted(plain)) return plain
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val payload = cipher.iv + ciphertext
            ENCRYPTED_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.w("CredentialCrypto", "Encrypt failed: ${e.message}")
            plain
        }
    }

    /**
     * 解密密码
     * @return 明文密码；若已是明文（旧数据）则原样返回
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        if (!isEncrypted(stored)) return stored
        return try {
            val payload = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
            if (payload.size < IV_SIZE + 16) return null
            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CredentialCrypto", "Decrypt failed: ${e.message}")
            null
        }
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith(ENCRYPTED_PREFIX) == true

    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw IllegalStateException("无法读取凭据加密密钥")
            return entry.secretKey
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}
