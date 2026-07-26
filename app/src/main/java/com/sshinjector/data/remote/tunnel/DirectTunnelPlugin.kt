package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.domain.vpn.TunnelChannel
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
class DirectTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "direct"
    override val displayName = "直连"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.UDP,
        TunnelCapability.DNS_OVER_TUNNEL,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
    )

    override val configDescriptor = TunnelConfigDescriptor(fields = emptyList())

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = "direct")
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _state.value = TunnelState()
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            DirectTunnelChannel(socket)
        } catch (e: Exception) {
            null
        }
    }

    override fun sendUdp(dstHost: String, dstPort: Int, payload: ByteArray) {
        // Direct mode: UDP handled by system routing
    }

    override suspend fun forwardDns(query: ByteArray): ByteArray? = null
}

private class DirectTunnelChannel(
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
