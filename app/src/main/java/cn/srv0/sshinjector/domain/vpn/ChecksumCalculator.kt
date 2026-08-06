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

    fun tcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        ipOffset: Int,
        tcpLen: Int,
    ): Short {
        val isIPv6 = srcIp.size == 16
        val paddedLen = tcpLen + (tcpLen and 1)
        val pseudoHeader =
            if (isIPv6) {
                ByteBuffer.allocate(40 + paddedLen).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    put(srcIp)
                    put(dstIp)
                    putInt(tcpLen)
                    put(ByteArray(3))
                    put(6)
                    put(packet, ipOffset, tcpLen)
                    if (paddedLen != tcpLen) put(0)
                }
            } else {
                ByteBuffer.allocate(12 + paddedLen).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    put(srcIp)
                    put(dstIp)
                    put(0)
                    put(6)
                    putShort(tcpLen.toShort())
                    put(packet, ipOffset, tcpLen)
                    if (paddedLen != tcpLen) put(0)
                }
            }

        var sum = 0L
        pseudoHeader.position(0)
        while (pseudoHeader.remaining() >= 2) {
            sum += (pseudoHeader.getShort().toInt() and 0xFFFF)
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
