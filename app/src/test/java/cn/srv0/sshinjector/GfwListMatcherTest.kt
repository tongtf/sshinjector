package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.GfwListMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gfwlist 解析与匹配逻辑的单元测试
 */
class GfwListMatcherTest {
    private val sampleList =
        """
        [AutoProxy 0.2.9]
        ! comment line, should be ignored
        |http://example.net/path/to/file
        .example.com
        ||google.com^
        ||youtube.com
        *.github.com
        @@||secure.example.com
        @@.cn
        8.8.8.8
        1.2.3.4:8080
        /^https?:\/\/example\.org/
        example.org
        """.trimIndent()

    private fun parse(): GfwListMatcher = GfwListMatcher.parse(sampleList)

    @Test
    fun `parse 忽略注释头部正则和 IP 行`() {
        val matcher = parse()
        assertEquals(8, matcher.ruleCount)
    }

    @Test
    fun `双竖线规则匹配域名及其子域名`() {
        val matcher = parse()
        assertTrue(matcher.matches("google.com"))
        assertTrue(matcher.matches("www.google.com"))
        assertTrue(matcher.matches("a.b.google.com"))
        assertFalse(matcher.matches("notgoogle.com"))
        assertFalse(matcher.matches("google.com.evil.io"))
    }

    @Test
    fun `点前缀规则匹配子域名`() {
        val matcher = parse()
        assertTrue(matcher.matches("www.example.com"))
        assertTrue(matcher.matches("example.com"))
        assertFalse(matcher.matches("example.com.evil.io"))
    }

    @Test
    fun `裸域名规则匹配`() {
        val matcher = parse()
        assertTrue(matcher.matches("example.org"))
        assertTrue(matcher.matches("sub.example.org"))
    }

    @Test
    fun `http URL 规则提取 host`() {
        val matcher = parse()
        assertTrue(matcher.matches("example.net"))
        assertTrue(matcher.matches("www.example.net"))
    }

    @Test
    fun `子域名例外规则优先于命中规则`() {
        val matcher = parse()
        assertTrue(matcher.matches("example.com"))
        assertFalse(matcher.matches("secure.example.com"))
        assertFalse(matcher.matches("deep.secure.example.com"))
    }

    @Test
    fun `后缀例外规则忽略 cn 结尾域名`() {
        val matcher = parse()
        assertTrue(matcher.matches("example.net"))
        assertFalse(matcher.matches("example.cn"))
        assertFalse(matcher.matches("example.com.cn"))
        assertFalse(matcher.matches("www.example.cn"))
    }

    @Test
    fun `通配符规则匹配裸域名及所有子域名`() {
        val matcher = parse()
        assertTrue(matcher.matches("github.com"))
        assertTrue(matcher.matches("sub.github.com"))
        assertTrue(matcher.matches("deep.sub.github.com"))
        assertFalse(matcher.matches("notgithub.com"))
    }

    @Test
    fun `匹配忽略大小写和末尾点`() {
        val matcher = parse()
        assertTrue(matcher.matches("GOOGLE.COM."))
        assertTrue(matcher.matches("WWW.Google.COM"))
    }

    @Test
    fun `空或空白 host 返回 false`() {
        val matcher = parse()
        assertFalse(matcher.matches(""))
        assertFalse(matcher.matches("   "))
        assertFalse(matcher.matches("."))
    }

    @Test
    fun `无规则时全不命中`() {
        val matcher = GfwListMatcher.parse("! nothing useful\n[AutoProxy]\n")
        assertEquals(0, matcher.ruleCount)
        assertFalse(matcher.matches("google.com"))
    }
}
