package cn.srv0.sshinjector.domain.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一键路由的**行级**文本改写。
 *
 * 每条用例都在锁"用户手写的内容不能被抹掉": 注释/空行/高级行/排版原样保留,
 * 只删插目标行, 且插入位置必须满足优先级 (应用最先, 域名最长后缀优先, default 最后)。
 */
class RoutingRuleTextTest {
    @Test
    fun `app upsert keeps the leading comment block in place`() {
        val text = "# 应用分流\n\napp:com.example.bank -> hk\nexample.com -> jp\ndefault -> hk"

        assertEquals(
            "# 应用分流\n\napp:com.example.bank -> jp\nexample.com -> jp\ndefault -> hk",
            RoutingRuleText.upsertApp(text, "com.example.bank", "jp"),
        )
    }

    @Test
    fun `app upsert removes the port variant and moves the rule to the top`() {
        val text = "example.com -> hk\napp:com.example.bank:443 -> hk\ndefault -> hk"

        assertEquals(
            "app:com.example.bank -> jp\nexample.com -> hk\ndefault -> hk",
            RoutingRuleText.upsertApp(text, "com.example.bank", "jp"),
        )
    }

    @Test
    fun `app upsert does not touch other packages or comments`() {
        val text = "# app:com.example.bank -> hk\napp:com.example.other -> hk\ndefault -> hk"

        assertEquals(
            "# app:com.example.bank -> hk\napp:com.example.bank -> jp\napp:com.example.other -> hk\ndefault -> hk",
            RoutingRuleText.upsertApp(text, "com.example.bank", "jp"),
        )
    }

    @Test
    fun `domain upsert inserts before the first not longer suffix`() {
        val text = "a.example.com -> hk\nexample.com -> hk\ndefault -> hk"

        assertEquals(
            "b.example.com -> jp\na.example.com -> hk\nexample.com -> hk\ndefault -> hk",
            RoutingRuleText.upsertDomain(text, "b.example.com", "jp"),
        )
    }

    @Test
    fun `domain upsert replaces same suffix and lands before default`() {
        val text = "a.example.com -> hk\nexample.com -> hk\ndefault -> hk"

        assertEquals(
            "a.example.com -> hk\nexample.com -> jp\ndefault -> hk",
            RoutingRuleText.upsertDomain(text, "example.com", "jp"),
        )
    }

    @Test
    fun `domain upsert removes wildcard and port variants of the same suffix`() {
        val text = "*.example.com -> hk\nexample.com:443 -> hk\nsub.example.com -> jp\ndefault -> hk"

        assertEquals(
            "sub.example.com -> jp\nexample.com -> us\ndefault -> hk",
            RoutingRuleText.upsertDomain(text, "example.com", "us"),
        )
    }

    @Test
    fun `domain upsert appends when there is no domain rule and no default`() {
        val text = "a.example.com -> hk"

        assertEquals(
            "a.example.com -> hk\nexample.com -> jp",
            RoutingRuleText.upsertDomain(text, "example.com", "jp"),
        )
    }

    @Test
    fun `domain upsert goes after app rules and before default`() {
        val text = "app:com.example.bank -> hk\ndefault -> hk"

        assertEquals(
            "app:com.example.bank -> hk\nexample.com -> jp\ndefault -> hk",
            RoutingRuleText.upsertDomain(text, "example.com", "jp"),
        )
    }

    @Test
    fun `comments and trailing newline survive a domain upsert`() {
        val text = "# ---- 域名 ----\n\n# note\nexample.com -> hk # 保留\ndefault -> hk\n"

        val result = RoutingRuleText.upsertDomain(text, "api.example.com", "jp")

        assertEquals(
            "# ---- 域名 ----\n\n# note\napi.example.com -> jp\nexample.com -> hk # 保留\ndefault -> hk\n",
            result,
        )
        assertEquals("结尾换行必须原样保留", '\n', result.last())
    }

    @Test
    fun `advanced lines are preserved untouched`() {
        val text = "203.0.113.0/24 -> hk\napp:com.example.bank:443 -> hk\ndefault -> hk"

        assertEquals(
            "203.0.113.0/24 -> hk\napp:com.example.bank:443 -> hk\nexample.com -> jp\ndefault -> hk",
            RoutingRuleText.upsertDomain(text, "example.com", "jp"),
        )
    }

    @Test
    fun `invalid input is returned byte for byte`() {
        val text = "example.com -> hk\ndefault -> hk"

        assertEquals(text, RoutingRuleText.upsertDomain(text, "not a domain", "jp"))
        assertEquals(text, RoutingRuleText.upsertDomain(text, "example.com", "not an exit"))
        assertEquals(text, RoutingRuleText.upsertApp(text, "not-a-package", "jp"))
        assertEquals(text, RoutingRuleText.upsertApp(text, "com.example.bank", "bad exit!"))
    }

    @Test
    fun `empty text creates the first rule of each kind`() {
        assertEquals("example.com -> hk", RoutingRuleText.upsertDomain("", "example.com", "hk"))
        assertEquals(
            "app:com.example.bank -> jp",
            RoutingRuleText.upsertApp("", "com.example.bank", "jp"),
        )
    }

    @Test
    fun `comment only text gets the rule after the comment block`() {
        assertEquals(
            "# only\nexample.com -> hk\n",
            RoutingRuleText.upsertDomain("# only\n", "example.com", "hk"),
        )
    }
}
