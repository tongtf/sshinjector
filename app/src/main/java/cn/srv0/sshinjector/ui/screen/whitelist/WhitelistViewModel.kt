package cn.srv0.sshinjector.ui.screen.whitelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.domain.model.WhitelistApp
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WhitelistViewModel
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val installedAppsSource: InstalledAppsSource,
    ) : ViewModel() {
        private val _enabledPackages = MutableStateFlow<Set<String>>(emptySet())
        val enabledPackages: StateFlow<Set<String>> = _enabledPackages.asStateFlow()

        // 应用列表缓存
        private val _cachedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
        val cachedApps: StateFlow<List<InstalledApp>> = _cachedApps.asStateFlow()

        private val _appsLoaded = MutableStateFlow(false)
        val appsLoaded: StateFlow<Boolean> = _appsLoaded.asStateFlow()

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
         * **必须一次事务写完**: 逐条写会让 `enabledWhitelistFlow` 逐条发射, 而
         * `SshVpnService` 的观察者是 `collectLatest { rebuildVpnInterfaceInternal() }` ——
         * 全选几百个应用就会重建几百次 TUN (并与 disconnect 抢 `connectMutex`)。
         */
        fun setPackagesEnabled(
            apps: List<InstalledApp>,
            enabled: Boolean,
        ) {
            if (apps.isEmpty()) return
            viewModelScope.launch {
                serverRepository.setWhitelistPackages(
                    apps.map { WhitelistApp(packageName = it.packageName, appName = it.name) },
                    enabled = enabled,
                )
            }
        }

        /**
         * 加载应用列表 (缓存与多出口应用选择器共用, 见 [InstalledAppsSource])。
         * @param forceRefresh 是否强制刷新缓存
         */
        fun loadApps(forceRefresh: Boolean = false) {
            viewModelScope.launch {
                _cachedApps.value = installedAppsSource.load(forceRefresh)
                _appsLoaded.value = true
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
