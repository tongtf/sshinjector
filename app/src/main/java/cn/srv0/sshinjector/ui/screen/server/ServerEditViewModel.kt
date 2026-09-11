package cn.srv0.sshinjector.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.ssh.KnownHostsManager
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerEditViewModel
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val keyManager: SshKeyManager,
        private val knownHostsManager: KnownHostsManager,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModel() {
        private val _saved = MutableStateFlow(false)
        val saved = _saved.asStateFlow()

        private val _keyAliases = MutableStateFlow<List<String>>(emptyList())
        val keyAliases = _keyAliases.asStateFlow()

        private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val error = _error.asSharedFlow()

        private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val message = _message.asSharedFlow()

        /**
         * 全局网络设置 (null 字段 = 未设置, 运行时回退 per-server)。
         * 编辑页据此提示"全局覆盖中"。
         */
        private val _globalSettings = MutableStateFlow(GlobalNetworkSettings(null, null, null))
        val globalSettings = _globalSettings.asStateFlow()

        init {
            refreshKeys()
            viewModelScope.launch {
                settingsDataStore.mtu
                    .combine(settingsDataStore.keepAlive) { mtu, keepAlive -> mtu to keepAlive }
                    .combine(settingsDataStore.enableIPv6) { (mtu, keepAlive), ipv6 ->
                        GlobalNetworkSettings(mtu, keepAlive, ipv6)
                    }.collect { _globalSettings.value = it }
            }
        }

        data class GlobalNetworkSettings(
            val mtu: Int?,
            val keepAlive: Int?,
            val enableIPv6: Boolean?,
        )

        fun refreshKeys() {
            _keyAliases.value = keyManager.listKeyAliases()
        }

        fun resetHostKey(
            host: String,
            port: Int,
        ) {
            viewModelScope.launch {
                val removed = knownHostsManager.removeHostKey(host.trim(), port)
                _message.tryEmit(
                    if (removed) {
                        "已重置服务器指纹，下次连接将重新信任该服务器"
                    } else {
                        "未找到已保存的服务器指纹"
                    },
                )
            }
        }

        fun generateAndAssociate(onGenerated: (String) -> Unit) {
            viewModelScope.launch {
                try {
                    val newAlias = "server_key_${System.currentTimeMillis()}"
                    val useBiometric = settingsDataStore.biometricUnlock.first()
                    keyManager.generateKeyPair(newAlias, 0, useBiometric)
                    refreshKeys()
                    onGenerated(newAlias)
                } catch (e: Exception) {
                    _error.tryEmit("生成失败: ${e.message}")
                }
            }
        }

        fun load(
            serverId: Long,
            onLoaded: (ServerConfig) -> Unit,
        ) {
            if (serverId == -1L) return
            viewModelScope.launch {
                val config = serverRepository.getServerById(serverId)
                config?.let { onLoaded(it) }
            }
        }

        fun save(
            serverId: Long,
            config: ServerConfig,
            onDone: () -> Unit,
            setAsDefault: Boolean = false,
        ) {
            viewModelScope.launch {
                serverRepository.saveServerEdit(serverId, config, setAsDefault)
                _saved.value = true
                onDone()
            }
        }

        fun delete(
            serverId: Long,
            onDone: () -> Unit,
        ) {
            viewModelScope.launch {
                val wasActive = serverRepository.getServerById(serverId)?.isActive == true
                serverRepository.deleteServer(serverId)
                if (wasActive) {
                    serverRepository.getAllServers().firstOrNull()?.let { serverRepository.setActiveServer(it.id) }
                }
                onDone()
            }
        }
    }
