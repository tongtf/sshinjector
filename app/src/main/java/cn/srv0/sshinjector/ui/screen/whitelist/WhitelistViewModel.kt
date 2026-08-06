package cn.srv0.sshinjector.ui.screen.whitelist

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity
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
        private val whitelistDao: WhitelistDao,
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
                whitelistDao.getEnabled().collect { list ->
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
                    whitelistDao.insert(WhitelistAppEntity(packageName = packageName, appName = appName))
                } else {
                    whitelistDao.delete(packageName)
                }
            }
        }

        /**
         * 应用列表缓存是否有效且未过期
         */
        private fun hasFreshCache(): Boolean =
            _appsLoaded.value && _cachedApps.value.isNotEmpty() &&
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
                        pm.getInstalledApplications(flags).map { info ->
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
