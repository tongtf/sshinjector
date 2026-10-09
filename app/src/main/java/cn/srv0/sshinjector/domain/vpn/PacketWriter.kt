package cn.srv0.sshinjector.domain.vpn

import java.nio.ByteBuffer

/**
 * IP/TCP 头写入与校验和回填，纯函数无状态。
 *
 * 从 TcpStateMachine 的 4 个组包函数 (数据/ACK/SYN-ACK/关闭) 与 UdpRelay 的 DNS 回程包
 * 中抽出的公共段 —— 五处原本各自复制同一段头写入与校验和代码, 改 MTU/头部语义时极易漏改一处。
 * 字节序由调用方的 ByteBuffer 决定 (回程组包一律 BIG_ENDIAN)。
 */
@Suppress("MagicNumber")
object PacketWriter {
    private const val IPV6_VERSION_WORD = 0x60000000
    private const val DEFAULT_HOP_LIMIT = 64
    private const val IPV4_IHL = 0x45 // version=4, IHL=5 (20B, 无选项)
    private const val IP_CSUM_OFFSET = 10
    private const val TCP_CSUM_OFFSET = 16

    /**
     * 写 40B IPv6 / 20B IPv4 头 (校验和字段留 0, IPv4 由 [writeIpv4HeaderChecksum] 回填)。
     *
     * @param l4Len IPv6 payload length (L4 头+载荷); IPv4 下总长用 totalLen 字段
     * @param ipv4Flags IPv4 identification/flags 字段 (TCP=0, UDP=DF 0x4000)
     */
    @Suppress("LongParameterList")
    fun writeIpHeader(
        packet: ByteBuffer,
        srcIp: ByteArray,
        dstIp: ByteArray,
        totalLen: Int,
        l4Len: Int,
        protocol: Int,
        ipv4Flags: Int,
    ) {
        if (srcIp.size == 16) {
            packet.putInt(IPV6_VERSION_WORD)
            packet.putShort(l4Len.toShort())
            packet.put(protocol.toByte())
            packet.put(DEFAULT_HOP_LIMIT.toByte())
            packet.put(srcIp)
            packet.put(dstIp)
        } else {
            packet.put(IPV4_IHL.toByte())
            packet.put(0x00)
            packet.putShort(totalLen.toShort())
            packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
            packet.putShort(ipv4Flags.toShort())
            packet.put(DEFAULT_HOP_LIMIT.toByte())
            packet.put(protocol.toByte())
            packet.putShort(0)
            packet.put(srcIp)
            packet.put(dstIp)
        }
    }

    /** 写 20B TCP 头 + 可选 options 区之前的基础 8 字段 (checksum/urgent 置 0)。options 由调用方接着写。 */
    @Suppress("LongParameterList")
    fun writeTcpHeader(
        packet: ByteBuffer,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flagsWord: Int,
        window: Int,
    ) {
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putInt(seqNum.toInt())
        packet.putInt(ackNum.toInt())
        packet.putShort(flagsWord.toShort())
        packet.putShort(window.toShort())
        packet.putShort(0) // checksum 占位
        packet.putShort(0) // urgent pointer
    }

    /** 回填 IPv4 头校验和 (IPv6 头无校验和)。 */
    fun writeIpv4HeaderChecksum(
        packet: ByteBuffer,
        ipHeaderLen: Int,
    ) {
        packet.position(0)
        val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
        packet.position(IP_CSUM_OFFSET)
        packet.putShort(ipChecksum)
    }

    /** 回填 TCP 校验和 (伪头由 [ChecksumCalculator.tcpChecksum] 处理, TCP 头 checksum 字段须为 0)。 */
    fun writeTcpChecksum(
        packet: ByteBuffer,
        ipHeaderLen: Int,
        tcpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
    ) {
        val tcpChecksum = ChecksumCalculator.tcpChecksum(srcIp, dstIp, packet.array(), ipHeaderLen, tcpLen)
        packet.position(ipHeaderLen + TCP_CSUM_OFFSET)
        packet.putShort(tcpChecksum)
    }
}
