package cn.srv0.sshinjector.ui.screen.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * uid → 可读应用名 (④ 按应用归属的展示层)。
 *
 * 放在 UI 侧而不是 [cn.srv0.sshinjector.domain.vpn.AppUidResolver] 里: 这是**展示**语义, 域名统计的
 * 数据面只认识 uid 这个机器事实。结果**缓存** —— 一次刷新的 distinct uid 数量级 = 用户真用过的应用数
 * (几十), `PackageManager` 查询每次 ~ms, 缓存后重复刷新零成本。
 *
 * 拿不到名字时退化成 `uid N` (不隐藏事实): 系统共享 uid / 已卸载应用 / 被限制查询时都会走到这里。
 */
@Singleton
class AppUidLabels
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val cache = ConcurrentHashMap<Int, String>()

        /** 查 uid 的可读名 (命中缓存直接返回; 绝不抛)。 */
        fun label(uid: Int): String {
            cache[uid]?.let { return it }
            val resolved = resolve(uid)
            cache[uid] = resolved
            return resolved
        }

        private fun resolve(uid: Int): String {
            if (uid < 0) return ""
            val fallback = "uid $uid"
            val pm = context.packageManager
            val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: return fallback
            val label =
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrNull()
            return if (label.isNullOrBlank()) pkg else label
        }
    }
