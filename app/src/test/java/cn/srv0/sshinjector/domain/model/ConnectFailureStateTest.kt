package cn.srv0.sshinjector.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 建连早期失败必须发布终态, 否则 UI 永远停在 Connecting。 */
class ConnectFailureStateTest {
    @Test
    fun `failure clears stage and records the failed step`() {
        val base =
            VpnState(
                status = VpnState.VpnStatus.Connecting,
                connectStage = ConnectStage.TUN,
            )
        val next = connectFailureState(base, HealthStep.TUN)
        assertEquals(VpnState.VpnStatus.Failed, next.status)
        assertEquals(HealthStep.TUN, next.failedStep)
        assertNull(next.connectStage)
    }

    @Test
    fun `failure without a step still terminates`() {
        val next = connectFailureState(VpnState(status = VpnState.VpnStatus.Connecting), null)
        assertEquals(VpnState.VpnStatus.Failed, next.status)
        assertNull(next.failedStep)
        assertNull(next.connectStage)
    }
}
