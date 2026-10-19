package cn.srv0.sshinjector.domain.vpn

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * 「按应用 uid (P2) 可行性探针」的可单测那一半。
 *
 * 真机行为 (是否给隧道连接返回归属 / 会不会被 SecurityException 拒 / 用量访问授权状态) 只能在设备上验,
 * 但**立论前提**可以在 JVM 上钉住: `getConnectionOwnerUid` 必须是"公开 SDK 里就有"的 API —— 它决定了
 * ④ 值不值得做 (2026-10-19 本地取证: android.jar 公开 stub + api-versions.xml `since="29"` +
 * stub javadoc 明确写给活动 VpnService)。前提变了就该重新评估设计, 而不是继续补探针。
 */
class AppUidProbeTest {
    private val probe = AppUidProbe(Mockito.mock(Context::class.java))

    @Test
    fun `connection owner uid is part of the public sdk`() {
        assertTrue(
            "getConnectionOwnerUid 不再出现在公开 SDK 里 —— ④ 的前提变了 (见设计附录 A), 重新评估可行性",
            probe.ownerUidMethodVisible(),
        )
    }

    @Test
    fun `probe refuses to run while the vpn is not active`() {
        // 该 API 只对活动 VpnService 开放: 未连接时调用只会得到 SecurityException, 那是误判不是结论
        val result = probe.probe(vpnActive = false)
        assertEquals(AppUidProbe.OwnerUid.VPN_NOT_ACTIVE, result.ownerUid)
    }

    @Test
    fun `probe degrades instead of crashing without services`() {
        // mock Context 的 getSystemService 返回 null → 必须安全降级 (ERROR), 绝不抛; 两项辅证同理为 false
        val result = probe.probe(vpnActive = true)
        assertEquals(AppUidProbe.OwnerUid.ERROR, result.ownerUid)
        assertFalse(result.usageStatsGranted)
        assertFalse(result.uidLabelResolvable)
    }
}
