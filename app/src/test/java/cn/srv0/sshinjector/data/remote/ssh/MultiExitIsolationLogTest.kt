package cn.srv0.sshinjector.data.remote.ssh

import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-5 回归: 附加出口的池失败**不得**沿用主出口的"全局结论"文案。
 *
 * 主出口池死了 = 整条链路要断 (ERROR, 触发重连); 附加出口池死了只影响该出口的新连接
 * (WARNING, 新连接改走 default)。两条走同一段代码时若不分流, 附加出口抖动会让
 * `故障归因` 多出一类假 ERROR, 并把 S21 的判读带偏 (看到"连接即将断开"就以为 VPN 断了)。
 */
class MultiExitIsolationLogTest {
    @Test
    fun `primary exit keeps the global conclusion and ERROR level`() {
        val log = poolFailureLog("")

        assertEquals("SSH 会话全部丢失 · 连接即将断开并触发重连", log.message)
        assertEquals(LogLevel.ERROR, log.level)
        assertEquals("SSH 会话全部丢失", log.throttleKey)
    }

    @Test
    fun `additional exit degrades to WARNING and never claims the vpn is disconnecting`() {
        val log = poolFailureLog("hk")

        assertTrue(log.message.contains("出口 SSH 会话全部丢失 · hk"))
        assertTrue(log.message.contains("新连接改走 default"))
        assertFalse("附加出口不得声称整条连接即将断开", log.message.contains("连接即将断开"))
        assertEquals(LogLevel.WARNING, log.level)
        assertEquals("出口降级", log.throttleKey)
    }

    @Test
    fun `primary exit no-session log stays byte identical`() {
        val log = noSessionLog("", "example.com", 443, "[conn=true/healthy=true]")

        assertEquals(
            "SSH 通道打开失败 · 无可用会话 (example.com:443) (pool: [conn=true/healthy=true])",
            log.message,
        )
        assertEquals(LogLevel.ERROR, log.level)
        assertEquals("SSH 通道打开失败", log.throttleKey)
    }

    @Test
    fun `additional exit no-session log is WARNING and points at default`() {
        val log = noSessionLog("hk", "example.com", 443, "[]")

        assertTrue(log.message.contains("出口无可用会话 · hk"))
        assertTrue(log.message.contains("新连接改走 default"))
        assertFalse(log.message.contains("连接即将断开"))
        assertEquals(LogLevel.WARNING, log.level)
        assertEquals("出口降级", log.throttleKey)
    }
}
