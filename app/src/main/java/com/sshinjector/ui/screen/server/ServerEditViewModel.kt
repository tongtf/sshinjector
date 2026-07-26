package com.sshinjector.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.dao.ServerDao
import com.sshinjector.data.local.entity.ServerEntity
import com.sshinjector.data.remote.ssh.SshKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    private val serverDao: ServerDao,
    private val keyManager: SshKeyManager
) : ViewModel() {
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    private val _keyAliases = MutableStateFlow<List<String>>(emptyList())
    val keyAliases = _keyAliases.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

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
            if (serverId == -1L) {
                serverDao.insert(entity)
            } else {
                serverDao.update(entity)
            }

            // 如果设置了为默认服务器，更新其他服务器的 isActive 状态
            if (setAsDefault) {
                serverDao.setActive(entity.id)
            }

            _saved.value = true
            onDone()
        }
    }

    fun delete(serverId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            serverDao.delete(serverId)
            onDone()
        }
    }
}
