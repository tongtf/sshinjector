package cn.srv0.sshinjector.ui.screen.whitelist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.domain.model.WhitelistApp
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WhitelistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        private val _enabledPackages = MutableStateFlow<Set<String>>(emptySet())
        val enabledPackages: StateFlow<Set<String>> = _enabledPackages.asStateFlow()

        // 应用列表缓存
        private val _cachedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
        val cachedApps: StateFlow<List<InstalledApp>> = _cachedApps.asStateFlow()

        private val _appsLoaded = MutableStateFlow(false)
        val appsLoaded: StateFlow<Boolean> = _appsLoaded.asStateFlow()

        private var lastCacheTime = 0L
        private val cacheValidityMs = 5 * 60 * 1000L // 缓存有效期 5 分钟

        init {
            viewModelScope.launch {
                serverRepository.enabledWhitelistFlow.collect { list ->
                    _enabledPackages.value = list.map { it.packageName }.toSet()
                }
            }
        }

        fun togglePackage(
            packageName: String,
            appName: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                if (enabled) {
                    serverRepository.addToWhitelist(WhitelistApp(packageName = packageName, appName = appName))
                } else {
                    serverRepository.removeFromWhitelist(packageName)
                }
            }
        }

        /**
         * 对「当前过滤集」批量启用/取消 (T-13 全选/反选)。
         *
         * 复用单包路径 ([addToWhitelist]/[removeFromWhitelist]) 逐条持久化: 白名单变化由
         * [SshVpnService] 的 `enabledWhitelistFlow` 观察者触发同一套 VPN 接口重建, 无需另发信号。
         */
        fun setPackagesEnabled(
            apps: List<InstalledApp>,
            enabled: Boolean,
        ) {
            if (apps.isEmpty()) return
            viewModelScope.launch {
                for (app in apps) {
                    if (enabled) {
                        serverRepository.addToWhitelist(
                            WhitelistApp(packageName = app.packageName, appName = app.name),
                        )
                    } else {
                        serverRepository.removeFromWhitelist(app.packageName)
                    }
                }
            }
        }

        /**
         * 应用列表缓存是否有效且未过期
         */
        private fun hasFreshCache(): Boolean =
            _appsLoaded.value &&
                _cachedApps.value.isNotEmpty() &&
                (System.currentTimeMillis() - lastCacheTime) < cacheValidityMs

        /**
         * 加载应用列表（带缓存）
         * @param forceRefresh 是否强制刷新缓存
         */
        fun loadApps(forceRefresh: Boolean = false) {
            // 如果有缓存且未过期，且不是强制刷新，直接使用缓存
            if (!forceRefresh && hasFreshCache()) {
                return
            }

            viewModelScope.launch {
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
                                )
                            }.sortedBy { it.name.lowercase() }
                    }
                _cachedApps.value = apps
                _appsLoaded.value = true
                lastCacheTime = System.currentTimeMillis()
            }
        }

        /**
         * 强制刷新应用列表
         */
        fun refreshApps() {
            loadApps(forceRefresh = true)
        }
    }

// ===== 纯过滤/选择逻辑 (无 Android 依赖, 便于 JVM 单测) =====

/** 滤器: 全部应用 */
internal const val WHITELIST_FILTER_ALL = 0

/** 滤器: 仅已选 (白名单内) 应用 */
internal const val WHITELIST_FILTER_SELECTED = 1

/** 滤器: 仅系统应用 */
internal const val WHITELIST_FILTER_SYSTEM = 2

/**
 * 应用列表过滤 (纯函数)。
 *
 * T-19: `hideSystemApps` 只作用于「全部/已选」两个滤器 —— 选「系统应用」时必须能看到系统项,
 * 否则默认 `hideSystemApps=true` 与 `filterMode==2` 互斥, 列表恒空、系统应用永远无法勾选。
 */
internal fun filterWhitelistApps(
    allApps: List<InstalledApp>,
    searchQuery: String,
    hideSystemApps: Boolean,
    filterMode: Int,
    enabledPackages: Set<String>,
): List<InstalledApp> {
    var list =
        if (searchQuery.isBlank()) {
            allApps
        } else {
            allApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    if (hideSystemApps && filterMode != WHITELIST_FILTER_SYSTEM) list = list.filter { !it.isSystem }
    when (filterMode) {
        WHITELIST_FILTER_SELECTED -> list = list.filter { it.packageName in enabledPackages }
        WHITELIST_FILTER_SYSTEM -> list = list.filter { it.isSystem }
    }
    return list
}

/**
 * 「全选」按钮状态: 由**当前过滤集**与已选集合派生 (T-13)。
 * 过滤集为空时恒为 false —— 全选/反选对空集无意义。
 */
internal fun areAllSelected(
    filtered: List<InstalledApp>,
    enabledPackages: Set<String>,
): Boolean = filtered.isNotEmpty() && filtered.all { it.packageName in enabledPackages }
