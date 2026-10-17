package cn.srv0.sshinjector.ui.biometric

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生物识别门禁三态决策 (T-12): 旧实现 fail-open (不可用即放行), 现要求认证而
 * 无法认证时必须拒绝连接 (fail-closed)。
 */
class BiometricGateTest {
    @Test
    fun `key without biometric requirement connects directly`() {
        assertEquals(
            GateAction.CONNECT,
            biometricGateDecision(needsBiometric = false, biometricAvailable = false),
        )
        assertEquals(
            GateAction.CONNECT,
            biometricGateDecision(needsBiometric = false, biometricAvailable = true),
        )
    }

    @Test
    fun `required and available prompts for authentication`() {
        assertEquals(
            GateAction.PROMPT,
            biometricGateDecision(needsBiometric = true, biometricAvailable = true),
        )
    }

    @Test
    fun `required but unavailable denies`() {
        assertEquals(
            GateAction.DENY,
            biometricGateDecision(needsBiometric = true, biometricAvailable = false),
        )
    }
}
