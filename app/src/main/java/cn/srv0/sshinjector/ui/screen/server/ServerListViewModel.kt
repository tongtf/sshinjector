package cn.srv0.sshinjector.ui.screen.server

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.vpn.SshVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** T-14: 「连接中」转圈的兜底收起时间。成功由 `isActive` 提前结束, 失败靠它超时收起。 */
private const val CONNECTING_CLEAR_TIMEOUT_MS = 30_000L

@HiltViewModel
class ServerListViewModel
    @Inject
    constructor(
        application: Application,
        private val serverRepository: ServerRepository,
    ) : AndroidViewModel(application) {
        private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
        val servers = _servers.asStateFlow()

        private val _connectingServerId = MutableStateFlow<Long?>(null)
        val connectingServerId = _connectingServerId.asStateFlow()

        private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val error = _error.asSharedFlow()

        init {
            viewModelScope.launch {
                serverRepository.allServersFlow.collect { _servers.value = it }
            }
        }

        fun delete(id: Long) {
            viewModelScope.launch {
                val wasActive = serverRepository.getServerById(id)?.isActive == true
                serverRepository.deleteServer(id)
                // 删除默认服务器后, 提升一条其他服务器作为默认 (若有)
                if (wasActive) {
                    serverRepository.getAllServers().firstOrNull()?.let { serverRepository.setActiveServer(it.id) }
                }
            }
        }

        fun setActive(id: Long) {
            viewModelScope.launch {
                serverRepository.setActiveServer(id)
            }
        }

        /**
         * 快速设为默认 / 取消默认。已是默认则清空, 否则设为默认(其他全部取消)。
         */
        fun toggleDefault(id: Long) {
            viewModelScope.launch {
                val server = serverRepository.getServerById(id)
                if (server?.isActive == true) {
                    serverRepository.deactivateAllServers()
                } else {
                    serverRepository.setActiveServer(id)
                }
            }
        }

        fun connect(id: Long) {
            try {
                val server = _servers.value.find { it.id == id }
                if (server == null) {
                    _error.tryEmit("服务器不存在")
                    return
                }

                _connectingServerId.value = id
                val context = getApplication<Application>()
                val intent =
                    Intent(context, SshVpnService::class.java).apply {
                        action = SshVpnService.ACTION_CONNECT
                        putExtra(SshVpnService.EXTRA_SERVER_ID, id)
                    }
                context.startForegroundService(intent)
                watchConnecting(id)
            } catch (e: Exception) {
                Log.e("ServerListViewModel", "Failed to start VPN service: ${e.message}", e)
                clearConnecting()
                _error.tryEmit("启动 VPN 服务失败: ${e.message}")
            }
        }

        /**
         * 收起「连接中」转圈 (T-14)。连接是异步的且失败没有回写 `isActive`, 所以只依赖
         * `isActive` 变 true 会让失败后卡片永久转圈。这里等成功 (`isActive`) 或超时兜底,
         * 两者都在 `finally` 里收起转圈。
         */
        private fun watchConnecting(id: Long) {
            viewModelScope.launch {
                try {
                    withTimeoutOrNull(CONNECTING_CLEAR_TIMEOUT_MS) {
                        _servers.first { list -> list.any { it.id == id && it.isActive } }
                    }
                } finally {
                    // 仅收起本连接自己的转圈, 避免旧 watchdog 误清后续连接的状态
                    if (_connectingServerId.value == id) clearConnecting()
                }
            }
        }

        fun disconnect() {
            try {
                clearConnecting()
                val context = getApplication<Application>()
                val intent =
                    Intent(context, SshVpnService::class.java).apply {
                        action = SshVpnService.ACTION_DISCONNECT
                    }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("ServerListViewModel", "Failed to disconnect VPN service: ${e.message}", e)
                _error.tryEmit("断开 VPN 服务失败: ${e.message}")
            }
        }

        fun clearConnecting() {
            _connectingServerId.value = null
        }
    }
