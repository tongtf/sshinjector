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
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocketFactory

@Singleton
class TrojanTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "trojan"
    override val displayName = "Trojan"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
        TunnelCapability.TLS,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "serverHost", label = "服务器地址", placeholder = "trojan.example.com"),
            ConfigField.NumberField(key = "serverPort", label = "端口", defaultValue = 443),
            ConfigField.TextField(key = "password", label = "密码", isPassword = true),
            ConfigField.TextField(key = "sni", label = "SNI", required = false, placeholder = "留空使用服务器地址"),
            ConfigField.SwitchField(key = "allowInsecure", label = "允许不安全证书", defaultValue = false),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var config: TunnelConfig.Trojan? = null

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.Trojan
        _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.serverHost)

        return try {
            val socket = createSocket(c.serverHost, c.serverPort, c.common.connectTimeout, c)
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
            val socket = createSocket(c.serverHost, c.serverPort, c.common.connectTimeout, c)
            val os = socket.getOutputStream()

            os.write((c.password + "\r\n").toByteArray())

            val addrBytes = buildAddressBytes(host, port)
            os.write(addrBytes)
            os.flush()

            TrojanTunnelChannel(socket)
        } catch (e: Exception) {
            null
        }
    }

    private fun createSocket(host: String, port: Int, timeout: Int, c: TunnelConfig.Trojan): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeout)
        val sni = c.sni ?: host
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        return sslFactory.createSocket(socket, sni, port, true)
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

private class TrojanTunnelChannel(
    private val socket: Socket
) : TunnelChannel {
    override fun connect(timeoutMs: Int): Boolean = socket.isConnected
    override val inputStream: InputStream? get() = if (socket.isConnected) socket.getInputStream() else null
    override val outputStream: OutputStream? get() = if (socket.isConnected) socket.getOutputStream() else null
    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
    override fun disconnect() {
        try { socket.close() } catch (_: Exception) {}
    }
}
