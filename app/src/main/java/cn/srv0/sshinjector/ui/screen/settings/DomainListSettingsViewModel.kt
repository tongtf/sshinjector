package cn.srv0.sshinjector.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.data.local.DomainListState
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DomainListSettingsViewModel @Inject constructor(
    private val domainListManager: DomainListManager,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    val state: StateFlow<DomainListState> = domainListManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, DomainListState.Idle)

    val domainListUrl: StateFlow<String> = settingsDataStore.domainListUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsDataStore.DEFAULT_DOMAIN_LIST_URL)

    fun updateList() = viewModelScope.launch { domainListManager.update() }

    fun setDomainListUrl(url: String) = viewModelScope.launch { settingsDataStore.setDomainListUrl(url) }

    fun resetToDefault() = viewModelScope.launch {
        settingsDataStore.setDomainListUrl(SettingsDataStore.DEFAULT_DOMAIN_LIST_URL)
    }
}
