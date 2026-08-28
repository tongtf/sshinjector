package cn.srv0.sshinjector.data.remote.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密码加密/解密工具：密钥托管于 Android Keystore（AES-GCM，密钥不出安全模块），
 * 实际加解密逻辑委托给纯 JVM 的 [AesGcmCipher]（可单元测试）。
 */
@Singleton
class CredentialCrypto
    @Inject
    constructor() {
        private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun encrypt(plain: String?): String? = cipher().encrypt(plain)

        fun decrypt(stored: String?): String? = cipher().decrypt(stored)

        fun isEncrypted(value: String?): Boolean = value?.startsWith(AesGcmCipher.ENCRYPTED_PREFIX) == true

        private fun cipher(): AesGcmCipher = AesGcmCipher(getOrCreateKey())

        private fun getOrCreateKey(): SecretKey {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry =
                    keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                        ?: error("无法读取凭据加密密钥")
                return entry.secretKey
            }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return kg.generateKey()
        }

        private companion object {
            const val KEY_ALIAS = "ssh_credential_aes"
        }
    }
