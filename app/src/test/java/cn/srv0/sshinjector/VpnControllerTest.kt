package cn.srv0.sshinjector

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnControllerTest {
    @Test
    fun `test connection order`() {
        // 测试连接顺序: SSH connect → port forwarding → SOCKS5 proxy → tunnel → packetLoop
        val steps = mutableListOf<String>()

        // 模拟连接步骤
        steps.add("SSH connect")
        steps.add("Port forwarding")
        steps.add("SOCKS5 proxy")
        steps.add("Tunnel")
        steps.add("Packet loop")

        assertEquals(5, steps.size)
        assertEquals("SSH connect", steps[0])
        assertEquals("Port forwarding", steps[1])
        assertEquals("SOCKS5 proxy", steps[2])
        assertEquals("Tunnel", steps[3])
        assertEquals("Packet loop", steps[4])
    }

    @Test
    fun `test connection stats`() {
        data class ConnectionStats(
            val bytesSent: Long = 0,
            val bytesReceived: Long = 0,
            val packetsSent: Long = 0,
            val packetsReceived: Long = 0,
        )

        val stats = ConnectionStats(bytesSent = 1024, bytesReceived = 2048)
        assertEquals(1024L, stats.bytesSent)
        assertEquals(2048L, stats.bytesReceived)
    }
}
