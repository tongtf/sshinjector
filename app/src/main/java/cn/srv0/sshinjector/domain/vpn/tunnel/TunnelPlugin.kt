package cn.srv0.sshinjector.domain.vpn.tunnel

import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import kotlinx.coroutines.flow.StateFlow

interface TunnelPlugin {
    val id: String

    val displayName: String

    val iconResId: Int

    val capabilities: Set<TunnelCapability>

    val configDescriptor: TunnelConfigDescriptor

    suspend fun connect(config: TunnelConfig): Result<Unit>

    suspend fun disconnect()

    val state: StateFlow<TunnelState>

    fun openTcpChannel(
        host: String,
        port: Int,
    ): TunnelChannel?

    val localSocksPort: Int get() = 0

    fun sendUdp(
        dstHost: String,
        dstPort: Int,
        payload: ByteArray,
    ) {
        throw UnsupportedOperationException("UDP not supported by $id")
    }

    suspend fun forwardDns(query: ByteArray): ByteArray? = null

    val stats: StateFlow<TunnelStats>
}
