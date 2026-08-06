package cn.srv0.sshinjector.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerEditViewModel
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val keyManager: SshKeyManager,
        private val settingsDataStore: SettingsDataStore,
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
            onLoaded: (ServerEntity) -> Unit,
        ) {
            if (serverId == -1L) return
            viewModelScope.launch {
                val entity = serverDao.getByIdBlocking(serverId)
                entity?.let { onLoaded(it) }
            }
        }

        fun save(
            serverId: Long,
            entity: ServerEntity,
            onDone: () -> Unit,
            setAsDefault: Boolean = false,
        ) {
            viewModelScope.launch {
                val id =
                    if (serverId == -1L) {
                        serverDao.insert(entity.copy(isActive = false))
                    } else {
                        val originalIsActive = serverDao.getByIdBlocking(serverId)?.isActive == true
                        serverDao.update(entity.copy(isActive = if (setAsDefault) false else originalIsActive))
                        entity.id
                    }

                if (setAsDefault) {
                    serverDao.setActive(id)
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
                val wasActive = serverDao.getByIdBlocking(serverId)?.isActive == true
                serverDao.delete(serverId)
                if (wasActive) {
                    serverDao.getAllBlocking().firstOrNull()?.let { serverDao.setActive(it.id) }
                }
                onDone()
            }
        }
    }
