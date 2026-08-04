package com.sshinjector.ui.screen.server

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.dao.ServerDao
import com.sshinjector.data.local.entity.ServerEntity
import com.sshinjector.vpn.SshVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    application: Application,
    private val serverDao: ServerDao
) : AndroidViewModel(application) {
    private val _servers = MutableStateFlow<List<ServerEntity>>(emptyList())
    val servers = _servers.asStateFlow()

    private val _connectingServerId = MutableStateFlow<Long?>(null)
    val connectingServerId = _connectingServerId.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

    init {
        viewModelScope.launch {
            serverDao.getAll().collect { _servers.value = it }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val wasActive = serverDao.getByIdBlocking(id)?.isActive == true
            serverDao.delete(id)
            // 删除默认服务器后, 提升一条其他服务器作为默认 (若有)
            if (wasActive) {
                serverDao.getAllBlocking().firstOrNull()?.let { serverDao.setActive(it.id) }
            }
        }
    }

    fun setActive(id: Long) {
        viewModelScope.launch {
            serverDao.setActive(id)
        }
    }

    /**
     * 快速设为默认 / 取消默认。已是默认则清空, 否则设为默认(其他全部取消)。
     */
    fun toggleDefault(id: Long) {
        viewModelScope.launch {
            val server = serverDao.getByIdBlocking(id)
            if (server?.isActive == true) {
                serverDao.deactivateAll()
            } else {
                serverDao.setActive(id)
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
            val intent = Intent(context, SshVpnService::class.java).apply {
                action = SshVpnService.ACTION_CONNECT
                putExtra(SshVpnService.EXTRA_SERVER_ID, id)
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("ServerListViewModel", "Failed to start VPN service: ${e.message}", e)
            _connectingServerId.value = null
            _error.tryEmit("启动 VPN 服务失败: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            _connectingServerId.value = null
            val context = getApplication<Application>()
            val intent = Intent(context, SshVpnService::class.java).apply {
                action = SshVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("ServerListViewModel", "Failed to disconnect VPN service: ${e.message}", e)
            _error.tryEmit("断开 VPN 服务失败: ${e.message}")
        }
    }

    fun clearConnecting() {
        _connectingServerId.value = null
    }
}
