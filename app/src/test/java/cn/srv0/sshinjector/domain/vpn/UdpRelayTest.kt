package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNS 回程 UDP 组包。
 *
 * RFC 8200 §8.1: IPv6 上 UDP 校验和必填, 且算出 0 必须写成 **0xFFFF** ——
 * 0 是"无校验和"的保留非法值, 收端直接丢包 (表现是偶发一次 DNS 无响应)。
 * IPv4 的 0 才表示可选的"无校验和", 两个语义不能混。
 */
class UdpRelayTest {
    @Test
    fun `IPv6 UDP checksum of zero must be written as FFFF`() {
        val relay = UdpRelay(PacketStats())
        val src = InetAddress.getByName("fd00::2").address
        val dst = InetAddress.getByName("2001:4860:4860::8888").address

        // 暴力搜索: 高 16 位 payload 字覆盖全部 65536 种和, 必然命中 raw==0 的那个负载
        val payload = ByteArray(64)
        var hit = -1
        for (n in 0..0xFFFF) {
            payload[0] = (n shr 8).toByte()
            payload[1] = n.toByte()
            if (relay.udpChecksumV6(src, dst, udpHeader(payload)) == 0) {
                hit = n
                break
            }
        }
        require(hit >= 0) { "没有找到校验和为 0 的负载, 暴力搜索前提失效" }

        val packet = relay.buildUdpResponsePacket(src, dst, DNS_PORT, DNS_PORT, payload)
        val written =
            ((packet[IPV6_HDR_LEN + 6].toInt() and 0xFF) shl 8) or
                (packet[IPV6_HDR_LEN + 7].toInt() and 0xFF)

        assertEquals(
            "raw=0 的情况必须写成 0xFFFF (RFC 8200 §8.1), 写 0 会被收端当非法包丢掉",
            0xFFFF,
            written,
        )
    }

    @Test
    fun `IPv6 DNS response packet keeps its checksum field in range`() {
        val relay = UdpRelay(PacketStats())
        val src = InetAddress.getByName("fd00::2").address
        val dst = InetAddress.getByName("2001:4860:4860::8888").address
        val payload = ByteArray(120) { it.toByte() }

        val packet = relay.buildUdpResponsePacket(src, dst, DNS_PORT, DNS_PORT, payload)
        val written =
            ((packet[IPV6_HDR_LEN + 6].toInt() and 0xFF) shl 8) or
                (packet[IPV6_HDR_LEN + 7].toInt() and 0xFF)
        assertEquals(packet.size, IPV6_HDR_LEN + 8 + payload.size)
        assertTrue("IPv6 UDP 校验和不能是 0", written != 0)
    }

    /** 与 [UdpRelay.buildUdpResponsePacket] 内部组的 UDP 段逐字节一致 (csum 字段为 0)。 */
    private fun udpHeader(payload: ByteArray): ByteArray {
        val udpLen = UDP_HDR_LEN + payload.size
        return ByteBuffer
            .allocate(udpLen)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(DNS_PORT.toShort())
                putShort(DNS_PORT.toShort())
                putShort(udpLen.toShort())
                putShort(0)
                put(payload)
            }.array()
    }

    private companion object {
        const val DNS_PORT = 53
        const val IPV6_HDR_LEN = 40
        const val UDP_HDR_LEN = 8
    }
}
