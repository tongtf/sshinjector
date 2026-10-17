package cn.srv0.sshinjector.ui.biometric

import android.content.Context
import androidx.biometric.BiometricManager

/**
 * 生物识别门禁决策 (T-12, fail-closed):
 * - 密钥不要求生物识别 → 直接放行
 * - 要求且设备可用 → 弹认证框
 * - 要求但设备/Activity 不可用 → 拒绝 (绝不静默放行)
 */
enum class GateAction { CONNECT, PROMPT, DENY }

/**
 * 纯决策函数 (可单测): 由「密钥是否要求认证」与「设备是否可用」推出动作。
 * 注意: 旧实现的 fail-open (不可用即放行) 已废弃 —— 要求认证却无法认证时必须 DENY。
 */
internal fun biometricGateDecision(
    needsBiometric: Boolean,
    biometricAvailable: Boolean,
): GateAction =
    when {
        !needsBiometric -> GateAction.CONNECT
        biometricAvailable -> GateAction.PROMPT
        else -> GateAction.DENY
    }

/**
 * 设备是否具备可用的强/弱生物识别; 这是「要求认证的密钥」能否弹出认证框的前置条件。
 */
fun biometricAvailable(context: Context): Boolean =
    BiometricManager
        .from(context)
        .canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
        ) == BiometricManager.BIOMETRIC_SUCCESS
