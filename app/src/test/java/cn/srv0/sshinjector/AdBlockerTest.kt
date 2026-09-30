package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.AdBlocker
import cn.srv0.sshinjector.domain.vpn.GfwListMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdBlocker 匹配逻辑单测 (纯 JVM, 无 Android 依赖):
 * matcher 通过 setMatcher 注入, 验证精确 / 后缀 / 通配 / 例外规则与 IP 字面量放行。
 */
class AdBlockerTest {
    private fun blocker(
        rules: String,
        enabled: Boolean = true,
    ): AdBlocker {
        val adb = AdBlocker()
        adb.setEnabled(enabled)
        adb.setMatcher(GfwListMatcher.parse(rules))
        return adb
    }

    @Test
    fun `命中精确域名与所有子域`() {
        val b = blocker("ad.qq.com\nlog.snssdk.com")
        assertTrue(b.isBlocked("ad.qq.com"))
        assertTrue(b.isBlocked("ads.ad.qq.com")) // 后缀匹配子域
        assertTrue(b.isBlocked("log.snssdk.com"))
    }

    @Test
    fun `通配与例外规则`() {
        val b = blocker("||taobao.com^\n@@safe.taobao.com")
        assertTrue(b.isBlocked("www.taobao.com"))
        assertTrue(b.isBlocked("m.taobao.com"))
        assertFalse(b.isBlocked("safe.taobao.com")) // @@ 例外优先于命中规则
    }

    @Test
    fun `非后缀与父域不命中`() {
        val b = blocker("ad.qq.com")
        assertFalse(b.isBlocked("qq.com")) // 规则是子域, 父域不匹配
        assertFalse(b.isBlocked("notad.qq.com")) // 非后缀
    }

    @Test
    fun `IP 字面量与空输入放行`() {
        val b = blocker("ad.qq.com")
        assertFalse(b.isBlocked("8.8.8.8"))
        assertFalse(b.isBlocked("192.168.1.1"))
        assertFalse(b.isBlocked(""))
        assertFalse(b.isBlocked("   "))
    }

    @Test
    fun `关闭总开关时全部放行`() {
        val b = blocker("ad.qq.com", enabled = false)
        assertFalse(b.isBlocked("ad.qq.com"))
    }

    @Test
    fun `大小写不敏感与尾点归一`() {
        val b = blocker("ad.qq.com")
        assertTrue(b.isBlocked("AD.QQ.COM.")) // 大写 + 尾点, isBlocked 内部归一后匹配
    }

    @Test
    fun `关闭时反向 DNS 与 arpa 不命中`() {
        val b = blocker("ad.qq.com")
        assertFalse(b.isBlocked("1.168.192.in-addr.arpa")) // 反向解析放行
    }
}
