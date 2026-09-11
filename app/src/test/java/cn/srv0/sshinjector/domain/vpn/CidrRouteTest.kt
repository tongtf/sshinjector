package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * [CidrRoute] 纯算法单测：parse 合法性/越界 prefix 过滤，matches 的 IPv4/IPv6、
 * 边界前缀与跨族行为。全为 IP 字面量，InetAddress.getByName 不触发 DNS。
 */
class CidrRouteTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    // ===== parse：合法 =====
    @Test
    fun `parse valid ipv4`() {
        assertEquals(8, CidrRoute.parse("10.0.0.0/8")?.prefixLength)
    }

    @Test
    fun `parse valid ipv6`() {
        assertEquals(32, CidrRoute.parse("2400:cb00::/32")?.prefixLength)
    }

    // ===== parse：非法 / 越界 → null =====
    @Test
    fun `parse rejects missing prefix`() {
        assertNull(CidrRoute.parse("10.0.0.0"))
    }

    @Test
    fun `parse rejects non-numeric prefix`() {
        assertNull(CidrRoute.parse("10.0.0.0/abc"))
    }

    @Test
    fun `parse rejects ipv4 prefix over 32`() {
        assertNull(CidrRoute.parse("10.0.0.0/33"))
    }

    @Test
    fun `parse rejects ipv6 prefix over 128`() {
        assertNull(CidrRoute.parse("2400::/129"))
    }

    @Test
    fun `parse rejects negative prefix`() {
        assertNull(CidrRoute.parse("10.0.0.0/-1"))
    }

    @Test
    fun `parse rejects garbage host`() {
        assertNull(CidrRoute.parse("not-an-ip/8"))
    }

    // ===== matches：IPv4 =====
    @Test
    fun `ipv4 prefix-8 in-range and out-of-range`() {
        val r = CidrRoute.parse("10.0.0.0/8")!!
        assertTrue(CidrRoute.matches(ip("10.255.1.1"), r))
        assertFalse(CidrRoute.matches(ip("11.0.0.1"), r))
    }

    @Test
    fun `ipv4 prefix-24 boundary`() {
        val r = CidrRoute.parse("192.168.1.0/24")!!
        assertTrue(CidrRoute.matches(ip("192.168.1.255"), r))
        assertFalse(CidrRoute.matches(ip("192.168.2.0"), r))
    }

    @Test
    fun `ipv4 prefix-32 exact host only`() {
        val r = CidrRoute.parse("192.168.1.1/32")!!
        assertTrue(CidrRoute.matches(ip("192.168.1.1"), r))
        assertFalse(CidrRoute.matches(ip("192.168.1.2"), r))
    }

    @Test
    fun `ipv4 prefix-0 matches any ipv4`() {
        val r = CidrRoute.parse("0.0.0.0/0")!!
        assertTrue(CidrRoute.matches(ip("8.8.8.8"), r))
        assertTrue(CidrRoute.matches(ip("255.255.255.255"), r))
    }

    // ===== matches：IPv6 =====
    @Test
    fun `ipv6 prefix-32 in-range and out-of-range`() {
        val r = CidrRoute.parse("2400:cb00::/32")!!
        assertTrue(CidrRoute.matches(ip("2400:cb00::2048"), r))
        assertFalse(CidrRoute.matches(ip("2401::1"), r))
    }

    @Test
    fun `ipv6 prefix-128 exact host only`() {
        val r = CidrRoute.parse("2400:cb00::2048/128")!!
        assertTrue(CidrRoute.matches(ip("2400:cb00::2048"), r))
        assertFalse(CidrRoute.matches(ip("2400:cb00::2049"), r))
    }

    @Test
    fun `ipv6 prefix-0 matches any ipv6`() {
        val r = CidrRoute.parse("::/0")!!
        assertTrue(CidrRoute.matches(ip("2400:cb00::1"), r))
    }

    // ===== 跨族：字节长度不同 → false =====
    @Test
    fun `cross-family returns false`() {
        val v4route = CidrRoute.parse("0.0.0.0/0")!!
        assertFalse(CidrRoute.matches(ip("::1"), v4route))
        val v6route = CidrRoute.parse("::/0")!!
        assertFalse(CidrRoute.matches(ip("1.2.3.4"), v6route))
    }
}
