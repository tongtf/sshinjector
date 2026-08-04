package com.sshinjector.ui.screen.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.vpn.SshVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {
    val autoConnect: StateFlow<Boolean> = settingsDataStore.autoConnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val biometricUnlock: StateFlow<Boolean> = settingsDataStore.biometricUnlock
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val mtu: StateFlow<Int> = settingsDataStore.mtu
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1500)
    val keepAlive: StateFlow<Int> = settingsDataStore.keepAlive
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    val enableIPv6: StateFlow<Boolean> = settingsDataStore.enableIPv6
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dnsMode: StateFlow<Int> = settingsDataStore.dnsMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setAutoConnect(enabled) }
    fun setBiometricUnlock(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setBiometricUnlock(enabled) }
    fun setMtu(value: Int) = viewModelScope.launch { settingsDataStore.setMtu(value) }
    fun setKeepAlive(value: Int) = viewModelScope.launch { settingsDataStore.setKeepAlive(value) }
    fun setEnableIPv6(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setEnableIPv6(enabled) }
    fun setDnsMode(mode: Int) = viewModelScope.launch {
        settingsDataStore.setDnsMode(mode)
        try {
            val intent = Intent(context, SshVpnService::class.java).apply {
                action = SshVpnService.ACTION_REBUILD
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }
}
