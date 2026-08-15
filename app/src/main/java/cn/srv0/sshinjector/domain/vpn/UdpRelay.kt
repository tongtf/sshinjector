package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

/**
 * UDP 处理（降级模式）。
 *
 * UDP relay 已明确不支持: 进入 TUN 的非 53 端口 UDP (QUIC/游戏) 直接丢弃并计数,
 * 不再假装通过 SOCKS5 UDP ASSOCIATE 转发 (该链路从未可用: 本地代理只监听 TCP)。
 * 仅保留: DNS 拦截 (53) 与 UDP 回程包构造 (DNS 响应)。
 */
class UdpRelay(
    private val stats: PacketStats,
) {
    companion object {
        private const val TAG = "PacketProcessor"
    }

    private val droppedUdpCount = AtomicLong(0)

    val droppedUdp: Long
        get() = droppedUdpCount.get()

    private var dnsInterceptor: DnsInterceptor? = null

    fun setDnsInterceptor(interceptor: DnsInterceptor) {
        dnsInterceptor = interceptor
    }

    /**
     * 处理 UDP 数据包: 53 端口走 DNS 拦截, 其余明确丢弃 (UDP relay 不支持)。
     */
    fun processUdpPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
    ): Boolean {
        if (payloadLength < 8) return false

        buffer.position(payloadStart)
        val srcPort = buffer.getShort().toInt() and 0xFFFF
        val dstPort = buffer.getShort().toInt() and 0xFFFF
        buffer.getShort() // length
        buffer.getShort() // checksum

        // DNS 查询拦截 (UDP 53)
        if (dstPort == 53 || srcPort == 53) {
            buffer.limit(payloadStart + payloadLength)
            return handleDnsPacket(buffer, srcIp, dstIp, srcPort, dstPort)
        }

        // UDP relay 不支持: 明确丢弃并计数 (QUIC/游戏等), 避免静默吞包
        droppedUdpCount.incrementAndGet()
        if (IS_DEBUG) {
            Log.d(TAG, "UDP ${dstIp.hostAddress}:$dstPort dropped (UDP relay not supported)")
        }
        return true
    }

    /**
     * DNS 包处理: 委托 DnsInterceptor 进行解析
     */
    private fun handleDnsPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): Boolean {
        val interceptor = dnsInterceptor
        if (interceptor == null) {
            Log.w(TAG, "DnsInterceptor not set, DNS packet ignored")
            return false
        }

        try {
            // 诊断日志：记录所有 53 端口 UDP 包
            if (IS_DEBUG) {
                Log.d(
                    TAG,
                    "DNS packet from $srcIp:$srcPort to $dstIp:$dstPort (${buffer.remaining()}B)",
                )
            }

            // 提取 DNS 查询 payload (buffer 已跳过 UDP 头部 8 字节, limit 为 DNS 负载长度)
            val dnsBuffer = buffer.slice()
            val dnsPayloadLen = dnsBuffer.remaining()
            dnsBuffer.limit(dnsPayloadLen)

            // 委托 DnsInterceptor 处理
            interceptor.processDnsQuery(dnsBuffer, srcIp, dstIp, srcPort, dstPort)
            stats.addPacket(dnsPayloadLen.toLong())
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleDnsPacket failed", e)
            stats.addError()
            return false
        }
    }

    /**
     * 构建 UDP 响应包 (用于 DNS 回程)
     */
    fun buildUdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipHeaderLen = 20
        val totalLen = ipHeaderLen + udpLen

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // IPv4 Header
        packet.put(0x45.toByte())
        packet.put(0x00)
        packet.putShort(totalLen.toShort())
        packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
        packet.putShort(0x4000.toShort())
        packet.put(64.toByte())
        packet.put(17.toByte()) // Protocol: UDP
        packet.putShort(0)
        packet.put(srcIp)
        packet.put(dstIp)

        // UDP Header
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort(udpLen.toShort())
        packet.putShort(0) // Checksum (optional for IPv4)

        // Payload
        packet.put(payload)

        // IP 校验和
        packet.position(0)
        val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
        packet.position(10)
        packet.putShort(ipChecksum)

        return packet.array()
    }
}
