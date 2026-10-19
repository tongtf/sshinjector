package cn.srv0.sshinjector.data.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端解析的命令构造与输出解析 (纯逻辑)。
 *
 * 重点是**命令注入防护**: 域名来自 DNS 应答 (攻击者可控) 而它要进服务器 shell, 字符白名单是唯一
 * 屏障 —— 任何 shell 元字符能过, 就等于把远端命令执行权交给了 DNS 应答方。
 */
class RemoteLookupTest {
    @Test
    fun `host whitelist accepts real domains`() {
        val accepted =
            listOf(
                "example.com",
                "a.b.c.example.co.uk",
                "host-1.example.com",
                "xn--ngstr-lra8j.com", // punycode (Play CDN)
                "1.2.3.4", // IP 字面量也能过 (没有域名语义, 但字符集合法)
                "a".repeat(63) + ".com",
            )
        for (host in accepted) {
            assertTrue(host, RemoteLookup.isValidHost(host))
        }
    }

    @Test
    fun `host whitelist rejects shell metacharacters`() {
        val rejected =
            listOf(
                "",
                " ",
                "exa mple.com",
                "example.com;id",
                "example.com|id",
                "example.com&&id",
                "example.com`id`",
                "example.com\$(id)",
                "example.com>out",
                "example.com\nid",
                "example.com\tid",
                "\$(whoami).example.com",
            )
        for (host in rejected) {
            assertFalse(host, RemoteLookup.isValidHost(host))
        }
    }

    @Test
    fun `host whitelist rejects malformed names and over-long input`() {
        val malformed = listOf("-x.com", ".example.com", "example..com", "example.com.", "x-.com")
        for (host in malformed) {
            assertFalse(host, RemoteLookup.isValidHost(host))
        }
        // 单标签 64 字符超限; 总长 254 超限
        assertFalse(RemoteLookup.isValidHost("a".repeat(64) + ".com"))
        assertFalse(RemoteLookup.isValidHost("a".repeat(254)))
    }

    @Test
    fun `command is built from a validated host`() {
        assertEquals("getent ahosts example.com", RemoteLookup.command("example.com"))
    }

    @Test
    fun `parse dedupes the socket-type lines of the same address`() {
        val raw =
            """
            142.250.66.78   STREAM example.com
            142.250.66.78   DGRAM
            142.250.66.78   RAW
            2a00:1450:4010:c0e::200e STREAM
            2a00:1450:4010:c0e::200e DGRAM
            """.trimIndent()
        assertEquals(
            listOf("142.250.66.78", "2a00:1450:4010:c0e::200e"),
            RemoteLookup.parseGetent(raw),
        )
    }

    @Test
    fun `parse keeps the server's order and does not promote v4`() {
        // 顺序 = 服务器 getaddrinfo 的偏好顺序 (即 SSH 通道实际会拨的那个); 重排会让展示的
        // "远端视角 IP" 与真实出口不一致。
        val raw =
            "2a00:1450:4010:c0e::200e STREAM\n" +
                "example.com has address 1.2.3.4\n" +
                "142.250.66.78 STREAM\n"
        assertEquals(
            listOf("2a00:1450:4010:c0e::200e", "142.250.66.78"),
            RemoteLookup.parseGetent(raw),
        )
    }

    @Test
    fun `parse ignores empty and non-address lines and caps the result`() {
        assertTrue(RemoteLookup.parseGetent("").isEmpty())
        assertTrue(RemoteLookup.parseGetent("\n  \n\t\n").isEmpty())
        assertTrue(RemoteLookup.parseGetent("getent: command not found\n").isEmpty())

        val many = (1..20).joinToString("\n") { "$it.$it.$it.$it STREAM x" }
        assertEquals(RemoteLookup.MAX_IPS, RemoteLookup.parseGetent(many).size)
    }

    @Test
    fun `ip literal detection`() {
        assertTrue(RemoteLookup.isIpLiteral("1.2.3.4"))
        assertTrue(RemoteLookup.isIpLiteral("2a00:1450:4010:c0e::200e"))
        assertTrue(RemoteLookup.isIpLiteral("::1"))
        assertFalse(RemoteLookup.isIpLiteral(""))
        assertFalse(RemoteLookup.isIpLiteral("example.com"))
        assertFalse(RemoteLookup.isIpLiteral("1.2.3"))
        assertFalse(RemoteLookup.isIpLiteral("1.2.3.4.5"))
        assertFalse(RemoteLookup.isIpLiteral("1.2.3.4a"))
    }
}
