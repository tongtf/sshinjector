package cn.srv0.sshinjector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.InetAddress

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
