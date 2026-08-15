package cn.srv0.sshinjector.domain.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IP/TCP/ICMPv6 校验和计算，纯函数无状态。
 */
object ChecksumCalculator {
    fun ipChecksum(
        header: ByteBuffer,
        headerLen: Int,
    ): Short {
        val savedPos = header.position()
        header.position(0)
        var sum = 0L
        val end = headerLen - (headerLen and 1)
        var pos = 0
        while (pos < end) {
            sum += (header.getShort().toInt() and 0xFFFF)
            pos += 2
        }
        if (headerLen and 1 != 0) {
            header.position(end)
            sum += (header.get().toInt() and 0xFF) shl 8
        }
        header.position(savedPos)
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv().toShort())
    }

    /**
     * TCP 校验和: 伪头 (src/dst IP + 长度/协议) + TCP 段 的 16-bit 和取反。
     * 直接在 packet 数组上流式计算, 不构造伪头临时 buffer (每段省 1 分配 + 1 整段拷贝)。
     * 调用方保证 TCP 头的 checksum 字段为 0 (计算时视为 0)。
     */
    fun tcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        ipOffset: Int,
        tcpLen: Int,
    ): Short {
        val isIPv6 = srcIp.size == 16
        var sum = 0L
        // 伪头: srcIp + dstIp
        for (i in srcIp.indices step 2) {
            sum += (((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF))
        }
        for (i in dstIp.indices step 2) {
            sum += (((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF))
        }
        // 伪头: 协议 + TCP 长度 (IPv6 用 32-bit 长度字段, IPv4 用 16-bit)
        if (isIPv6) {
            sum += 6
            sum += (tcpLen ushr 16) and 0xFFFF
        } else {
            sum += 6
        }
        sum += tcpLen and 0xFFFF
        // TCP 段 (头 + payload)
        val end = ipOffset + tcpLen
        var pos = ipOffset
        while (pos + 1 < end) {
            sum += (((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF))
            pos += 2
        }
        if (pos < end) {
            sum += (packet[pos].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }

    fun icmpv6Checksum(
        packet: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        icmpOffset: Int,
        icmpLen: Int,
    ): Short {
        // ICMPv6 伪头部: srcIp(16) + dstIp(16) + length(4) + zeros(3) + nextHeader(1)
        val pseudoHeader = ByteBuffer.allocate(40 + icmpLen)
        pseudoHeader.order(ByteOrder.BIG_ENDIAN)
        pseudoHeader.put(srcIp)
        pseudoHeader.put(dstIp)
        pseudoHeader.putInt(icmpLen)
        pseudoHeader.put(ByteArray(3)) // zeros
        pseudoHeader.put(58.toByte()) // Next Header: ICMPv6
        pseudoHeader.put(packet, icmpOffset, icmpLen)

        var sum = 0L
        pseudoHeader.position(0)
        var pos = 0
        while (pos < pseudoHeader.limit()) {
            sum += (pseudoHeader.getShort().toInt() and 0xFFFF)
            pos += 2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }
}
