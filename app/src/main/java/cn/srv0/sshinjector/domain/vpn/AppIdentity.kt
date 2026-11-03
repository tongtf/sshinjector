package cn.srv0.sshinjector.domain.vpn

import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * uid → 包名 的会话级映射 (设计 §8.5.1)。
 *
 * 为什么按包名而不是 uid 写规则: uid 是**设备事实** (重装 / 多用户 / 工作资料都会变), 写进规则
 * 会在重装后静默失效; 包名跨设备稳定, 也是用户能看懂的东西。
 *
 * 三条纪律:
 * 1. **不写负缓存** —— 应用刚安装/刚启动时 `getNameForUid` 可能返回 null, 写负缓存会让这条规则
 *    在整个会话里永久失效 (与假 IP 归零宽限期同类教训);
 * 2. 只在 `VpnController.connect` 清一次 (会话中途装/卸应用不影响已有规则);
 * 3. **绝不参与隧道/直连判定** —— 结果只喂 `RouteTarget.pkg`, 拿不到 uid 时应用规则只是不命中。
 *
 * 注入的是纯函数 [lookup]: 生产用 `PackageManager`, 单测用假表 (无 Robolectric)。
 */
@Singleton
class AppIdentity(
    private val lookup: (Int) -> String?,
) {
    private val cache = ConcurrentHashMap<Int, String>()

    /** uid → 包名; 查不到 (uid 已消失 / 无权限 / 无归属) 返回 null。共享 uid 返回组内首个包名。 */
    fun packageOf(uid: Int): String? {
        if (uid < 0) return null
        cache[uid]?.let { return it }
        val resolved = lookup(uid) ?: return null
        cache[uid] = resolved
        return resolved
    }

    /** 会话开始清一次 (重装/多用户切换后 uid 会变)。 */
    fun clear() {
        cache.clear()
    }

    /** 只做展示: 命中缓存条数 (进数据面快照的 `应用` 行)。 */
    fun diagnostics(): String = "应用映射 ${cache.size} 条"

    companion object {
        /** 生产实现: `PackageManager.getNameForUid` (反射/多用户异常一律当无归属)。 */
        fun fromPackageManager(packageManager: PackageManager): AppIdentity =
            AppIdentity { uid ->
                runCatching { packageManager.getNameForUid(uid) }.getOrNull()
            }
    }
}
