package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.ChecksumCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * 校验和流式化回归测试: 新 tcpChecksum 直接在数组上计算, 不构造伪头 buffer。
 * 以旧实现 (伪头 ByteBuffer 版) 为黄金对照, 覆盖 IPv4/IPv6 与奇数长度 payload。
 */
class ChecksumCalculatorTest {
    @Test
    fun `streaming tcp checksum matches legacy pseudo-header implementation`() {
        val rng = Random(42)
        for (iteration in 0 until 200) {
            val isIPv6 = iteration % 2 == 0
            val srcIp = randomIp(rng, isIPv6)
            val dstIp = randomIp(rng, isIPv6)
            val payloadLen = rng.nextInt(2048)
            val tcpLen = 20 + payloadLen
            val ipOffset = if (isIPv6) 40 else 20

            val packet = ByteArray(ipOffset + tcpLen)
            rng.nextBytes(packet)

            val legacy = legacyTcpChecksum(srcIp, dstIp, packet, ipOffset, tcpLen)
            val streaming = ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet, ipOffset, tcpLen)
            assertEquals(
                "iteration=$iteration ipv6=$isIPv6 payloadLen=$payloadLen",
                legacy,
                streaming,
            )
        }
    }

    @Test
    fun `tcp checksum self-consistency - sum of pseudo header plus checksum is all-ones`() {
        val rng = Random(7)
        for (iteration in 0 until 100) {
            val isIPv6 = iteration % 2 == 0
            val srcIp = randomIp(rng, isIPv6)
            val dstIp = randomIp(rng, isIPv6)
            val payloadLen = rng.nextInt(1460)
            val tcpLen = 20 + payloadLen
            val ipOffset = if (isIPv6) 40 else 20

            val packet = ByteArray(ipOffset + tcpLen)
            rng.nextBytes(packet)
            // checksum 字段视为 0 (计算前), 模拟真实组包路径
            packet[ipOffset + 16] = 0
            packet[ipOffset + 17] = 0

            val checksum = ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet, ipOffset, tcpLen)
            packet[ipOffset + 16] = ((checksum.toInt() ushr 8) and 0xFF).toByte()
            packet[ipOffset + 17] = (checksum.toInt() and 0xFF).toByte()

            val total = pseudoHeaderSum(srcIp, dstIp, tcpLen, isIPv6) + segmentSum(packet, ipOffset, tcpLen)
            assertEquals(
                "iteration=$iteration: pseudo+segment+checksum should fold to 0xFFFF",
                0xFFFF,
                fold16(total),
            )
        }
    }

    @Test
    fun `odd length payload checksum matches legacy`() {
        val rng = Random(99)
        val srcIp = randomIp(rng, isIPv6 = false)
        val dstIp = randomIp(rng, isIPv6 = false)
        val payloadLen = 9 // 奇数, 校验和需补零
        val tcpLen = 20 + payloadLen
        val ipOffset = 20

        val packet = ByteArray(ipOffset + tcpLen)
        rng.nextBytes(packet)
        assertEquals(
            legacyTcpChecksum(srcIp, dstIp, packet, ipOffset, tcpLen),
            ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet, ipOffset, tcpLen),
        )
    }

    private fun legacyTcpChecksum(
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

    private fun pseudoHeaderSum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpLen: Int,
        isIPv6: Boolean,
    ): Int {
        var sum = 0
        for (i in srcIp.indices step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }
        for (i in dstIp.indices step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        if (isIPv6) {
            sum += 6
            sum += (tcpLen ushr 16) and 0xFFFF
        } else {
            sum += 6
        }
        return sum + (tcpLen and 0xFFFF)
    }

    private fun segmentSum(
        packet: ByteArray,
        ipOffset: Int,
        tcpLen: Int,
    ): Int {
        var sum = 0
        val end = ipOffset + tcpLen
        var pos = ipOffset
        while (pos + 1 < end) {
            sum += (((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF))
            pos += 2
        }
        if (pos < end) {
            sum += (packet[pos].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun fold16(sum: Int): Int {
        var s = sum
        while (s shr 16 != 0) {
            s = (s and 0xFFFF) + (s shr 16)
        }
        return s
    }

    private fun randomIp(
        rng: Random,
        isIPv6: Boolean,
    ): ByteArray {
        val bytes = ByteArray(if (isIPv6) 16 else 4)
        rng.nextBytes(bytes)
        return bytes
    }
}
