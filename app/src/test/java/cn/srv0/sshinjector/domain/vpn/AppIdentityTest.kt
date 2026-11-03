package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * uid → 包名 的会话级映射 (设计 §8.5.1)。
 *
 * 关键不变量是**不写负缓存**: 应用刚安装/刚启动时 `getNameForUid` 可能返回 null, 负缓存会让
 * 该应用的路由规则在整个会话里永久失效 —— 症状是"规则配好了, 只有这个 app 不生效",
 * 而且日志里没有任何痕迹。
 */
class AppIdentityTest {
    @Test
    fun `resolves a uid and caches the hit`() {
        var calls = 0
        val identity =
            AppIdentity { uid ->
                calls++
                if (uid == 10100) "com.example.bank" else null
            }

        assertEquals("com.example.bank", identity.packageOf(10100))
        assertEquals("com.example.bank", identity.packageOf(10100))
        assertEquals("命中必须缓存: 每次新建连接都查 PackageManager 会拖慢 SYN 路径", 1, calls)
    }

    @Test
    fun `a miss is not cached`() {
        var resolved = false
        val identity =
            AppIdentity { uid ->
                // 第一次模拟"应用刚启动, 系统还查不到"
                if (resolved) "com.example.late" else null
            }

        assertNull(identity.packageOf(10123))
        resolved = true
        assertEquals("负缓存会让这条规则整个会话永久失效", "com.example.late", identity.packageOf(10123))
    }

    @Test
    fun `negative uid is never looked up`() {
        var calls = 0
        val identity =
            AppIdentity { _ ->
                calls++
                "com.example.x"
            }

        assertNull(identity.packageOf(-1))
        assertNull(identity.packageOf(-1000))
        assertEquals("NO_UID 直接返回, 不消耗查询", 0, calls)
    }

    @Test
    fun `clear drops the mapping so a reinstall is re-resolved`() {
        var pkg: String? = "com.example.old"
        val identity = AppIdentity { pkg }

        assertEquals("com.example.old", identity.packageOf(10101))
        pkg = "com.example.new"
        assertEquals("缓存还在, 会话中途不重新解析", "com.example.old", identity.packageOf(10101))

        identity.clear()
        assertEquals("重装/多用户切换后 uid 会指向新包名", "com.example.new", identity.packageOf(10101))
    }

    @Test
    fun `diagnostics reports the cache size`() {
        val identity = AppIdentity { uid -> "com.example.$uid" }
        identity.packageOf(1)
        identity.packageOf(2)

        assertEquals("应用映射 2 条", identity.diagnostics())
    }
}
