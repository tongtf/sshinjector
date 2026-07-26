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
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpsProxyTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "https_proxy"
    override val displayName = "HTTPS Proxy"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
        TunnelCapability.TLS,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "proxyHost", label = "代理服务器", placeholder = "proxy.example.com"),
            ConfigField.NumberField(key = "proxyPort", label = "代理端口", defaultValue = 443),
            ConfigField.TextField(key = "username", label = "用户名", required = false),
            ConfigField.TextField(key = "password", label = "密码", isPassword = true, required = false),
            ConfigField.SwitchField(key = "useTls", label = "使用 TLS", defaultValue = true),
            ConfigField.TextField(key = "sni", label = "SNI", required = false, placeholder = "留空使用代理地址"),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var config: TunnelConfig.HttpsProxy? = null

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.HttpsProxy
        _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.proxyHost)

        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(c.proxyHost, c.proxyPort), c.common.connectTimeout)
            socket.close()

            this.config = c
            _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = c.proxyHost)
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
            socket.connect(InetSocketAddress(c.proxyHost, c.proxyPort), c.common.connectTimeout)

            val output = socket.getOutputStream()
            val connectRequest = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n"
            output.write(connectRequest.toByteArray())
            output.flush()

            val input = socket.getInputStream()
            val response = ByteArray(1024)
            val len = input.read(response)
            val responseStr = String(response, 0, len)

            if (responseStr.contains("200")) {
                HttpsTunnelChannel(socket)
            } else {
                socket.close()
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

private class HttpsTunnelChannel(
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
