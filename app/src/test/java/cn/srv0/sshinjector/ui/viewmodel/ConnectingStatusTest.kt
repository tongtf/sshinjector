package cn.srv0.sshinjector.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 拒绝/取消 VPN 授权后必须收回乐观状态, 否则该服务器行永久转圈。 */
class ConnectingStatusTest {
    @Test
    fun `clears only the requested server`() {
        val map = mapOf(1L to "Connecting", 2L to "Connected")
        assertEquals(mapOf(2L to "Connected"), clearConnectingStatus(map, 1L))
    }

    @Test
    fun `no pending server leaves the map untouched`() {
        val map = mapOf(1L to "Connecting")
        assertEquals(map, clearConnectingStatus(map, null))
    }

    @Test
    fun `unknown id is a no-op`() {
        val map = mapOf(1L to "Connecting")
        assertEquals(map, clearConnectingStatus(map, 9L))
        assertFalse(clearConnectingStatus(map, 9L).containsKey(9L))
    }
}
