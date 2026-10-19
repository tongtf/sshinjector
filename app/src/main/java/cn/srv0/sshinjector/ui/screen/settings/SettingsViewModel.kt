package cn.srv0.sshinjector.ui.screen.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.DomainStatsStore
import cn.srv0.sshinjector.domain.vpn.StatsSuggestionStore
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import cn.srv0.sshinjector.vpn.SshVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsDataStore: SettingsDataStore,
        private val suggestionStore: StatsSuggestionStore,
        private val domainStatsStore: DomainStatsStore,
    ) : ViewModel() {
        val autoConnect: StateFlow<Boolean> =
            settingsDataStore.autoConnect
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)
        val biometricUnlock: StateFlow<Boolean> =
            settingsDataStore.biometricUnlock
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        // 全局网络设置: null = 未设置 → 运行时回退 per-server 字段, UI 显示默认值
        val mtu: StateFlow<Int?> =
            settingsDataStore.mtu
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        val keepAlive: StateFlow<Int?> =
            settingsDataStore.keepAlive
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        val enableIPv6: StateFlow<Boolean?> =
            settingsDataStore.enableIPv6
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        val dnsMode: StateFlow<Int> =
            settingsDataStore.dnsMode
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

        // 广告过滤总开关, 默认开启
        val adBlockEnabled: StateFlow<Boolean> =
            settingsDataStore.adBlockEnabled
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)
        val language: StateFlow<String> =
            settingsDataStore.language
                .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

        /** null = 未设置 → 探测走默认端点 (http://www.gstatic.com/generate_204, 服务端出口需可达)。 */
        val probeUrl: StateFlow<String?> =
            settingsDataStore.probeUrl
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** 域名统计页的「待确认建议」条数 —— 设置页入口徽标 (0 时不显示)。 */
        val statsSuggestionCount: StateFlow<Int> =
            suggestionStore.state
                .map { it.size }
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

        /**
         * 已采集域名数 —— 设置页入口 subtitle (设计 §9.1 `已记录 N 个域名`)。
         *
         * 刻意**不**让 `DomainStatsStore` 推流: 采集发生在 `closeTcpConnection` 里, 从数据面通知 UI 是
         * 仓库明令禁止的反模式 (每连接一次全量拷贝 + 全量 recompose)。这里只在页面可见时每
         * 30s 拉一次 `size()` (O(1)), 与 store 自身的淘汰巡检 [DomainStatsStore.TRIM_INTERVAL_MS] 同频。
         */
        val domainStatsCount: StateFlow<Int> =
            flow {
                while (true) {
                    emit(domainStatsStore.size())
                    delay(DOMAIN_STATS_POLL_MS)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                domainStatsStore.size(),
            )

        fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setAutoConnect(enabled) }

        fun setBiometricUnlock(enabled: Boolean) {
            viewModelScope.launch { settingsDataStore.setBiometricUnlock(enabled) }
        }

        fun setMtu(value: Int) = viewModelScope.launch { settingsDataStore.setMtu(value) }

        fun setKeepAlive(value: Int) = viewModelScope.launch { settingsDataStore.setKeepAlive(value) }

        /** 清除全局 MTU 覆盖 (回退当前服务器的 mtu)。 */
        fun clearMtu() = viewModelScope.launch { settingsDataStore.clearMtu() }

        /** 清除全局 keepAlive 覆盖 (回退当前服务器的 keepAliveInterval)。 */
        fun clearKeepAlive() = viewModelScope.launch { settingsDataStore.clearKeepAlive() }

        fun setProbeUrl(url: String) = viewModelScope.launch { settingsDataStore.setProbeUrl(url.ifBlank { null }) }

        fun setEnableIPv6(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setEnableIPv6(enabled) }

        fun setDnsMode(mode: Int) =
            viewModelScope.launch {
                settingsDataStore.setDnsMode(mode)
                try {
                    val intent =
                        Intent(context, SshVpnService::class.java).apply {
                            action = SshVpnService.ACTION_REBUILD
                        }
                    context.startService(intent)
                } catch (e: Exception) {
                    // 后台 startService 可能被系统限制 → 设置改了却没重建, 零信号
                    VpnController.appLogThrottled(
                        "重建 VPN 触发失败 · ${e.message} — 设置改动需重连后生效",
                        level = LogLevel.WARNING,
                        throttleKey = "重建 VPN 触发失败",
                    )
                }
            }

        fun setAdBlockEnabled(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setAdBlockEnabled(enabled) }

        fun setLanguage(code: String) = viewModelScope.launch { settingsDataStore.setLanguage(code) }

        private companion object {
            /** 采集量轮询间隔 (与 [DomainStatsStore.TRIM_INTERVAL_MS] 同频)。 */
            const val DOMAIN_STATS_POLL_MS = 30_000L

            /** 页面不可见后多久停止轮询 (旋转/短暂切后台不重启)。 */
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
