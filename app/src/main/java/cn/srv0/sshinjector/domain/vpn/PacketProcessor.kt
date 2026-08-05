package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据包处理器（协调器）：解析 IP 头，按协议分派到 TCP/UDP/ICMPv6 子模块。
 */
@Singleton
class PacketProcessor @Inject constructor(
    private val tunnelManager: TunnelManager
) {
    companion object {
        const val DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS = 300000L // 5 分钟默认值
    }

    private val stats = PacketStats()

    @Volatile private var tunWriter: ((ByteArray) -> Unit)? = null
    private var dnsInterceptor: DnsInterceptor? = null

    private val tcpStateMachine = TcpStateMachine(tunnelManager, { tunWriter }, stats)
    private val udpRelay = UdpRelay({ tunWriter }, stats)
    private val icmpv6Responder = Icmpv6Responder({ tunWriter })

    val packetsProcessed: StateFlow<Long> = stats.packetsProcessed
    val bytesProcessed: StateFlow<Long> = stats.bytesProcessed
    val errors: StateFlow<Long> = stats.errors

    fun setTunWriter(writer: (ByteArray) -> Unit) {
        tunWriter = writer
    }

    fun setDnsInterceptor(interceptor: DnsInterceptor) {
        dnsInterceptor = interceptor
        tcpStateMachine.setDnsInterceptor(interceptor)
        udpRelay.setDnsInterceptor(interceptor)
    }

    /**
     * 处理来自 TUN 的 IPv4 数据包
     */
    fun processIpv4Packet(buffer: ByteBuffer, tunFd: java.io.FileDescriptor): Boolean {
        val parsed = IpPacketParser.parseIpv4Header(buffer) ?: return false
        return when (parsed.protocol) {
            0x06 -> tcpStateMachine.processTcpPacket(
                buffer, parsed.srcIp, parsed.dstIp, parsed.payloadStart, parsed.payloadLength, tunFd
            )
            0x11 -> udpRelay.processUdpPacket(
                buffer, parsed.srcIp, parsed.dstIp, parsed.payloadStart, parsed.payloadLength, tunFd
            )
            else -> false
        }
    }

    /**
     * 处理来自 TUN 的 IPv6 数据包
     */
    fun processIpv6Packet(buffer: ByteBuffer, tunFd: java.io.FileDescriptor): Boolean {
        val parsed = IpPacketParser.parseIpv6Header(buffer) ?: return false
        return when (parsed.protocol) {
            0x06 -> tcpStateMachine.processTcpPacket(
                buffer, parsed.srcIp, parsed.dstIp, parsed.payloadStart, parsed.payloadLength, tunFd
            )
            0x11 -> udpRelay.processUdpPacket(
                buffer, parsed.srcIp, parsed.dstIp, parsed.payloadStart, parsed.payloadLength, tunFd
            )
            0x3A -> icmpv6Responder.processIcmpv6Packet(
                buffer, parsed.srcIp, parsed.dstIp, parsed.payloadStart, parsed.payloadLength
            )
            else -> false
        }
    }

    /**
     * 构建 UDP 响应包 (用于 DNS 回程)
     */
    fun buildUdpResponsePacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        return udpRelay.buildUdpResponsePacket(srcIp, dstIp, srcPort, dstPort, payload)
    }

    fun cleanupStaleConnections(timeoutMs: Long = DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS) {
        tcpStateMachine.cleanupStaleConnections(timeoutMs)
        udpRelay.cleanupStaleConnections(timeoutMs)
    }
}
