package cn.srv0.sshinjector.ui.screen.whitelist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已安装应用来源 (白名单页与多出口应用选择器共用)。
 *
 * 抽出来的原因: 两次 `PackageManager.getInstalledApplications` 各有 5 分钟缓存时, 两个页面会
 * 各持一份快照 —— 用户在白名单页刚装的 app, 到多出口选择器里就找不到 (或反之)。
 * 单例 + 单缓存保证两处看到的是同一份设备事实。
 *
 * 缓存 5 分钟: 拉全量应用列表在低端机上约 200~500ms, 每次进页面都刷会明显卡顿;
 * 而 5 分钟内装上/卸掉应用属于罕见场景, 需要时用户可显式刷新 ([load] 传 `forceRefresh`)。
 */
@Singleton
class InstalledAppsSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        @Volatile
        private var cached: List<InstalledApp> = emptyList()

        @Volatile
        private var loadedAt: Long = 0L

        suspend fun load(forceRefresh: Boolean = false): List<InstalledApp> {
            if (!forceRefresh && cached.isNotEmpty() && System.currentTimeMillis() - loadedAt < CACHE_VALIDITY_MS) {
                return cached
            }
            val apps =
                withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    val flags = PackageManager.ApplicationInfoFlags.of(0)
                    pm
                        .getInstalledApplications(flags)
                        .map { info ->
                            InstalledApp(
                                packageName = info.packageName,
                                name = pm.getApplicationLabel(info).toString(),
                                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                uid = info.uid,
                            )
                        }.sortedBy { it.name.lowercase() }
                }
            cached = apps
            loadedAt = System.currentTimeMillis()
            return apps
        }

        companion object {
            private const val CACHE_VALIDITY_MS = 5 * 60 * 1000L
        }
    }
