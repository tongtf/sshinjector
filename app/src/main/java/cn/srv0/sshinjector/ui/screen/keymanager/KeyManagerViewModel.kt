package cn.srv0.sshinjector.ui.screen.keymanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.ssh.KeyKind
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KeyInfo(
    val alias: String,
    val algorithm: String,
    val createdAt: String,
    val publicKey: String,
    val kind: KeyKind,
    val isBiometricProtected: Boolean,
)

@HiltViewModel
class KeyManagerViewModel
    @Inject
    constructor(
        private val keyManager: SshKeyManager,
        @ApplicationContext private val context: Context,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModel() {
        private val _keys = MutableStateFlow<List<KeyInfo>>(emptyList())
        val keys = _keys.asStateFlow()

        private val _activeKey = MutableStateFlow<KeyInfo?>(null)
        val activeKey = _activeKey.asStateFlow()

        private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val error = _error.asSharedFlow()

        fun refresh() {
            val aliases = keyManager.listKeyAliases()
            val list =
                aliases.mapNotNull { alias ->
                    try {
                        val publicKey = keyManager.getPublicKey(alias)
                        val algo = keyManager.getKeyAlgorithm(alias)
                        val createdAt = keyManager.getKeyCreationDate(alias)
                        val kind = keyManager.getKeyKind(alias)
                        val bio = kind == KeyKind.GENERATED && keyManager.isBiometricProtected(alias)
                        KeyInfo(alias, algo, createdAt, publicKey, kind, bio)
                    } catch (e: Exception) {
                        null
                    }
                }
            _keys.value = list
            _activeKey.value = _keys.value.firstOrNull()
        }

        fun generateKeyPair(
            alias: String,
            algorithm: Int,
            requireBiometric: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    val useBiometric = requireBiometric || settingsDataStore.biometricUnlock.first()
                    keyManager.generateKeyPair(alias, algorithm, useBiometric)
                    refresh()
                } catch (e: Exception) {
                    _error.tryEmit("生成失败: ${e.message}")
                }
            }
        }

        fun importPrivateKey(
            alias: String,
            pem: String,
            passphrase: String? = null,
        ) {
            viewModelScope.launch {
                try {
                    keyManager.importPrivateKey(alias, pem, passphrase)
                    refresh()
                } catch (e: Exception) {
                    _error.tryEmit("导入失败: ${e.message}")
                }
            }
        }

        fun importPublicKey(
            alias: String,
            pubKey: String,
        ) {
            viewModelScope.launch {
                try {
                    keyManager.importPublicKey(alias, pubKey)
                    refresh()
                } catch (e: Exception) {
                    _error.tryEmit("导入失败: ${e.message}")
                }
            }
        }

        fun deleteKey(alias: String) {
            keyManager.deleteKey(alias)
            refresh()
        }

        fun deleteAllKeys() {
            keyManager.deleteAllKeys()
            refresh()
        }

        fun copyPublicKey(text: String): Boolean =
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    val clip = ClipData.newPlainText("SSH Public Key", text)
                    clipboard.setPrimaryClip(clip)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
    }
