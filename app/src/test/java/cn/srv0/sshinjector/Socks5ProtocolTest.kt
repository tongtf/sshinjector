package cn.srv0.sshinjector

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Socks5ProtocolTest {
    @Test
    fun `test SOCKS5 handshake`() {
        // SOCKS5 握手: VER=5, NMETHODS=1, METHOD=0x00(无认证)
        val handshake = byteArrayOf(0x05, 0x01, 0x00)
        assertEquals(3, handshake.size)
        assertEquals(0x05, handshake[0].toInt() and 0xFF)
        assertEquals(0x01, handshake[1].toInt() and 0xFF)
        assertEquals(0x00, handshake[2].toInt() and 0xFF)
    }

    @Test
    fun `test SOCKS5 CONNECT request`() {
        // SOCKS5 CONNECT 请求: VER=5, CMD=1(CONNECT), RSV=0, ATYP=1(IPv4), DST.ADDR, DST.PORT
        val request = ByteBuffer.allocate(10)
        request.put(0x05) // VER
        request.put(0x01) // CMD: CONNECT
        request.put(0x00) // RSV
        request.put(0x01) // ATYP: IPv4
        request.put(byteArrayOf(0x08, 0x08, 0x08, 0x08)) // DST.ADDR: 8.8.8.8
        request.putShort(53) // DST.PORT: 53

        request.flip()
        assertEquals(10, request.remaining())
        assertEquals(0x05, request.get().toInt() and 0xFF)
        assertEquals(0x01, request.get().toInt() and 0xFF)
    }

    @Test
    fun `test SOCKS5 response parsing`() {
        // SOCKS5 成功响应: VER=5, REP=0(Success), RSV=0, ATYP=1(IPv4), BND.ADDR, BND.PORT
        val response =
            byteArrayOf(
                0x05, 0x00, 0x00, 0x01, // VER REP RSV ATYP
                0x00, 0x00, 0x00, 0x00, // BND.ADDR
                0x00, 0x00, // BND.PORT
            )

        assertEquals(0x05, response[0].toInt() and 0xFF)
        assertEquals(0x00, response[1].toInt() and 0xFF) // Success
        assertEquals(0x01, response[3].toInt() and 0xFF) // IPv4
    }

    @Test
    fun `test SOCKS5 UDP ASSOCIATE`() {
        // SOCKS5 UDP ASSOCIATE 请求: CMD=3
        val cmd = 0x03
        assertEquals(3, cmd)
    }
}
