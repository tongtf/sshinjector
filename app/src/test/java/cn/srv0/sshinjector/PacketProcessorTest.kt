package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.IpPacketParser
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
    // connection key 属于 IpPacketParser (生产五元组哈希), 早期版本这里复制了一份私有
    // 实现测副本 —— 生产算法零覆盖且两份算法不一致。改为直接测生产函数。
    @Test
    fun `connection key is deterministic`() {
        val srcIp = InetAddress.getByName("192.168.1.1")
        val dstIp = InetAddress.getByName("10.0.0.1")

        val key1 = IpPacketParser.connectionKey(srcIp, dstIp, 12345, 80)
        val key2 = IpPacketParser.connectionKey(srcIp, dstIp, 12345, 80)
        assertEquals(key1, key2)
    }

    @Test
    fun `connection key differs by port and direction`() {
        val ipA = InetAddress.getByName("192.168.1.1")
        val ipB = InetAddress.getByName("10.0.0.1")

        val key1 = IpPacketParser.connectionKey(ipA, ipB, 12345, 80)
        val key2 = IpPacketParser.connectionKey(ipA, ipB, 12346, 80)
        assertNotEquals(key1, key2)

        // 反向 (A:12345→B:80 vs B:80→A:12345) 是两条不同连接, 键必须区分
        val reverse = IpPacketParser.connectionKey(ipB, ipA, 80, 12345)
        assertNotEquals(key1, reverse)
    }

    @Test
    fun `connection key differs by ip`() {
        val src1 = InetAddress.getByName("192.168.1.1")
        val src2 = InetAddress.getByName("192.168.1.2")
        val dst = InetAddress.getByName("10.0.0.1")

        assertNotEquals(
            IpPacketParser.connectionKey(src1, dst, 12345, 80),
            IpPacketParser.connectionKey(src2, dst, 12345, 80),
        )
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
}
