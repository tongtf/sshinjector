package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.PacketProcessor
import cn.srv0.sshinjector.domain.vpn.SshIoDispatcher
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketProcessorTest {
    @Test
    fun `test connection key generation`() {
        val srcIp = InetAddress.getByName("192.168.1.1")
        val dstIp = InetAddress.getByName("10.0.0.1")
        val srcPort = 12345
        val dstPort = 80

        val key1 = generateConnectionKey(srcIp, dstIp, srcPort, dstPort)
        val key2 = generateConnectionKey(srcIp, dstIp, srcPort, dstPort)
        assertEquals(key1, key2)
    }

    @Test
    fun `test connection key uniqueness`() {
        val srcIp = InetAddress.getByName("192.168.1.1")
        val dstIp = InetAddress.getByName("10.0.0.1")

        val key1 = generateConnectionKey(srcIp, dstIp, 12345, 80)
        val key2 = generateConnectionKey(srcIp, dstIp, 12346, 80)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `malformed dns is dropped counted and never reprocessed (F12-e)`() {
        runBlocking {
            val processor = PacketProcessor(mock<TunnelManager>(), SshIoDispatcher())
            // UDP:53 + 畸形负载; DnsInterceptor 未注入 → 走 interceptor==null 的丢弃分支
            val packet = buildIpv4UdpPacket(dstPort = 53, payload = byteArrayOf(0x01, 0x02, 0x03))

            val handled = processor.processIpv4Packet(ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN))
            assertFalse("unhandled packet must be dropped, not returned for TUN re-injection", handled)
            withTimeout(3000) { processor.errors.first { it > 0 } }
        }
    }

    /** 最小 IPv4 + UDP 报文 (20B IP 头 + 8B UDP 头 + payload), 目的端口可指定。 */
    private fun buildIpv4UdpPacket(
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        return ByteBuffer
            .allocate(totalLen)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(0x45.toByte())
                put(0x00)
                putShort(totalLen.toShort())
                putShort(0x0001) // id
                putShort(0x4000) // DF
                put(64.toByte())
                put(17.toByte()) // UDP
                putShort(0) // checksum (接收路径不校验)
                put(InetAddress.getByName("10.0.0.2").address)
                put(InetAddress.getByName("8.8.8.8").address)
                // UDP 头
                putShort(40000.toShort())
                putShort(dstPort.toShort())
                putShort((8 + payload.size).toShort())
                putShort(0)
                put(payload)
            }.array()
    }

    private fun generateConnectionKey(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): Long {
        val srcHash = srcIp.hashCode().toLong()
        val dstHash = dstIp.hashCode().toLong()
        return (srcHash shl 32) xor (dstHash shl 16) xor (srcPort.toLong() shl 8) xor dstPort.toLong()
    }
}
