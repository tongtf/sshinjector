package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `effectiveWhitelist` (F2) 的三条不变量:
 * ① 自身包名必须剔除 (否则本应用流量进 TUN 自环);
 * ② 不可见/已卸载包必须剔除 (否则 `addAllowedApplication` 抛 `NameNotFoundException`,
 *   而 `allowedPackages.size` 仍判非空 → 加了全量路由却一个应用都没放行 = 整机断网);
 * ③ 过滤后为空时返回空列表 (调用方据此退化成"全部直连")。
 */
class WhitelistFilterTest {
    @Test
    fun `own package is excluded`() {
        val result =
            effectiveWhitelist(
                packages = listOf("com.own", "com.a", "com.b"),
                ownPackage = "com.own",
                isInstalled = { true },
            )

        assertEquals(listOf("com.a", "com.b"), result)
    }

    @Test
    fun `packages not visible to the package manager are excluded`() {
        val installed = setOf("com.a", "com.c")
        val result =
            effectiveWhitelist(
                packages = listOf("com.a", "com.gone", "com.c"),
                ownPackage = "com.own",
                isInstalled = { it in installed },
            )

        assertEquals(listOf("com.a", "com.c"), result)
    }

    @Test
    fun `all filtered out returns empty so callers degrade to direct`() {
        val result =
            effectiveWhitelist(
                packages = listOf("com.own", "com.gone", "com.a"),
                ownPackage = "com.own",
                isInstalled = { false },
            )

        assertEquals(emptyList<String>(), result)
    }
}
