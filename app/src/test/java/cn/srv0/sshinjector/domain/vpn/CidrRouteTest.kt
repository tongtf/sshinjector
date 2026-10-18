package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

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

    // ===== matchesRaw ≡ matches（数据面热路径的等价性矩阵，T-A04 护栏）=====

    /**
     * 数据面热路径用 [CidrRoute.matchesRaw]（直接在报文缓冲区里前缀匹配, 零分配）, 判定必须与
     * [CidrRoute.matches] 逐位一致 —— 不一致的直接后果是"该直连的进了隧道 / 该进隧道的直连了"。
     *
     * 矩阵覆盖: IPv4/IPv6、命中/未命中、/0、/32、/128、非 8 倍数位、**跨族**
     * （跨族是真实陷阱: `0.0.0.0/0` 的全字节循环为 0 次, 漏掉长度检查就会命中任意 IPv6 包）。
     */
    @Test
    fun `matchesRaw agrees with matches across a CIDR by address matrix`() {
        val routes =
            listOf(
                "10.0.0.0/8",
                "192.168.1.0/24",
                "192.168.1.1/32",
                "0.0.0.0/0",
                "172.16.0.0/12",
                "100.64.0.0/10",
                "203.0.113.7/31",
                "1.2.3.0/25",
                "2400:cb00::/32",
                "2400:cb00::2048/128",
                "::/0",
                "fd00::/8",
                "2001:db8::/33",
            ).mapNotNull(CidrRoute::parse)

        val addresses =
            listOf(
                "10.0.0.1", "10.255.255.255", "11.0.0.1", "192.168.1.0", "192.168.1.1",
                "192.168.1.2", "192.168.1.255", "0.0.0.0", "255.255.255.255", "172.16.5.5",
                "172.32.0.1", "100.64.0.1", "203.0.113.6", "203.0.113.7", "203.0.113.8",
                "1.2.3.1", "1.2.3.128", "8.8.8.8",
                "2400:cb00::2048", "2400:cb00::2049", "2401::1", "::1", "fd00::abcd", "fe80::1",
                "2001:db8:8000::1", "2001:db8:4000::1", "2001:db8::1",
            ).map(::ip)

        for (route in routes) {
            val size = route.networkBytes.size
            for (address in addresses) {
                val expected = CidrRoute.matches(address, route)
                val bytes = address.address
                val actual =
                    CidrRoute.matchesRaw(ByteBuffer.wrap(bytes), 0, bytes.size, route)
                assertEquals("route=$route address=$address (both $size-byte)", expected, actual)
            }
        }
    }

    /** 偏移语义: 在 buffer 的 [ipOffset] 处读地址, 且**不得**推进 position。 */
    @Test
    fun `matchesRaw reads the absolute offset and keeps the position`() {
        val route = CidrRoute.parse("192.168.1.0/24")!!
        val buf = ByteBuffer.allocate(24)
        // 偏移 4 处放 192.168.1.7 (前缀字节故意留 0, 证明读的是 ipOffset 而不是 0)
        buf.put(4, 192.toByte())
        buf.put(5, 168.toByte())
        buf.put(6, 1.toByte())
        buf.put(7, 7.toByte())
        buf.position(11)

        assertTrue(CidrRoute.matchesRaw(buf, 4, 4, route))
        assertFalse(CidrRoute.matchesRaw(buf, 5, 4, route))
        assertEquals("matchesRaw 不得推进 position", 11, buf.position())
    }

    /** 缓冲区装不下地址 → false (不得越界读, 也不得抛异常)。 */
    @Test
    fun `matchesRaw returns false when the buffer cannot hold the address`() {
        val v6route = CidrRoute.parse("2400:cb00::/32")!!
        val tooShort = ByteBuffer.wrap(ByteArray(8))
        assertFalse(CidrRoute.matchesRaw(tooShort, 0, 16, v6route))

        val v4route = CidrRoute.parse("0.0.0.0/0")!!
        assertFalse(CidrRoute.matchesRaw(ByteBuffer.wrap(ByteArray(8)), -1, 4, v4route))
    }
}
