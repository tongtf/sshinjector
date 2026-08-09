package cn.srv0.sshinjector.ui.screen.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerFormValidatorTest {
    @Test
    fun `host accepts hostname ipv4 and ipv6`() {
        assertNull(ServerFormValidator.hostError("example.com"))
        assertNull(ServerFormValidator.hostError("my-vps.example.org"))
        assertNull(ServerFormValidator.hostError("192.168.1.1"))
        assertNull(ServerFormValidator.hostError("10.0.0.255"))
        assertNull(ServerFormValidator.hostError("2400:cb00::2048"))
        assertNull(ServerFormValidator.hostError("::1"))
    }

    @Test
    fun `host rejects blank whitespace control chars and bad formats`() {
        assertEquals(ServerFormError.HOST_REQUIRED, ServerFormValidator.hostError(""))
        assertEquals(ServerFormError.HOST_REQUIRED, ServerFormValidator.hostError("   "))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("exa mple.com"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("exa\nmple.com"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("exa\tmple.com"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("256.1.1.1"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("-bad-host"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("exa_mple.com"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("host..com"))
        assertEquals(ServerFormError.HOST_INVALID, ServerFormValidator.hostError("host."))
    }

    @Test
    fun `name rules`() {
        assertNull(ServerFormValidator.nameError("我的 VPS"))
        assertEquals(ServerFormError.NAME_REQUIRED, ServerFormValidator.nameError(" "))
        assertEquals(ServerFormError.NAME_TOO_LONG, ServerFormValidator.nameError("x".repeat(65)))
    }

    @Test
    fun `port rules`() {
        assertNull(ServerFormValidator.portError("22"))
        assertNull(ServerFormValidator.portError("65535"))
        assertEquals(ServerFormError.PORT_RANGE, ServerFormValidator.portError("0"))
        assertEquals(ServerFormError.PORT_RANGE, ServerFormValidator.portError("65536"))
        assertEquals(ServerFormError.PORT_RANGE, ServerFormValidator.portError("abc"))
        assertEquals(ServerFormError.PORT_RANGE, ServerFormValidator.portError(""))
    }

    @Test
    fun `socks port requires 1024 to 65535`() {
        assertNull(ServerFormValidator.socksPortError("1080"))
        assertNull(ServerFormValidator.socksPortError("65535"))
        assertEquals(ServerFormError.SOCKS_PORT_RANGE, ServerFormValidator.socksPortError("22"))
        assertEquals(ServerFormError.SOCKS_PORT_RANGE, ServerFormValidator.socksPortError("70000"))
        assertEquals(ServerFormError.SOCKS_PORT_RANGE, ServerFormValidator.socksPortError("1023"))
    }

    @Test
    fun `mtu and keepalive ranges`() {
        assertNull(ServerFormValidator.mtuError("1500"))
        assertNull(ServerFormValidator.mtuError("576"))
        assertEquals(ServerFormError.MTU_RANGE, ServerFormValidator.mtuError("100"))
        assertEquals(ServerFormError.MTU_RANGE, ServerFormValidator.mtuError("2000"))
        assertNull(ServerFormValidator.keepAliveError("30"))
        assertNull(ServerFormValidator.keepAliveError("0"))
        assertEquals(ServerFormError.KEEPALIVE_RANGE, ServerFormValidator.keepAliveError("-1"))
        assertEquals(ServerFormError.KEEPALIVE_RANGE, ServerFormValidator.keepAliveError("9999"))
    }

    @Test
    fun `username rejects whitespace and control chars`() {
        assertNull(ServerFormValidator.usernameError("root"))
        assertEquals(ServerFormError.USERNAME_REQUIRED, ServerFormValidator.usernameError(" "))
        assertEquals(ServerFormError.USERNAME_INVALID, ServerFormValidator.usernameError("ro ot"))
        assertEquals(ServerFormError.USERNAME_INVALID, ServerFormValidator.usernameError("ro\noot"))
    }
}
