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
    fun disconnect()
}

/**
 * SSH 直连通道工厂
 */
interface SshChannelFactory {
    fun createDirectChannel(host: String, port: Int): TunnelChannel?
    
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
        val localSocksPort: Int = 0,
        val error: String? = null
    )
}