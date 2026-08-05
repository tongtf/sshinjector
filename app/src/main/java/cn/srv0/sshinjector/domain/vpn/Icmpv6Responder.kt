package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

/**
 * ICMPv6 邻居发现 (ND) 响应：处理 NS/RS 并回 NA/RA。
 */
class Icmpv6Responder(
    private val tunWriterProvider: () -> ((ByteArray) -> Unit)?
) {
    companion object {
        private const val TAG = "PacketProcessor"
        private val vpnGatewayIpv6 = InetAddress.getByName("fd00::1")
        // 稳定单播本地管理 MAC (02:00:00:00:00:01)，替代全零 MAC (00:00:00:00:00:00 为保留非法地址)
        private val gatewayMac = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x01)
    }

    fun processIcmpv6Packet(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int
    ): Boolean {
        if (payloadLength < 4) return false

        buffer.position(payloadStart)
        val type = buffer.get().toInt() and 0xFF
        val code = buffer.get().toInt() and 0xFF
        buffer.getShort() // checksum

        // ICMPv6 类型: 133=Router Solicitation, 134=Router Advertisement,
        // 135=Neighbor Solicitation, 136=Neighbor Advertisement
        return when (type) {
            135 -> handleNeighborSolicitation(buffer, srcIp, payloadStart, payloadLength)
            133 -> handleRouterSolicitation(srcIp)
            else -> {
                if (IS_DEBUG) Log.d(TAG, "ICMPv6 type=$type code=$code not handled, dropping")
                false
            }
        }
    }

    private fun handleNeighborSolicitation(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int
    ): Boolean {
        // NS: Type(1) Code(1) Checksum(2) Reserved(4) TargetAddr(16) Options...
        if (payloadLength < 24) return false

        buffer.position(payloadStart + 4) // Skip type, code, checksum, reserved
        val targetAddr = ByteArray(16)
        buffer.get(targetAddr)

        // 检查是否在查询我们的 VPN 网关地址
        if (!targetAddr.contentEquals(vpnGatewayIpv6.address)) {
            return false
        }

        // 构造 Neighbor Advertisement 响应
        val writer = tunWriterProvider() ?: return false
        val naPacket = buildNeighborAdvertisement(
            srcIp = vpnGatewayIpv6.address,
            dstIp = srcIp.address,
            targetAddr = targetAddr
        )
        writer(naPacket)
        if (IS_DEBUG) Log.d(TAG, "Sent Neighbor Advertisement for $targetAddr")
        return true
    }

    private fun handleRouterSolicitation(srcIp: InetAddress): Boolean {
        // 响应 Router Advertisement
        val writer = tunWriterProvider() ?: return false
        val raPacket = buildRouterAdvertisement(
            srcIp = vpnGatewayIpv6.address,
            dstIp = srcIp.address
        )
        writer(raPacket)
        if (IS_DEBUG) Log.d(TAG, "Sent Router Advertisement")
        return true
    }

    private fun buildNeighborAdvertisement(
        srcIp: ByteArray, dstIp: ByteArray,
        targetAddr: ByteArray
    ): ByteArray {
        // IPv6 固定头部 40 字节 + ICMPv6 NA 24 字节 + 选项
        val icmpLen = 24 + 8 // NA + Target Link-layer Address Option
        val ipHeaderLen = 40
        val totalLen = ipHeaderLen + icmpLen

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // IPv6 Header
        packet.putInt(0x60000000) // Version=6, TC=0, Flow=0
        packet.putShort(icmpLen.toShort()) // Payload length
        packet.put(58.toByte()) // Next Header: ICMPv6
        packet.put(64.toByte()) // Hop Limit
        packet.put(srcIp)
        packet.put(dstIp)

        // ICMPv6 Neighbor Advertisement
        packet.put(136.toByte()) // Type: Neighbor Advertisement
        packet.put(0.toByte())   // Code: 0
        packet.putShort(0)       // Checksum (计算后填入)
        packet.put(0xC0.toByte()) // Flags: R=0, S=1, O=1
        packet.put(0.toByte())   // Reserved
        packet.putInt(0)         // Reserved
        packet.put(targetAddr)   // Target Address

        // Target Link-layer Address Option (使用稳定本地管理 MAC)
        packet.put(2.toByte())   // Type: Target Link-layer Address
        packet.put(1.toByte())   // Length: 1 (in units of 8 bytes = 8 bytes)
        packet.put(gatewayMac)   // MAC address (6 bytes) + 2 bytes padding

        // 计算 ICMPv6 校验和 (包含伪头部)
        val icmpStart = ipHeaderLen
        val icmpChecksum = ChecksumCalculator.icmpv6Checksum(packet.array(), srcIp, dstIp, icmpStart, icmpLen)
        packet.position(icmpStart + 2)
        packet.putShort(icmpChecksum)

        return packet.array()
    }

    private fun buildRouterAdvertisement(
        srcIp: ByteArray, dstIp: ByteArray
    ): ByteArray {
        val icmpLen = 16 + 8 // RA + Source Link-layer Address Option
        val ipHeaderLen = 40
        val totalLen = ipHeaderLen + icmpLen

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        // IPv6 Header
        packet.putInt(0x60000000)
        packet.putShort(icmpLen.toShort())
        packet.put(58.toByte()) // Next Header: ICMPv6
        packet.put(255.toByte()) // Hop Limit: 255 (链路本地)
        packet.put(srcIp)
        packet.put(dstIp)

        // ICMPv6 Router Advertisement
        packet.put(134.toByte()) // Type: Router Advertisement
        packet.put(0.toByte())   // Code: 0
        packet.putShort(0)       // Checksum
        packet.put(64.toByte())  // Cur Hop Limit: 64
        packet.put(0.toByte())   // Flags: M=0, O=0
        packet.putShort(1800.toShort()) // Router Lifetime: 1800s
        packet.putInt(0)         // Reachable Time: 0 (unspecified)
        packet.putInt(0)         // Retrans Timer: 0 (unspecified)

        // Source Link-layer Address Option (使用稳定本地管理 MAC)
        packet.put(1.toByte())   // Type: Source Link-layer Address
        packet.put(1.toByte())   // Length: 1
        packet.put(gatewayMac)   // MAC (6 bytes) + 2 padding

        // 计算 ICMPv6 校验和
        val icmpStart = ipHeaderLen
        val icmpChecksum = ChecksumCalculator.icmpv6Checksum(packet.array(), srcIp, dstIp, icmpStart, icmpLen)
        packet.position(icmpStart + 2)
        packet.putShort(icmpChecksum)

        return packet.array()
    }
}
