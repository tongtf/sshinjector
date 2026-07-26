package com.sshinjector.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.domain.vpn.tunnel.TunnelManager
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import com.sshinjector.domain.vpn.tunnel.TunnelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val tunnelManager: TunnelManager
) : ViewModel() {
    val autoConnect: StateFlow<Boolean> = settingsDataStore.autoConnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val biometricUnlock: StateFlow<Boolean> = settingsDataStore.biometricUnlock
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val notificationEnabled: StateFlow<Boolean> = settingsDataStore.notificationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val mtu: StateFlow<Int> = settingsDataStore.mtu
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1500)
    val keepAlive: StateFlow<Int> = settingsDataStore.keepAlive
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    val enableIPv6: StateFlow<Boolean> = settingsDataStore.enableIPv6
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dnsMode: StateFlow<Int> = settingsDataStore.dnsMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val theme: StateFlow<String> = settingsDataStore.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val logLevel: StateFlow<Int> = settingsDataStore.logLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    val availablePlugins: StateFlow<List<TunnelPlugin>> = tunnelManager.availablePlugins
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activePlugin: StateFlow<TunnelPlugin?> = tunnelManager.activePlugin
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setAutoConnect(enabled) }
    fun setBiometricUnlock(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setBiometricUnlock(enabled) }
    fun setNotificationEnabled(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setNotificationEnabled(enabled) }
    fun setMtu(value: Int) = viewModelScope.launch { settingsDataStore.setMtu(value) }
    fun setKeepAlive(value: Int) = viewModelScope.launch { settingsDataStore.setKeepAlive(value) }
    fun setEnableIPv6(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setEnableIPv6(enabled) }
    fun setDnsMode(mode: Int) = viewModelScope.launch { settingsDataStore.setDnsMode(mode) }
    fun setTheme(value: String) = viewModelScope.launch { settingsDataStore.setTheme(value) }
    fun setLogLevel(level: Int) = viewModelScope.launch { settingsDataStore.setLogLevel(level) }
}
