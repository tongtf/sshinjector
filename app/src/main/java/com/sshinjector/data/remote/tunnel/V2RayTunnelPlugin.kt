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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocketFactory

@Singleton
class V2RayTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "v2ray"
    override val displayName = "V2Ray"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
        TunnelCapability.TLS,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "serverHost", label = "服务器地址"),
            ConfigField.NumberField(key = "serverPort", label = "端口", defaultValue = 443),
            ConfigField.TextField(key = "uuid", label = "UUID"),
            ConfigField.NumberField(key = "alterId", label = "AlterID", defaultValue = 0, min = 0),
            ConfigField.DropdownField(
                key = "security", label = "加密方式",
                options = listOf("auto" to "自动", "aes-128-gcm" to "AES-128-GCM", "chacha20-poly1305" to "ChaCha20", "none" to "无")
            ),
            ConfigField.DropdownField(
                key = "network", label = "传输协议",
                options = listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC")
            ),
            ConfigField.TextField(key = "path", label = "路径 (WS/gRPC)", required = false),
            ConfigField.SwitchField(key = "useTls", label = "使用 TLS", defaultValue = true),
            ConfigField.TextField(key = "sni", label = "SNI", required = false),
            ConfigField.SwitchField(key = "allowInsecure", label = "允许不安全连接", defaultValue = false),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var config: TunnelConfig.V2Ray? = null

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.V2Ray
        _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.serverHost)

        return try {
            val socket = createSocket(c)
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
            val socket = createSocket(c)
            val os = socket.getOutputStream()
            val input = socket.getInputStream()

            val vmess = VmessAeadProtocol(c.uuid)
            val request = vmess.buildRequest(host, port, System.currentTimeMillis() / 1000)
            os.write(request)
            os.flush()

            if (!vmess.readResponse(input)) {
                socket.close()
                return null
            }

            V2RayTunnelChannel(socket)
        } catch (e: Exception) {
            null
        }
    }

    private fun createSocket(c: TunnelConfig.V2Ray): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(c.serverHost, c.serverPort), c.common.connectTimeout)
        if (c.useTls) {
            val sni = c.sni ?: c.serverHost
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            return sslFactory.createSocket(socket, sni, c.serverPort, true)
        }
        return socket
    }
}

private class VmessAeadProtocol(uuid: String) {
    private val uuidBytes = run {
        val hex = uuid.replace("-", "")
        ByteArray(16) { Integer.parseInt(hex.substring(it * 2, it * 2 + 2), 16).toByte() }
    }
    private val key = md5(uuidBytes + "c48619fe-8f02-49e0-b9e9-edf763e17e21".toByteArray())
    private val iv = md5(key + "c48619fe-8f02-49e0-b9e9-edf763e17e21".toByteArray())

    fun buildRequest(host: String, port: Int, timestamp: Long): ByteArray {
        val tsBytes = ByteArray(8) { (timestamp shr (it * 8)).toByte() }

        val body = buildRequestBody(host, port)
        val bodyKey = md5(key + tsBytes)
        val bodyIv = md5(iv + tsBytes)

        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(bodyKey, "AES"), IvParameterSpec(bodyIv))
        val encryptedBody = cipher.doFinal(body)

        val authData = ByteArray(16)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(uuidBytes, "HmacSHA256"))
        val authId = hmac.doFinal(tsBytes).copyOf(4)
        authId.copyInto(authData, 0, 0, 4)
        val tsPart = tsBytes.copyOf(8)
        tsPart.copyInto(authData, 4, 0, 8)

        val headerCipher = Cipher.getInstance("AES/CFB/NoPadding")
        headerCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encryptedAuth = headerCipher.doFinal(authData).copyOf(16)

        return encryptedAuth + encryptedBody
    }

    fun readResponse(input: InputStream): Boolean {
        val buf = ByteArray(4)
        val n = input.read(buf)
        return n >= 4
    }

    private fun buildRequestBody(host: String, port: Int): ByteArray {
        val addrBytes = buildAddrBytes(host, port)
        return ByteArray(1) + // version
            byteArrayOf(1) + // cmd (TCP)
            byteArrayOf(0) + // opt
            byteArrayOf(0) +  // sec
            addrBytes
    }

    private fun buildAddrBytes(host: String, port: Int): ByteArray {
        val portBytes = byteArrayOf((port shr 8).toByte(), port.toByte())
        return try {
            val addr = InetAddress.getByName(host)
            when {
                addr is Inet4Address -> byteArrayOf(1) + addr.address + portBytes
                addr is java.net.Inet6Address -> byteArrayOf(4) + addr.address + portBytes
                else -> byteArrayOf(3, host.length.toByte()) + host.toByteArray() + portBytes
            }
        } catch (_: Exception) {
            byteArrayOf(3, host.length.toByte()) + host.toByteArray() + portBytes
        }
    }

    private fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)
}

private class V2RayTunnelChannel(
    private val socket: Socket,
) : TunnelChannel {
    override fun connect(timeoutMs: Int): Boolean = socket.isConnected
    override val inputStream: InputStream? get() = if (socket.isConnected) socket.getInputStream() else null
    override val outputStream: OutputStream? get() = if (socket.isConnected) socket.getOutputStream() else null
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
    override fun disconnect() {
        try { socket.close() } catch (_: Exception) {}
    }
}
