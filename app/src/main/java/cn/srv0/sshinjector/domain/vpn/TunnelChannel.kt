package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.model.ServerConfig
import java.io.InputStream
import java.io.OutputStream

/**
 * SSH 直连通道接口
 * 用于 SOCKS5 CONNECT 中通过 SSH ChannelDirectTCPIP 代理连接目标
 */
interface TunnelChannel {
    fun connect(timeoutMs: Int): Boolean

    val inputStream: InputStream?
    val outputStream: OutputStream?
    val isConnected: Boolean

    /**
     * 所属 SSH 会话的标识, 仅用于排障日志。
     *
     * JSch 每条 Session 只有**一个读线程**, 它把 CHANNEL_DATA 直接写进本通道
     * `inputStream` 的管道里, 管道一满这条会话所有通道都收不到数据 (见
     * `max_input_buffer_size` 注释)。因此看到「已写未读」时必须能区分:
     * 同 `sessionId` 的多个通道一起饿 = **会话级冻结**;
     * 各不相同 / 只有这一个 = **该目标主机自己不应答**。
     * 非 SSH 实现 (测试假通道等) 返回 `""`。
     */
    val sessionId: String
        get() = ""

    fun disconnect()
}

/**
 * SSH 直连通道工厂
 */
interface SshChannelFactory {
    fun createDirectChannel(
        host: String,
        port: Int,
    ): TunnelChannel?

    /**
     * 连接到 SSH 服务器
     */
    suspend fun connect(config: ServerConfig): ConnectionResult

    /**
     * 断开 SSH 连接
     */
    suspend fun disconnect(): Boolean

    data class ConnectionResult(
        val success: Boolean,
        val error: String? = null,
    )
}
