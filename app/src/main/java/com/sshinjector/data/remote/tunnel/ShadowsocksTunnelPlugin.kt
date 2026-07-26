package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.tunnel.ConfigField
import com.sshinjector.domain.vpn.tunnel.TunnelCapability
import com.sshinjector.domain.vpn.tunnel.TunnelConfig
import com.sshinjector.domain.vpn.tunnel.TunnelConfigDescriptor
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import com.sshinjector.domain.vpn.tunnel.TunnelState
import com.sshinjector.domain.vpn.tunnel.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShadowsocksTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "shadowsocks"
    override val displayName = "Shadowsocks"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "serverHost", label = "服务器地址", placeholder = "ss.example.com"),
            ConfigField.NumberField(key = "serverPort", label = "端口", defaultValue = 8388),
            ConfigField.TextField(key = "password", label = "密码", isPassword = true),
            ConfigField.DropdownField(
                key = "method",
                label = "加密方法",
                options = listOf("aes-256-gcm" to "AES-256-GCM", "aes-128-gcm" to "AES-128-GCM", "chacha20-ietf-poly1305" to "ChaCha20"),
                defaultValue = "aes-256-gcm"
            ),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var config: TunnelConfig.Shadowsocks? = null

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.Shadowsocks
        _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.serverHost)

        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(c.serverHost, c.serverPort), c.common.connectTimeout)
            socket.close()
            this.config = c
            _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = c.serverHost)
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = TunnelState(status = TunnelState.Status.Failed, error = e.message)
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        config = null
        _state.value = TunnelState()
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        val c = config ?: return null
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(c.serverHost, c.serverPort), c.common.connectTimeout)
            val os = socket.getOutputStream()
            val ssCipher = SsCipher(c.password, c.method)

            val salt = ByteArray(ssCipher.saltLen).also { SecureRandom().nextBytes(it) }
            val sessionKey = ssCipher.deriveKey(salt)
            os.write(salt)

            val addrBytes = buildAddressBytes(host, port)
            val encrypted = ssCipher.encrypt(sessionKey, addrBytes)
            os.write(encrypted)
            os.flush()

            ShadowsocksTunnelChannel(socket, ssCipher, sessionKey)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildAddressBytes(host: String, port: Int): ByteArray {
        val portBytes = byteArrayOf((port shr 8).toByte(), port.toByte())
        return try {
            val addr = InetAddress.getByName(host)
            when {
                addr is java.net.Inet4Address -> byteArrayOf(1) + addr.address + portBytes
                addr is java.net.Inet6Address -> byteArrayOf(4) + addr.address + portBytes
                else -> byteArrayOf(3, host.length.toByte()) + host.toByteArray() + portBytes
            }
        } catch (_: Exception) {
            byteArrayOf(3, host.length.toByte()) + host.toByteArray() + portBytes
        }
    }
}

private class SsCipher(password: String, method: String) {
    val saltLen: Int
    private val keyLen: Int
    private val ivLen: Int
    private val tagLen: Int = 16
    private val cipherName: String

    init {
        when (method) {
            "aes-256-gcm" -> { saltLen = 32; keyLen = 32; ivLen = 12; cipherName = "AES/GCM/NoPadding" }
            "aes-128-gcm" -> { saltLen = 16; keyLen = 16; ivLen = 12; cipherName = "AES/GCM/NoPadding" }
            "chacha20-ietf-poly1305" -> { saltLen = 32; keyLen = 32; ivLen = 12; cipherName = "AES/GCM/NoPadding" }
            else -> { saltLen = 32; keyLen = 32; ivLen = 12; cipherName = "AES/GCM/NoPadding" }
        }
    }

    private val keyMaterial: ByteArray = run {
        val md = MessageDigest.getInstance("SHA-256")
        val result = ByteArray(keyLen)
        var ctx = password.toByteArray()
        var offset = 0
        while (offset < keyLen) {
            ctx = md.digest(ctx)
            val copyLen = minOf(ctx.size, keyLen - offset)
            ctx.copyInto(result, offset, 0, copyLen)
            offset += copyLen
        }
        result
    }

    fun deriveKey(salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val result = keyMaterial.copyOf()
        val ctx = md.digest(keyMaterial + salt)
        ctx.copyInto(result, 0, 0, minOf(ctx.size, result.size))
        return result
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(ivLen).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(cipherName)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagLen * 8, iv))
        val encrypted = cipher.doFinal(plaintext)
        return iv + encrypted
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, ivLen)
        val ciphertext = data.copyOfRange(ivLen, data.size)
        val cipher = Cipher.getInstance(cipherName)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagLen * 8, iv))
        return cipher.doFinal(ciphertext)
    }
}

private class ShadowsocksTunnelChannel(
    private val socket: Socket,
    private val ssCipher: SsCipher,
    private val sessionKey: ByteArray,
) : TunnelChannel {
    override fun connect(timeoutMs: Int): Boolean = socket.isConnected
    override val inputStream: InputStream? get() = if (socket.isConnected) socket.getInputStream() else null
    override val outputStream: OutputStream? get() = if (socket.isConnected) socket.getOutputStream() else null
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
    override fun disconnect() {
        try { socket.close() } catch (_: Exception) {}
    }
}
