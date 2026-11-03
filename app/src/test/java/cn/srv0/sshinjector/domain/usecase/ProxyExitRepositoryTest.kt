package cn.srv0.sshinjector.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `countRulesReferencing` (设计 §8.11: 改名/删除出口前提示"有 N 条规则引用它")。
 *
 * 这里只做**文本级**计数, 不做完整解析: 目的是在用户删出口时给出一个可核对的行数,
 * 而不是替代解析器。所以判据必须与解析器一致 —— 只看 `->` 右侧的出口名, 忽略注释与空行。
 */
class ProxyExitRepositoryTest {
    @Test
    fun `counts only lines whose exit reference matches`() {
        val rules =
            """
            example.com -> hk
            *.googlevideo.com -> hk
            example.co.jp -> jp
            """.trimIndent()

        assertEquals(2, ProxyExitRepository.countRulesReferencing(rules, "hk"))
        assertEquals(1, ProxyExitRepository.countRulesReferencing(rules, "jp"))
    }

    @Test
    fun `ignores comments`() {
        val rules =
            """
            # example.com -> hk
            example.com -> hk   # 保留 hk
            """.trimIndent()

        assertEquals("注释里的引用不是真规则", 1, ProxyExitRepository.countRulesReferencing(rules, "hk"))
    }

    @Test
    fun `is case insensitive and trims whitespace`() {
        val rules = "   example.com    ->    HK   "

        assertEquals(1, ProxyExitRepository.countRulesReferencing(rules, " hk "))
    }

    @Test
    fun `does not match a different exit whose name contains the same prefix`() {
        val rules =
            """
            a.example.com -> hk
            b.example.com -> hk2
            """.trimIndent()

        assertEquals("必须整名相等, 不能按前缀匹配", 1, ProxyExitRepository.countRulesReferencing(rules, "hk"))
    }

    @Test
    fun `ignores malformed lines`() {
        val rules =
            """
            hk
            -> hk
            example.com hk
            """.trimIndent()

        assertEquals(0, ProxyExitRepository.countRulesReferencing(rules, "hk"))
    }

    @Test
    fun `blank exit name never matches`() {
        assertEquals(0, ProxyExitRepository.countRulesReferencing("example.com -> hk", "   "))
    }
}
