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

        /** 当前 known_hosts 中已信任的主机指纹 (T-11 可见化; null = 尚未信任)。 */
        private val _storedFingerprint = MutableStateFlow<String?>(null)
        val storedFingerprint = _storedFingerprint.asStateFlow()

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

        /** 刷新已信任指纹 (服务器编辑页展示; host 为空/端口非法时视为未信任)。 */
        fun refreshStoredFingerprint(
            host: String,
            port: Int,
        ) {
            val h = host.trim()
            _storedFingerprint.value =
                if (h.isEmpty() || port <= 0) {
                    null
                } else {
                    knownHostsManager.getStoredFingerprint(h, port)
                }
        }

        fun resetHostKey(
            serverId: Long,
            host: String,
            port: Int,
        ) {
            viewModelScope.launch {
                // 只删 known_hosts 不够: DB 指纹非空时 check() 以它为权威源, 必须两处都清
                knownHostsManager.removeHostKey(host.trim(), port)
                serverRepository.resetHostKeyFingerprint(serverId)
                // T-11: 重置后 UI 立即回到「尚未信任」, 无需等下次进页
                _storedFingerprint.value = null
                _message.tryEmit("已重置服务器指纹（含已保存的信任记录），下次连接将重新信任该服务器")
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
                try {
                    serverRepository.saveServerEdit(serverId, config, setAsDefault)
                } catch (e: Exception) {
                    // 加密失败不降级为明文——保存中止并提示重试，绝不落明文
                    _error.tryEmit("加密失败，请重试: ${e.message}")
                    return@launch
                }
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
