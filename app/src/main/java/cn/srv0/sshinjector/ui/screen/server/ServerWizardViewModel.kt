package cn.srv0.sshinjector.ui.screen.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.model.LoginCredential
import cn.srv0.sshinjector.domain.model.ServerProvisionerContract
import cn.srv0.sshinjector.domain.model.ServerProvisioning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 向导步骤 */
enum class WizardStep {
    SERVER_INFO,
    LOGIN_CREDENTIALS,
    PROVISIONING,
    RESULT,
}

/** 向导结果状态 */
sealed class WizardResult {
    object None : WizardResult()

    data class Success(
        val account: String,
        val keyAlias: String,
    ) : WizardResult()

    data class LocalOnly(
        val reason: String,
        val keyAlias: String,
    ) : WizardResult()

    data class Failed(
        val message: String,
    ) : WizardResult()

    data class Tampered(
        val message: String,
    ) : WizardResult()
}

@HiltViewModel
class ServerWizardViewModel
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val keyManager: SshKeyManager,
        private val provisioner: ServerProvisionerContract,
    ) : ViewModel() {
        private val _currentStep = MutableStateFlow(WizardStep.SERVER_INFO)
        val currentStep = _currentStep.asStateFlow()

        // Step 1: 服务器信息
        private val _serverName = MutableStateFlow("")
        val serverName = _serverName.asStateFlow()
        private val _host = MutableStateFlow("")
        val host = _host.asStateFlow()
        private val _port = MutableStateFlow("22")
        val port = _port.asStateFlow()

        // Step 2: 登录凭证（仅本次配置用，不入库）
        private val _loginUsername = MutableStateFlow("")
        val loginUsername = _loginUsername.asStateFlow()
        private val _loginPassword = MutableStateFlow("")
        val loginPassword = _loginPassword.asStateFlow()

        // Step 3: 进度
        private val _currentProvisionStep = MutableStateFlow<ServerProvisioning.Step?>(null)
        val currentProvisionStep = _currentProvisionStep.asStateFlow()

        // Step 4: 结果
        private val _result = MutableStateFlow<WizardResult>(WizardResult.None)
        val result = _result.asStateFlow()

        private val _saved = MutableStateFlow(false)
        val saved = _saved.asStateFlow()

        private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val error = _error.asSharedFlow()

        fun setServerName(value: String) {
            _serverName.value = value
        }

        fun setHost(value: String) {
            _host.value = value
        }

        fun setPort(value: String) {
            _port.value = value
        }

        fun setLoginUsername(value: String) {
            _loginUsername.value = value
        }

        fun setLoginPassword(value: String) {
            _loginPassword.value = value
        }

        fun toLoginCredentials() {
            _currentStep.value = WizardStep.LOGIN_CREDENTIALS
        }

        fun toServerInfo() {
            _currentStep.value = WizardStep.SERVER_INFO
        }

        fun retry() {
            _currentStep.value = WizardStep.LOGIN_CREDENTIALS
            _result.value = WizardResult.None
        }

        /** 校验 Step1 输入，合法则进入 Step2 */
        fun submitServerInfo(onInvalid: (Int) -> Unit): Boolean {
            val nameError = ServerFormValidator.nameError(_serverName.value)
            val hostError = ServerFormValidator.hostError(_host.value)
            val portError = ServerFormValidator.portError(_port.value)
            return when {
                nameError == ServerFormError.NAME_REQUIRED || hostError == ServerFormError.HOST_REQUIRED -> {
                    onInvalid(R.string.wizard_require_fields)
                    false
                }
                hostError != null -> {
                    onInvalid(R.string.server_error_host_invalid)
                    false
                }
                portError != null -> {
                    onInvalid(R.string.wizard_invalid_port)
                    false
                }
                else -> {
                    _currentStep.value = WizardStep.LOGIN_CREDENTIALS
                    true
                }
            }
        }

        /** 校验 Step2 输入，合法则启动 provisioning */
        fun submitCredentials(onInvalid: (Int) -> Unit): Boolean {
            val usernameError = ServerFormValidator.usernameError(_loginUsername.value)
            return when {
                usernameError == ServerFormError.USERNAME_REQUIRED -> {
                    onInvalid(R.string.wizard_require_login_username)
                    false
                }
                usernameError != null -> {
                    onInvalid(R.string.server_error_username_invalid)
                    false
                }
                else -> {
                    startProvision()
                    true
                }
            }
        }

        private fun startProvision() {
            if (_currentStep.value == WizardStep.PROVISIONING) return
            _currentStep.value = WizardStep.PROVISIONING
            _result.value = WizardResult.None
            viewModelScope.launch {
                val alias = "server_key_${System.currentTimeMillis()}"
                try {
                    keyManager.generateKeyPair(alias, 0, requireBiometric = false)
                } catch (e: Exception) {
                    _currentStep.value = WizardStep.RESULT
                    _result.value = WizardResult.Failed("密钥生成失败: ${e.message}")
                    return@launch
                }
                val publicKey = keyManager.getPublicKey(alias)
                val login =
                    LoginCredential(
                        host = _host.value,
                        port = _port.value.toIntOrNull() ?: 22,
                        username = _loginUsername.value,
                        password = _loginPassword.value,
                    )
                provisioner.provision(login, publicKey).collect { event ->
                    when (event) {
                        is ServerProvisioning.ProvisionEvent.StepStarted -> {
                            _currentProvisionStep.value = event.step
                        }
                        is ServerProvisioning.ProvisionEvent.StepCompleted -> {
                            _currentProvisionStep.value = event.step
                        }
                        is ServerProvisioning.ProvisionEvent.Finished -> {
                            _currentStep.value = WizardStep.RESULT
                            _currentProvisionStep.value = null
                            when (val outcome = event.outcome) {
                                is ServerProvisioning.Outcome.FullSuccess -> {
                                    _result.value = WizardResult.Success(outcome.account, alias)
                                }
                                is ServerProvisioning.Outcome.LocalOnly -> {
                                    _result.value = WizardResult.LocalOnly(outcome.reason, alias)
                                }
                                is ServerProvisioning.Outcome.TamperDetected -> {
                                    _result.value = WizardResult.Tampered("脚本完整性校验失败，可能被篡改")
                                }
                                is ServerProvisioning.Outcome.Failed -> {
                                    _result.value = WizardResult.Failed(outcome.message)
                                }
                            }
                        }
                    }
                }
            }
        }

        /** 保存服务器（username 固定为隧道账号，keyAlias 来自向导生成的密钥） */
        fun save(onDone: () -> Unit) {
            if (_saved.value) return
            val alias =
                (_result.value as? WizardResult.Success)?.keyAlias
                    ?: (_result.value as? WizardResult.LocalOnly)?.keyAlias
                    ?: return
            val account =
                (_result.value as? WizardResult.Success)?.account
                    ?: ServerProvisioning.TUNNEL_ACCOUNT
            viewModelScope.launch {
                serverDao.insert(
                    ServerEntity(
                        name = _serverName.value,
                        host = _host.value,
                        port = _port.value.toIntOrNull() ?: 22,
                        username = account,
                        keyAlias = alias,
                        keyAlgorithm = "ECDSA_P256",
                        isActive = false,
                    ),
                )
                _saved.value = true
                onDone()
            }
        }
    }
