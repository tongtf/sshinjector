package cn.srv0.sshinjector.ui.biometric

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricAuth
    @Inject
    constructor(
        private val keys: SshKeyManager,
    ) {
        /**
         * 判断指定密钥是否要求生物识别/锁屏认证才能签名。
         */
        fun needsBiometric(keyAlias: String): Boolean = keyAlias.isNotEmpty() && keys.isBiometricProtected(keyAlias)

        /**
         * 弹出生物识别认证框。认证成功后回调 onSuccess。
         * 认证失败/取消回调 onCancelled。
         */
        fun authenticate(
            activity: FragmentActivity,
            title: String,
            onSuccess: () -> Unit,
            onCancelled: () -> Unit,
        ) {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt =
                BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val cipher = result.cryptoObject?.cipher
                            if (cipher != null) {
                                onSuccess()
                            } else {
                                onCancelled()
                            }
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            onCancelled()
                        }

                        override fun onAuthenticationFailed() {
                            // 指纹不匹配, 允许重试
                        }
                    },
                )
            val promptInfo =
                PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(activity.getString(R.string.settings_verify_identity_sub))
                    .setNegativeButtonText(activity.getString(R.string.cancel))
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    ).build()

            val cipher =
                try {
                    createAuthCipher()
                } catch (_: Exception) {
                    onCancelled()
                    return
                }

            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }

        private fun createAuthCipher(): Cipher {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val secretKey = getOrCreateBiometricSecretKey()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            return cipher
        }

        private fun getOrCreateBiometricSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existingKey = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
            if (existingKey != null) return existingKey

            val keyGenerator =
                KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore",
                )
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        BIOMETRIC_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        /**
         * 带生物识别门控的连接:
         * - 密钥需要认证时先弹认证框, 成功后执行 onGranted
         * - 密钥无需认证时直接执行 onGranted
         * - 认证失败/取消执行 onDenied
         */
        fun connectIfAllowed(
            activity: FragmentActivity,
            keyAlias: String,
            onGranted: () -> Unit,
            onDenied: (() -> Unit)? = null,
        ) {
            if (needsBiometric(keyAlias)) {
                authenticate(
                    activity = activity,
                    title = activity.getString(R.string.settings_verify_identity),
                    onSuccess = onGranted,
                    onCancelled = { onDenied?.invoke() },
                )
            } else {
                onGranted()
            }
        }

        companion object {
            private const val BIOMETRIC_KEY_ALIAS = "sshinjector_biometric_key"
            private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

            fun from(activity: FragmentActivity): BiometricAuth =
                EntryPointAccessors.fromActivity(activity, BiometricAuthEntryPoint::class.java).biometricAuth()
        }
    }

@EntryPoint
@InstallIn(ActivityComponent::class)
interface BiometricAuthEntryPoint {
    fun biometricAuth(): BiometricAuth
}
