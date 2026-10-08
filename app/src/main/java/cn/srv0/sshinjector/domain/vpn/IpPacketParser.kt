package cn.srv0.sshinjector.domain.vpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IP/TCP/UDP 包头解析，纯函数无状态。
 */
object IpPacketParser {
    // IPv6 扩展头部类型: Hop-by-Hop(0), Routing(43), Fragment(44), Destination Options(60), Reserved(255)
    private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 60, 255)

    /** UDP 头固定 8 字节 (RFC 768)。 */
    private const val UDP_HEADER_LEN = 8

    /**
     * IP 头字节数: IPv4 = IHL*4 (**含选项**, 别硬编码 20), IPv6 = 40 固定头。
     *
     * @param packet 从 IP 头开始的完整报文
     * @param version IP 版本号 (4 / 6)
     * @return 头长; 版本未知 / IHL 非法 / 缓冲区不足返回 -1
     */
    fun ipHeaderLength(
        packet: ByteArray,
        version: Int,
    ): Int =
        when (version) {
            4 ->
                if (packet.size < 20) {
                    -1
                } else {
                    val ihl = (packet[0].toInt() and 0x0F) * 4
                    if (ihl < 20 || ihl > packet.size) -1 else ihl
                }
            6 -> if (packet.size < 40) -1 else 40
            else -> -1
        }

    /**
     * UDP 载荷 (DNS 查询应答等) 在整个 IP 报文里的起始下标 = IP 头 + **8 字节 UDP 头**。
     *
     * 少加这 8 字节会把 UDP 头 (srcPort/dstPort/len/cksum) 一起当载荷发出去: 对端把
     * dstPort=0x0035 当成 QDCOUNT → 直接丢包 → 本地 5s 超时, 而日志只报"DNS 响应超时",
     * 排障方向被完全带偏。同文件的 portOffset / UdpRelay 的剥离逻辑都要跳过这 8 字节。
     *
     * @param packet 从 IP 头开始的完整报文
     * @param version IP 版本号 (4 / 6)
     * @return 载荷起始下标; IP 头非法或装不下 UDP 头返回 -1
     */
    fun udpPayloadOffset(
        packet: ByteArray,
        version: Int,
    ): Int {
        val ipHeaderLen = ipHeaderLength(packet, version)
        if (ipHeaderLen < 0) return -1
        val offset = ipHeaderLen + UDP_HEADER_LEN
        if (packet.size - offset < 1) return -1
        return offset
    }

    /**
     * 从 [packet] 的 [offset] 起读大端 u16 (UDP 端口 / 长度等)。
     * 越界返回 -1 —— 调用方据此丢包, 而不是读到隔壁字段当端口。
     */
    fun readU16(
        packet: ByteArray,
        offset: Int,
    ): Int {
        if (offset < 0 || offset + 1 >= packet.size) return -1
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    /**
     * 从 [buffer] 的绝对下标 [start] 处读出该 IP 包的**总长度** (IP 头自带的长度字段, 含头)。
     *
     * 粘连包定界必须用它: `parseIpv4Header` 的 `payloadLength = buffer.remaining()` 是**到 buffer
     * 末尾**的长度, 一次 TUN read 里有多个包时会把后续包整段算进第 1 包的 payload —— 隧道收到的是
     * 被污染的字节流 + 第 1 包的 ACK 推过了头, 而后续包又被丢掉。
     *
     * 用**绝对下标**而非当前 position: `parseIpv4Header` / `processTcpPacket` 都会推进 position,
     * 读 total length 必须回到包起点。IPv4 取 bytes[2..3]; IPv6 取 bytes[4..5] + 40
     * (bytes[2..5] 是 traffic class/flow label, 不是长度)。
     *
     * @return 总字节数 (>=20); -1 = 版本未知/长度字段非法/缓冲区不足
     */
    fun ipPacketTotalLength(
        buffer: ByteBuffer,
        start: Int,
    ): Int {
        if (start < 0 || start >= buffer.limit()) return -1
        val version = (buffer.get(start).toInt() shr 4) and 0x0F
        return when (version) {
            4 -> ipv4TotalLength(buffer, start)
            6 -> ipv6TotalLength(buffer, start)
            else -> -1
        }
    }

    /** IPv4: total length = bytes[2..3] (不含前缀), 必须至少装下 IHL*4 的头。 */
    private fun ipv4TotalLength(
        buffer: ByteBuffer,
        start: Int,
    ): Int {
        if (buffer.limit() - start < 20) return -1
        val ihl = buffer.get(start).toInt() and 0x0F
        if (ihl < 5) return -1
        val total =
            ((buffer.get(start + 2).toInt() and 0xFF) shl 8) or
                (buffer.get(start + 3).toInt() and 0xFF)
        return if (total >= ihl * 4) total else -1
    }

    /** IPv6: 总长 = 40 字节头 + payload length (bytes[4..5])。bytes[2..5] 是 flow label, 不是长度。 */
    private fun ipv6TotalLength(
        buffer: ByteBuffer,
        start: Int,
    ): Int {
        if (buffer.limit() - start < 40) return -1
        val payloadLen =
            ((buffer.get(start + 4).toInt() and 0xFF) shl 8) or
                (buffer.get(start + 5).toInt() and 0xFF)
        return 40 + payloadLen
    }

    /**
     * 解析 IPv4 头，返回协议、源/目的地址与 payload 区间。
     * @param buffer 位置将停在 payload 起始处
     */
    fun parseIpv4Header(buffer: ByteBuffer): ParsedPacket? {
        buffer.order(ByteOrder.BIG_ENDIAN)

        // 最小 IPv4 头部 20 字节
        if (buffer.remaining() < 20) return null

        val versionIhl = buffer.get().toInt() and 0xFF
        val version = versionIhl shr 4
        val ihl = versionIhl and 0x0F

        if (version != 4) return null
        if (ihl < 5) return null

        val headerLength = ihl * 4
        if (buffer.remaining() < headerLength - 1) return null

        // 跳过 DSCP/ECN, Total Length
        buffer.position(buffer.position() + 1 + 2)

        buffer.getShort() // identification
        buffer.getShort() // flags/fragment
        buffer.get() // ttl
        val protocol = buffer.get().toInt() and 0xFF
        buffer.getShort() // headerChecksum

        val srcIp = readIpv4Address(buffer)
        val dstIp = readIpv4Address(buffer)

        // 处理选项
        if (ihl > 5) {
            buffer.position(buffer.position() + (ihl - 5) * 4)
        }

        val payloadStart = buffer.position()
        val payloadLength = buffer.remaining()

        return ParsedPacket(srcIp, dstIp, protocol, payloadStart, payloadLength)
    }

    /**
     * 解析 IPv6 头（含扩展头），返回协议、源/目的地址与 payload 区间。
     * @param buffer 位置将停在最终 payload 起始处
     */
    fun parseIpv6Header(buffer: ByteBuffer): ParsedPacket? {
        buffer.order(ByteOrder.BIG_ENDIAN)

        // IPv6 固定头部 40 字节
        if (buffer.remaining() < 40) return null

        val versionTrafficClassFlow = buffer.getInt()
        val version = versionTrafficClassFlow shr 28

        if (version != 6) return null

        val payloadLength = buffer.getShort().toInt() and 0xFFFF
        var nextHeader = buffer.get().toInt() and 0xFF
        buffer.get() // hop limit

        val srcIp = readIpv6Address(buffer)
        val dstIp = readIpv6Address(buffer)

        // 解析 IPv6 扩展头部，找到最终的上层协议
        val (payloadStart, finalNextHeader) = parseIpv6ExtensionHeaders(buffer, payloadLength, nextHeader)
        if (payloadStart < 0) return null

        // 重新计算实际 payload 长度 (扣除扩展头部)
        val extensionHeaderLen = payloadStart - 40
        val actualPayloadLength = payloadLength - extensionHeaderLen
        if (actualPayloadLength < 0) return null

        return ParsedPacket(srcIp, dstIp, finalNextHeader, payloadStart, actualPayloadLength)
    }

    /**
     * 解析 IPv6 扩展头部
     * @return payload 起始位置，负数表示错误
     */
    private fun parseIpv6ExtensionHeaders(
        buffer: ByteBuffer,
        payloadLength: Int,
        nextHeader: Int,
    ): Pair<Int, Int> {
        var pos = buffer.position()
        val maxPos = pos + payloadLength
        var currentNextHeader = nextHeader

        // 限制扩展头部数量防止循环
        var extHeaderCount = 0
        while (currentNextHeader in IPV6_EXTENSION_HEADERS && extHeaderCount < 10) {
            if (pos + 2 > maxPos) return Pair(-1, currentNextHeader)

            buffer.get(pos) // extType
            val extLen = (buffer.get(pos + 1).toInt() and 0xFF) + 1 // 单位是 8 字节

            val extHeaderLen =
                when (currentNextHeader) {
                    44 -> 8 // Fragment header 固定 8 字节
                    else -> extLen * 8
                }

            if (pos + extHeaderLen > maxPos) return Pair(-1, currentNextHeader)

            // 更新 nextHeader 为扩展头部中的 Next Header 字段
            currentNextHeader = buffer.get(pos).toInt() and 0xFF
            pos += extHeaderLen
            extHeaderCount++
        }

        buffer.position(pos)
        return Pair(pos, currentNextHeader)
    }

    private fun readIpv4Address(buffer: ByteBuffer): InetAddress {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return InetAddress.getByAddress(bytes)
    }

    private fun readIpv6Address(buffer: ByteBuffer): InetAddress {
        val bytes = ByteArray(16)
        buffer.get(bytes)
        return InetAddress.getByAddress(bytes)
    }

    /**
     * 五元组连接标识符
     */
    fun connectionKey(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): Long {
        val srcBytes = srcIp.address
        val dstBytes = dstIp.address
        val isSrc6 = srcBytes.size == 16
        val isDst6 = dstBytes.size == 16

        val srcLow =
            if (isSrc6) {
                (srcBytes[12].toLong() and 0xFF shl 24) or
                    (srcBytes[13].toLong() and 0xFF shl 16) or
                    (srcBytes[14].toLong() and 0xFF shl 8) or
                    (srcBytes[15].toLong() and 0xFF)
            } else {
                (srcBytes[0].toLong() and 0xFF shl 24) or
                    (srcBytes[1].toLong() and 0xFF shl 16) or
                    (srcBytes[2].toLong() and 0xFF shl 8) or
                    (srcBytes[3].toLong() and 0xFF)
            }

        val dstLow =
            if (isDst6) {
                (dstBytes[12].toLong() and 0xFF shl 24) or
                    (dstBytes[13].toLong() and 0xFF shl 16) or
                    (dstBytes[14].toLong() and 0xFF shl 8) or
                    (dstBytes[15].toLong() and 0xFF)
            } else {
                (dstBytes[0].toLong() and 0xFF shl 24) or
                    (dstBytes[1].toLong() and 0xFF shl 16) or
                    (dstBytes[2].toLong() and 0xFF shl 8) or
                    (dstBytes[3].toLong() and 0xFF)
            }

        return (srcLow shl 48) xor
            (dstLow shl 32) xor
            (srcPort.toLong() shl 16) xor
            dstPort.toLong()
    }

    data class ParsedPacket(
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val protocol: Int,
        val payloadStart: Int,
        val payloadLength: Int,
    )
}
