package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「点连接」三条分支: 已连接同一台不能重连 (会白拆会话), 另一台必须切换 (旧实现静默 no-op)。
 */
class ConnectIntentTest {
    @Test
    fun `not running means plain connect`() {
        assertEquals(ConnectIntent.CONNECT, connectIntent(running = false, currentServerId = 7, targetServerId = 7))
        assertEquals(ConnectIntent.CONNECT, connectIntent(running = false, currentServerId = null, targetServerId = 9))
    }

    @Test
    fun `same server while running is a no-op`() {
        assertEquals(
            ConnectIntent.ALREADY_CONNECTED,
            connectIntent(running = true, currentServerId = 7, targetServerId = 7),
        )
    }

    @Test
    fun `different server while running switches`() {
        assertEquals(ConnectIntent.SWITCH, connectIntent(running = true, currentServerId = 7, targetServerId = 9))
    }

    @Test
    fun `unknown current server while running still switches`() {
        assertEquals(ConnectIntent.SWITCH, connectIntent(running = true, currentServerId = null, targetServerId = 9))
    }
}
