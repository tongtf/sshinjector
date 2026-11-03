package cn.srv0.sshinjector.domain.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 改名/删除出口时的规则文本改写。
 *
 * 这些用例锁的是"用户手写的内容不能被抹掉": 行序、注释、缩进、`hk` 与 `hk2` 的区分。
 * 任何一条出错, 用户看到的都是"规则还在, 只是不生效"。
 */
class ExitRuleTextTest {
    @Test
    fun `rename rewrites only the exit reference`() {
        val text = "example.com -> hk\napp:com.example.bank -> jp"

        assertEquals(
            "example.com -> jp\napp:com.example.bank -> jp",
            ExitRuleText.renameExit(text, "hk", "jp"),
        )
    }

    @Test
    fun `rename keeps comments spacing and order`() {
        val text = "# 应用分流\nexample.com   ->   hk   # 保留注释\n"

        assertEquals(
            "结尾换行也必须原样保留\n",
            "# 应用分流\nexample.com   ->   jp   # 保留注释\n",
            ExitRuleText.renameExit(text, "hk", "jp"),
        )
    }

    @Test
    fun `rename is case insensitive and does not touch a longer name`() {
        val text = "a.example.com -> HK\nb.example.com -> hk2"

        assertEquals(
            "a.example.com -> jp\nb.example.com -> hk2",
            ExitRuleText.renameExit(text, "hk", "jp"),
        )
    }

    @Test
    fun `rename without references returns the text unchanged`() {
        val text = "example.com -> hk\ndefault -> hk"

        assertEquals("没有引用就不该产生任何字节变化", text, ExitRuleText.renameExit(text, "jp", "us"))
    }

    @Test
    fun `remove deletes only the matching lines and counts them`() {
        val text =
            """
            example.com -> hk
            *.googlevideo.com -> hk
            example.co.jp -> jp
            """.trimIndent()

        val (result, removed) = ExitRuleText.removeExitRules(text, "hk")

        assertEquals(2, removed)
        assertEquals("example.co.jp -> jp", result)
    }

    @Test
    fun `remove ignores comments and prefix collisions`() {
        val text =
            """
            # example.com -> hk
            a.example.com -> hk2
            b.example.com -> hk
            """.trimIndent()

        val (result, removed) = ExitRuleText.removeExitRules(text, "hk")

        assertEquals(1, removed)
        assertEquals("# example.com -> hk\na.example.com -> hk2", result)
    }

    @Test
    fun `prepend puts the app rule before existing rules so it wins`() {
        val text = "example.com -> hk\ndefault -> hk\n"

        assertEquals(
            "app:com.example.bank -> jp\nexample.com -> hk\ndefault -> hk\n",
            ExitRuleText.prependAppRule(text, "com.example.bank", "jp"),
        )
    }

    @Test
    fun `prepend keeps default as the last line`() {
        val text = "default -> hk"

        val result = ExitRuleText.prependAppRule(text, "com.example.bank", "jp")

        assertEquals("app:com.example.bank -> jp\ndefault -> hk", result)
        assertEquals("default 必须仍在最后, 否则解析时整行被丢", "default -> hk", result.lines().last())
    }

    @Test
    fun `prepend goes after a leading comment block`() {
        val text = "# ---- 应用分流 ----\n\nexample.com -> hk\n"

        assertEquals(
            "# ---- 应用分流 ----\n\napp:com.example.bank -> jp\nexample.com -> hk\n",
            ExitRuleText.prependAppRule(text, "com.example.bank", "jp"),
        )
    }

    @Test
    fun `prepend on empty text creates the first rule`() {
        assertEquals(
            "app:com.example.bank -> jp\n",
            ExitRuleText.prependAppRule("", "com.example.bank", "jp"),
        )
    }

    @Test
    fun `prepend is idempotent`() {
        val once = ExitRuleText.prependAppRule("example.com -> hk", "com.example.bank", "jp")

        assertEquals(once, ExitRuleText.prependAppRule(once, "com.example.bank", "jp"))
    }

    @Test
    fun `remove without matches changes nothing`() {
        val text = "example.com -> jp"
        val (result, removed) = ExitRuleText.removeExitRules(text, "hk")

        assertEquals(0, removed)
        assertEquals(text, result)
    }
}
