package com.sshinjector.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.dao.ServerDao
import com.sshinjector.data.local.entity.ServerEntity
import com.sshinjector.data.remote.ssh.SshKeyManager
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    private val serverDao: ServerDao,
    private val keyManager: SshKeyManager,
    private val plugins: Map<String, @JvmSuppressWildcards TunnelPlugin>
) : ViewModel() {
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    private val _keyAliases = MutableStateFlow<List<String>>(emptyList())
    val keyAliases = _keyAliases.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

    private val _plugins = MutableStateFlow(plugins)
    val pluginList: Flow<List<TunnelPlugin>> = _plugins.asStateFlow().map { it.values.toList() }

    init {
        refreshKeys()
    }

    fun refreshKeys() {
        _keyAliases.value = keyManager.listKeyAliases()
    }

    fun generateAndAssociate(onGenerated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val newAlias = "server_key_${System.currentTimeMillis()}"
                keyManager.generateKeyPair(newAlias, 0, false)
                refreshKeys()
                onGenerated(newAlias)
            } catch (e: Exception) {
                _error.tryEmit("生成失败: ${e.message}")
            }
        }
    }

    fun load(serverId: Long, onLoaded: (ServerEntity) -> Unit) {
        if (serverId == -1L) return
        viewModelScope.launch {
            val entity = serverDao.getByIdBlocking(serverId)
            if (entity != null) onLoaded(entity)
        }
    }

    fun save(serverId: Long, entity: ServerEntity, onDone: () -> Unit, setAsDefault: Boolean = false) {
        viewModelScope.launch {
            val id = if (serverId == -1L) {
                // 统一先按非默认插入, 由 setActive 原子收敛唯一性
                serverDao.insert(entity.copy(isActive = false))
            } else {
                // 保留原 isActive 值 (用户取消默认时若该服务器是唯一默认, 保持其默认状态, 避免 0 active)
                val originalIsActive = serverDao.getByIdBlocking(serverId)?.isActive == true
                serverDao.update(entity.copy(isActive = if (setAsDefault) false else originalIsActive))
                entity.id
            }

            // 如果设置了为默认服务器，更新其他服务器的 isActive 状态
            if (setAsDefault) {
                serverDao.setActive(id)
            }

            _saved.value = true
            onDone()
        }
    }

    fun delete(serverId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val wasActive = serverDao.getByIdBlocking(serverId)?.isActive == true
            serverDao.delete(serverId)
            // 删除默认服务器后, 提升一条其他服务器作为默认 (若有)
            if (wasActive) {
                serverDao.getAllBlocking().firstOrNull()?.let { serverDao.setActive(it.id) }
            }
            onDone()
        }
    }
}
