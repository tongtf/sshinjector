package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 数据包处理统计，跨模块共享。
 */
class PacketStats {
    val packetsProcessed = MutableStateFlow(0L)
    val bytesProcessed = MutableStateFlow(0L)
    val errors = MutableStateFlow(0L)
}
