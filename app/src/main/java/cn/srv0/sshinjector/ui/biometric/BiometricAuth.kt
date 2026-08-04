package cn.srv0.sshinjector.ui.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricAuth @Inject constructor(
    private val keyManager: SshKeyManager
) {    /**
     * 判断指定密钥是否要求生物识别/锁屏认证才能签名。
     */
    fun needsBiometric(keyAlias: String): Boolean =
        keyAlias.isNotEmpty() && keyManager.isBiometricProtected(keyAlias)

    /**
     * 弹出生物识别认证框。认证成功后回调 onSuccess。
     * 认证失败/取消回调 onCancelled。
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onCancelled()
            }

            override fun onAuthenticationFailed() {
                // 指纹不匹配, 允许重试
            }
        })
        val promptInfo = PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("验证身份以解锁 SSH 密钥")
            .setNegativeButtonText("取消")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
            .build()
        prompt.authenticate(promptInfo)
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
        onDenied: (() -> Unit)? = null
    ) {
        if (needsBiometric(keyAlias)) {
            authenticate(
                activity = activity,
                title = "验证身份",
                onSuccess = onGranted,
                onCancelled = { onDenied?.invoke() }
            )
        } else {
            onGranted()
        }
    }

    companion object {
        fun from(activity: FragmentActivity): BiometricAuth =
            EntryPointAccessors.fromActivity(activity, BiometricAuthEntryPoint::class.java).biometricAuth()
    }
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface BiometricAuthEntryPoint {
    fun biometricAuth(): BiometricAuth
}
