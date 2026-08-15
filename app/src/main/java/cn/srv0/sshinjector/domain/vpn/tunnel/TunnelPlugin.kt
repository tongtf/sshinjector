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

    /**
     * 注册回向直通回调: 远端数据不经本地 SOCKS socket 中转,
     * 由插件直接回调写回 TUN, 省一次往返与一个阻塞读协程。
     * key 为本地 SOCKS 客户端端口。数据以 (data, offset, length) 零拷贝传递,
     * 回调返回后数组即失效, 不得持有引用。
     */
    fun registerTunCallback(
        clientPort: Int,
        callback: (ByteArray, Int, Int) -> Unit,
    ) {
        // 默认无操作; 需要直通能力的插件自行实现
    }

    fun removeTunCallback(clientPort: Int) {
        // 默认无操作
    }

    val stats: StateFlow<TunnelStats>
}
