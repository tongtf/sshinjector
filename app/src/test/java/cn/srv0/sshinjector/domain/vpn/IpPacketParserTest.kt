package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TUN 一次 read 里粘连多个 IP 包时的定界语义 (`IpPacketParser.ipPacketTotalLength`), 以及
 * DNS 绕过转发用的 IP 头长 / UDP 载荷偏移 (`ipHeaderLength` / `udpPayloadOffset`)。
 *
 * 回归背景: `parseIpv4Header` 的 `payloadLength = buffer.remaining()` 是**到 buffer 末尾**的长度,
 * 多包时会把后续包整段算进第 1 包的 payload —— 隧道收到被污染的字节流 + 第 1 包的 ACK 推过了头,
 * 而后续包又被丢弃。定界只能用 IP 头自带的 total length。
 * IPv6 的坑在 bytes[2..5] 是 traffic class/flow label, 长度是 bytes[4..5] + 40。
 * UDP 载荷的坑是漏减 8 字节 UDP 头, 见 `udp payload offset...` 用例。
 */
class IpPacketParserTest {
    // ---------------------------------------------------------------- 正常帧

    @Test
    fun `IPv4 total length comes from the header field not the buffer end`() {
        val pkt = ipv4Packet(ByteArray(100))
        assertEquals(120, IpPacketParser.ipPacketTotalLength(buffer(pkt), 0))
    }

    @Test
    fun `stitched IPv4 packets are delimited one by one`() {
        val a = ipv4Packet(ByteArray(100))
        val b = ipv4Packet(ByteArray(40))
        val c = ipv4Packet(ByteArray(500))
        val stitched = a + b + c
        assertEquals(listOf(120, 60, 520), frameAll(buffer(stitched)))
        assertEquals(700, stitched.size)
    }

    @Test
    fun `IPv6 total length is 40 byte header plus payload length`() {
        val pkt = ipv6Packet(ByteArray(1000))
        assertEquals(1040, IpPacketParser.ipPacketTotalLength(buffer(pkt), 0))
    }

    @Test
    fun `stitched IPv6 packets are delimited one by one`() {
        val a = ipv6Packet(ByteArray(1000))
        val b = ipv6Packet(ByteArray(60))
        assertEquals(listOf(1040, 100), frameAll(buffer(a + b)))
    }

    /**
     * bytes[2..3] 是 traffic class / flow label 的一部分, 不是长度字段。
     * 读偏移 2 会得到 0xFFFF = 65535, 读偏移 4..5 + 40 才是 1040。
     */
    @Test
    fun `IPv6 flow label bytes are never mistaken for the length field`() {
        val pkt =
            ipv6Packet(ByteArray(1000)).also {
                it[2] = 0xFF.toByte()
                it[3] = 0xFF.toByte()
            }
        // 读偏移 2..3 会得到 0xFFFF=65535 (再 +40 = 65575), 只有读 4..5 才是 1040
        assertEquals(1040, IpPacketParser.ipPacketTotalLength(buffer(pkt), 0))
    }

    @Test
    fun `mixed IPv4 and IPv6 frames keep their own boundaries`() {
        val v4 = ipv4Packet(ByteArray(60))
        val v6 = ipv6Packet(ByteArray(200))
        assertEquals(listOf(80, 240), frameAll(buffer(v4 + v6)))
    }

    /** 长度字段比实际字节短 (报文被截断): 定界取字段值, 由调用方判定越界。 */
    @Test
    fun `a truncated frame reports the header field so the caller can reject it`() {
        val pkt = ipv4Packet(ByteArray(100))
        val truncated = pkt.copyOf(60)
        val total = IpPacketParser.ipPacketTotalLength(buffer(truncated), 0)
        assertEquals(120, total)
        assertTrue("caller must reject a frame longer than the buffer", total > truncated.size)
    }

    // ---------------------------------------------------------------- 非法帧

    @Test
    fun `malformed frames return minus one`() {
        // 版本未知 (5)
        val version5 = ipv4Packet(ByteArray(10))
        version5[0] = 0x55
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(version5), 0))

        // IPv4 total length < IHL*4 (头装不下自己声称的长度)
        val badLen = ipv4Packet(ByteArray(100))
        badLen[2] = 0x00
        badLen[3] = 10
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(badLen), 0))

        // IHL < 5 (非法头长)
        val badIhl = ipv4Packet(ByteArray(100))
        badIhl[0] = 0x44
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(badIhl), 0))

        // IPv6 头不足 40 字节
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(ipv6Packet(ByteArray(100)).copyOf(39)), 0))

        // IPv4 头不足 20 字节
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(ipv4Packet(ByteArray(100)).copyOf(19)), 0))
    }

    @Test
    fun `an out of range start index returns minus one`() {
        val pkt = ipv4Packet(ByteArray(10))
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(pkt), -1))
        assertEquals(-1, IpPacketParser.ipPacketTotalLength(buffer(pkt), pkt.size))
    }

    // ------------------------------------------------ UDP 载荷偏移 (DNS 绕过转发)

    /**
     * DNS 绕过转发 (`VpnController.forwardDnsBypassPacket`) 取载荷时必须同时跳过 IP 头和
     * **8 字节 UDP 头**。少减 8 = 把 srcPort/dstPort/len/cksum 当 DNS 消息发出去,
     * 对端把 dstPort=0x0035 当成 QDCOUNT 直接丢包 → 本地 5s 超时, 而日志只报
     * "DNS 响应超时", 排障方向被完全带偏。
     */
    @Test
    fun `udp payload offset skips both the ip header and the 8 byte udp header`() {
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)

        val v4 = udpV4Packet(ihlWords = 5, dns = dns)
        assertEquals("IPv4 无选项: 20 + 8", 28, IpPacketParser.udpPayloadOffset(v4, 4))
        assertArrayEquals("载荷必须正好是 DNS 消息", dns, v4.copyOfRange(28, v4.size))

        val v6 = udpV6Packet(dns = dns)
        assertEquals("IPv6: 40 + 8", 48, IpPacketParser.udpPayloadOffset(v6, 6))
        assertArrayEquals("载荷必须正好是 DNS 消息", dns, v6.copyOfRange(48, v6.size))
    }

    /** IPv4 带选项时 IHL > 5, 头长必须跟着变 —— 硬编码 20 会读到选项字节。 */
    @Test
    fun `ipv4 options extend the header so the payload offset follows`() {
        val dns = ByteArray(16)
        val v4 = udpV4Packet(ihlWords = 6, dns = dns) // 24 字节头
        assertEquals(24, IpPacketParser.ipHeaderLength(v4, 4))
        assertEquals(32, IpPacketParser.udpPayloadOffset(v4, 4))
    }

    @Test
    fun `unparsable headers make the offset minus one so the caller can drop the packet`() {
        assertEquals(-1, IpPacketParser.ipHeaderLength(ByteArray(10), 4)) // 装不下最小头
        assertEquals(-1, IpPacketParser.udpPayloadOffset(ByteArray(10), 4))
        assertEquals(-1, IpPacketParser.udpPayloadOffset(ByteArray(40), 6)) // 头刚好占满, 无 UDP 头
        assertEquals(-1, IpPacketParser.ipHeaderLength(udpV4Packet(5, ByteArray(4)), 5)) // 版本未知
    }

    // ---------------------------------------------------------------- helpers

    /** IPv4 报文: 可变 IHL 的 IP 头 + 8 字节 UDP 头 + 载荷。 */
    private fun udpV4Packet(
        ihlWords: Int,
        dns: ByteArray,
    ): ByteArray {
        val ipHeaderLen = ihlWords * 4
        val total = ipHeaderLen + 8 + dns.size
        val head = ByteArray(ipHeaderLen)
        head[0] = (0x40 or ihlWords).toByte() // version=4 | IHL
        head[2] = (total shr 8).toByte()
        head[3] = total.toByte()
        head[9] = 17 // UDP
        return head + udpHeader(dns.size) + dns
    }

    /** IPv6 报文: 40 字节固定头 + 8 字节 UDP 头 + 载荷。 */
    private fun udpV6Packet(dns: ByteArray): ByteArray {
        val payloadLen = 8 + dns.size
        val head = ByteArray(40)
        head[0] = 0x60 // version=6
        head[4] = (payloadLen shr 8).toByte()
        head[5] = payloadLen.toByte()
        head[6] = 17 // next header = UDP
        head[7] = 64 // hop limit
        return head + udpHeader(dns.size) + dns
    }

    /** dstPort=53 是关键: 少跳这 8 字节时它会被对端当成 QDCOUNT。 */
    private fun udpHeader(payloadLen: Int): ByteArray {
        val udp = ByteArray(8)
        udp[0] = 0xC0.toByte()
        udp[1] = 0x00 // src 49152
        udp[2] = 0x00
        udp[3] = 53 // dst DNS
        val len = 8 + payloadLen
        udp[4] = (len shr 8).toByte()
        udp[5] = len.toByte()
        return udp
    }

    // ------------------------------------------------------------ connectionKey 零分配路径

    /**
     * 五元组 key 就是连接身份: 位布局一旦漂移, 回程数据会被投给别的连接 (或连接凭空消失)。
     * 这里用**独立于生产实现**的参考公式 (直接拼字节, 见 [referenceKey]) 锁定, 再显式断言
     * 两个地址各自取了哪 4 字节 —— 参考公式与生产实现同时改错才会漏过。
     */
    @Test
    fun `connection key layout is locked for IPv4 and IPv6`() {
        val v4src = InetAddress.getByName("10.0.0.2")
        val v4dst = InetAddress.getByName("192.168.1.1")
        assertEquals(0x0A000002L, IpPacketParser.addressLow32(v4src))
        assertEquals(0xC0A80101L, IpPacketParser.addressLow32(v4dst))
        assertEquals(
            referenceKey(v4src.address, v4dst.address, 12345, 80),
            IpPacketParser.connectionKey(v4src, v4dst, 12345, 80),
        )
        assertEquals(
            IpPacketParser.connectionKey(0x0A000002L, 0xC0A80101L, 12345, 80),
            IpPacketParser.connectionKey(v4src, v4dst, 12345, 80),
        )

        // IPv6 只取**后 4 字节** (历史行为, 不改成前 4 字节 —— 改了 key 就变)。
        val v6src = InetAddress.getByName("2001:db8::1")
        val v6dst = InetAddress.getByName("fd00::abcd:1234")
        assertEquals(0x00000001L, IpPacketParser.addressLow32(v6src))
        assertEquals(0xABCD1234L, IpPacketParser.addressLow32(v6dst))
        assertEquals(
            referenceKey(v6src.address, v6dst.address, 443, 54321),
            IpPacketParser.connectionKey(v6src, v6dst, 443, 54321),
        )
    }

    /** 直接从 buffer 绝对下标取低 32 位: IPv4 取 offset 起 4 字节, IPv6 取后 4 字节。 */
    @Test
    fun `address low32 from buffer matches the InetAddress version`() {
        val v4 = ipv4With("10.0.0.2", "192.168.1.1")
        assertEquals(0x0A000002L, IpPacketParser.addressLow32(buffer(v4), 4, 12))
        assertEquals(0xC0A80101L, IpPacketParser.addressLow32(buffer(v4), 4, 16))

        val v6 = ipv6With("2001:db8::1", "fd00::abcd:1234")
        assertEquals(0x00000001L, IpPacketParser.addressLow32(buffer(v6), 6, 8))
        assertEquals(0xABCD1234L, IpPacketParser.addressLow32(buffer(v6), 6, 24))
    }

    /** 解析出的 `srcLow`/`dstLow` 必须与逐字段算出的值一致 (零分配路径不许引入偏差)。 */
    @Test
    fun `parsed packet carries low32 values matching the addresses`() {
        val v4 = ipv4With("10.0.0.2", "192.168.1.1")
        val parsed4 = IpPacketParser.parseIpv4Header(buffer(v4))
        assertEquals(0x0A000002L, parsed4!!.srcLow)
        assertEquals(0xC0A80101L, parsed4.dstLow)
        assertEquals(IpPacketParser.addressLow32(parsed4.srcIp), parsed4.srcLow)

        val v6 = ipv6With("2001:db8::1", "fd00::abcd:1234")
        val parsed6 = IpPacketParser.parseIpv6Header(buffer(v6))
        assertEquals(0x00000001L, parsed6!!.srcLow)
        assertEquals(0xABCD1234L, parsed6.dstLow)
        assertEquals(IpPacketParser.addressLow32(parsed6.dstIp), parsed6.dstLow)
    }

    /** 按 `ipPacketTotalLength` 步进走完整个缓冲区, 返回每个包的定界长度。 */
    private fun frameAll(buffer: ByteBuffer): List<Int> {
        val outerLimit = buffer.limit()
        val sizes = mutableListOf<Int>()
        var start = 0
        while (start < outerLimit) {
            val total = IpPacketParser.ipPacketTotalLength(buffer, start)
            assertTrue("无法在 offset=$start 定界 (limit=$outerLimit)", total > 0)
            assertTrue("包 $total 字节越过缓冲区末尾", start + total <= outerLimit)
            sizes += total
            start += total
        }
        return sizes
    }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    private fun ipv4Packet(payload: ByteArray): ByteArray {
        val total = 20 + payload.size
        val head = ByteArray(20)
        head[0] = 0x45 // version=4, IHL=5
        head[2] = (total shr 8).toByte()
        head[3] = total.toByte()
        head[9] = 6 // TCP
        return head + payload
    }

    private fun ipv6Packet(payload: ByteArray): ByteArray {
        val head = ByteArray(40)
        head[0] = 0x60 // version=6
        head[4] = (payload.size shr 8).toByte()
        head[5] = payload.size.toByte()
        head[6] = 6 // next header = TCP
        head[7] = 64 // hop limit
        return head + payload
    }

    private fun ipv4With(
        src: String,
        dst: String,
    ): ByteArray {
        val pkt = ipv4Packet(ByteArray(8))
        System.arraycopy(InetAddress.getByName(src).address, 0, pkt, 12, 4)
        System.arraycopy(InetAddress.getByName(dst).address, 0, pkt, 16, 4)
        return pkt
    }

    private fun ipv6With(
        src: String,
        dst: String,
    ): ByteArray {
        val pkt = ipv6Packet(ByteArray(8))
        System.arraycopy(InetAddress.getByName(src).address, 0, pkt, 8, 16)
        System.arraycopy(InetAddress.getByName(dst).address, 0, pkt, 24, 16)
        return pkt
    }

    /**
     * **独立于生产实现**的参考公式: 低位取样 + 位布局按 `AGENTS.md`/历史实现写死。
     * IPv4 取 bytes[0..3]; IPv6 取 bytes[12..15]。
     */
    private fun referenceKey(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
    ): Long {
        fun low(b: ByteArray): Long {
            val off = if (b.size == 16) 12 else 0
            return ((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF)
        }
        return (low(src) shl 48) xor (low(dst) shl 32) xor (srcPort.toLong() shl 16) xor dstPort.toLong()
    }
}
