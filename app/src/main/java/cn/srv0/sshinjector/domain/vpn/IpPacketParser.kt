package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

/**
 * IP/TCP/UDP 包头解析，纯函数无状态。
 */
object IpPacketParser {
    private const val TAG = "PacketProcessor"

    /**
     * 解析 IPv4 头，返回协议、源/目的地址与 payload 区间。
     * @param buffer 位置将停在 payload 起始处
     */
    fun parseIpv4Header(buffer: ByteBuffer): ParsedPacket? {
        buffer.order(ByteOrder.BIG_ENDIAN)

        // 最小 IPv4 头部 20 字节
        if (buffer.remaining() < 20) return null

        val versionIhl = buffer.get().toInt() and 0xFF
        val version = versionIhl shr 4
        val ihl = versionIhl and 0x0F

        if (version != 4) return null
        if (ihl < 5) return null

        val headerLength = ihl * 4
        if (buffer.remaining() < headerLength - 1) return null

        // 跳过 DSCP/ECN, Total Length
        buffer.position(buffer.position() + 1 + 2)

        buffer.getShort() // identification
        buffer.getShort() // flags/fragment
        buffer.get() // ttl
        val protocol = buffer.get().toInt() and 0xFF
        buffer.getShort() // headerChecksum

        val srcIp = readIpv4Address(buffer)
        val dstIp = readIpv4Address(buffer)

        // 处理选项
        if (ihl > 5) {
            buffer.position(buffer.position() + (ihl - 5) * 4)
        }

        val payloadStart = buffer.position()
        val payloadLength = buffer.remaining()

        return ParsedPacket(srcIp, dstIp, protocol, payloadStart, payloadLength)
    }

    /**
     * 解析 IPv6 头（含扩展头），返回协议、源/目的地址与 payload 区间。
     * @param buffer 位置将停在最终 payload 起始处
     */
    fun parseIpv6Header(buffer: ByteBuffer): ParsedPacket? {
        buffer.order(ByteOrder.BIG_ENDIAN)

        // IPv6 固定头部 40 字节
        if (buffer.remaining() < 40) return null

        val versionTrafficClassFlow = buffer.getInt()
        val version = versionTrafficClassFlow shr 28

        if (version != 6) return null

        val payloadLength = buffer.getShort().toInt() and 0xFFFF
        var nextHeader = buffer.get().toInt() and 0xFF
        buffer.get() // hop limit

        val srcIp = readIpv6Address(buffer)
        val dstIp = readIpv6Address(buffer)

        // 解析 IPv6 扩展头部，找到最终的上层协议
        val (payloadStart, finalNextHeader) = parseIpv6ExtensionHeaders(buffer, payloadLength, nextHeader)
        if (payloadStart < 0) return null

        // 重新计算实际 payload 长度 (扣除扩展头部)
        val extensionHeaderLen = payloadStart - 40
        val actualPayloadLength = payloadLength - extensionHeaderLen
        if (actualPayloadLength < 0) return null

        return ParsedPacket(srcIp, dstIp, finalNextHeader, payloadStart, actualPayloadLength)
    }

    /**
     * 解析 IPv6 扩展头部
     * @return payload 起始位置，负数表示错误
     */
    private fun parseIpv6ExtensionHeaders(buffer: ByteBuffer, payloadLength: Int, nextHeader: Int): Pair<Int, Int> {
        var pos = buffer.position()
        val maxPos = pos + payloadLength
        var currentNextHeader = nextHeader

        // IPv6 扩展头部类型
        val extensionHeaders = setOf(
            0,   // Hop-by-Hop Options
            43,  // Routing
            44,  // Fragment
            60,  // Destination Options
            255  // Reserved (实验性)
        )

        // 限制扩展头部数量防止循环
        var extHeaderCount = 0
        while (currentNextHeader in extensionHeaders && extHeaderCount < 10) {
            if (pos + 2 > maxPos) return Pair(-1, currentNextHeader)

            val extType = buffer.get(pos).toInt() and 0xFF
            val extLen = (buffer.get(pos + 1).toInt() and 0xFF) + 1 // 单位是 8 字节

            val extHeaderLen = when (currentNextHeader) {
                44 -> 8 // Fragment header 固定 8 字节
                else -> extLen * 8
            }

            if (pos + extHeaderLen > maxPos) return Pair(-1, currentNextHeader)

            // 更新 nextHeader 为扩展头部中的 Next Header 字段
            currentNextHeader = buffer.get(pos).toInt() and 0xFF
            pos += extHeaderLen
            extHeaderCount++
        }

        buffer.position(pos)
        return Pair(pos, currentNextHeader)
    }

    private fun readIpv4Address(buffer: ByteBuffer): InetAddress {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return InetAddress.getByAddress(bytes)
    }

    private fun readIpv6Address(buffer: ByteBuffer): InetAddress {
        val bytes = ByteArray(16)
        buffer.get(bytes)
        return InetAddress.getByAddress(bytes)
    }

    /**
     * 五元组连接标识符
     */
    fun connectionKey(srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int): Long {
        val srcBytes = srcIp.address
        val dstBytes = dstIp.address
        val isSrc6 = srcBytes.size == 16
        val isDst6 = dstBytes.size == 16

        val srcLow = if (isSrc6) {
            (srcBytes[12].toLong() and 0xFF shl 24) or
                (srcBytes[13].toLong() and 0xFF shl 16) or
                (srcBytes[14].toLong() and 0xFF shl 8) or
                (srcBytes[15].toLong() and 0xFF)
        } else {
            (srcBytes[0].toLong() and 0xFF shl 24) or
                (srcBytes[1].toLong() and 0xFF shl 16) or
                (srcBytes[2].toLong() and 0xFF shl 8) or
                (srcBytes[3].toLong() and 0xFF)
        }

        val dstLow = if (isDst6) {
            (dstBytes[12].toLong() and 0xFF shl 24) or
                (dstBytes[13].toLong() and 0xFF shl 16) or
                (dstBytes[14].toLong() and 0xFF shl 8) or
                (dstBytes[15].toLong() and 0xFF)
        } else {
            (dstBytes[0].toLong() and 0xFF shl 24) or
                (dstBytes[1].toLong() and 0xFF shl 16) or
                (dstBytes[2].toLong() and 0xFF shl 8) or
                (dstBytes[3].toLong() and 0xFF)
        }

        return (srcLow shl 48) xor
            (dstLow shl 32) xor
            (srcPort.toLong() shl 16) xor
            dstPort.toLong()
    }

    data class ParsedPacket(
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val protocol: Int,
        val payloadStart: Int,
        val payloadLength: Int
    )
}
