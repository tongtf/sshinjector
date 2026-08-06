package cn.srv0.sshinjector.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.config.ServerProvisioner
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.model.LoginCredential
import cn.srv0.sshinjector.domain.model.ServerProvisioning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一键配置 UI 状态 */
sealed class ProvisioningUiState {
    object Idle : ProvisioningUiState()

    data class Running(val steps: List<ServerProvisioning.Step>, val currentStep: ServerProvisioning.Step?) :
        ProvisioningUiState()

    data class Success(val account: String, val keyAlias: String) : ProvisioningUiState()

    data class LocalOnly(val reason: String, val keyAlias: String) : ProvisioningUiState()

    data class Failed(val message: String) : ProvisioningUiState()

    data class Tampered(val message: String) : ProvisioningUiState()
}

@HiltViewModel
class ServerEditViewModel
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val keyManager: SshKeyManager,
        private val settingsDataStore: SettingsDataStore,
        private val provisioner: ServerProvisioner,
    ) : ViewModel() {
        private val _saved = MutableStateFlow(false)
        val saved = _saved.asStateFlow()

        private val _keyAliases = MutableStateFlow<List<String>>(emptyList())
        val keyAliases = _keyAliases.asStateFlow()

        private val _provisioningState = MutableStateFlow<ProvisioningUiState>(ProvisioningUiState.Idle)
        val provisioningState = _provisioningState.asStateFlow()

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
                    keyManager.generateKeyPair(newAlias, 3, useBiometric)
                    refreshKeys()
                    onGenerated(newAlias)
                } catch (e: Exception) {
                    _error.tryEmit("生成失败: ${e.message}")
                }
            }
        }

        /**
         * 启动一键配置：生成 Ed25519 密钥 → 远程执行服务器端配置脚本。
         * 成功/降级后回填 [onProvisioned]，UI 据此填充隧道账号与密钥别名。
         */
        fun startProvision(
            host: String,
            port: Int,
            loginUsername: String,
            loginPassword: String,
            onProvisioned: (account: String, keyAlias: String) -> Unit,
        ) {
            if (_provisioningState.value is ProvisioningUiState.Running) return
            viewModelScope.launch {
                val alias = "server_key_${System.currentTimeMillis()}"
                try {
                    keyManager.generateKeyPair(alias, 3, requireBiometric = false)
                } catch (e: Exception) {
                    _provisioningState.value = ProvisioningUiState.Failed("密钥生成失败: ${e.message}")
                    return@launch
                }
                val publicKey = keyManager.getPublicKey(alias)
                val login =
                    LoginCredential(
                        host = host,
                        port = port,
                        username = loginUsername,
                        password = loginPassword,
                    )
                _provisioningState.value =
                    ProvisioningUiState.Running(
                        steps = ServerProvisioning.Step.entries.toList(),
                        currentStep = null,
                    )
                provisioner.provision(login, publicKey).collect { event ->
                    when (event) {
                        is ServerProvisioning.ProvisionEvent.StepStarted -> {
                            val cur = _provisioningState.value as? ProvisioningUiState.Running
                            if (cur != null) {
                                _provisioningState.value = cur.copy(currentStep = event.step)
                            }
                        }
                        is ServerProvisioning.ProvisionEvent.StepCompleted -> {
                            val cur = _provisioningState.value as? ProvisioningUiState.Running
                            if (cur != null) {
                                _provisioningState.value = cur.copy(currentStep = event.step)
                            }
                        }
                        is ServerProvisioning.ProvisionEvent.Finished -> {
                            when (val outcome = event.outcome) {
                                is ServerProvisioning.Outcome.FullSuccess -> {
                                    _provisioningState.value =
                                        ProvisioningUiState.Success(outcome.account, alias)
                                    onProvisioned(outcome.account, alias)
                                }
                                is ServerProvisioning.Outcome.LocalOnly -> {
                                    _provisioningState.value =
                                        ProvisioningUiState.LocalOnly(outcome.reason, alias)
                                    onProvisioned(ServerProvisioning.TUNNEL_ACCOUNT, alias)
                                }
                                is ServerProvisioning.Outcome.TamperDetected -> {
                                    _provisioningState.value =
                                        ProvisioningUiState.Tampered(
                                            "脚本完整性校验失败，可能被篡改",
                                        )
                                }
                                is ServerProvisioning.Outcome.Failed -> {
                                    _provisioningState.value =
                                        ProvisioningUiState.Failed(outcome.message)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun resetProvisioning() {
            _provisioningState.value = ProvisioningUiState.Idle
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
