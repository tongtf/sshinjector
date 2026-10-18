package cn.srv0.sshinjector.domain.vpn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * IP/TCP/UDP 包头解析。
 *
 * 除了 [newFrame] 维护的帧代际 (惰性地址的越帧哨兵, 见下) 之外无状态。
 *
 * **惰性地址 (T-A05)**: [ParsedPacket] 只记 `buffer` + 绝对下标, 地址在真正需要时才物化。
 * 代价是一条不变量 —— 惰性地址必须在**同一次 TUN read 的帧内**读完 (帧外 buffer 已被覆盖),
 * 由 [newFrame]/[staleAddressReads] 这对哨兵守着: 越帧读取会被计数而不是静默返回脏地址。
 */
object IpPacketParser {
    // IPv6 扩展头部类型: Hop-by-Hop(0), Routing(43), Fragment(44), Destination Options(60), Reserved(255)
    private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 60, 255)

    /** 帧代际: 每次 TUN read 前递增, 惰性地址读取时比对 (见 [newFrame] / [staleAddressReads])。 */
    @Volatile
    private var frameId = 0

    /** 惰性地址失效读取计数 (跨帧 / 越界), 见 [staleAddressReads]。 */
    private val staleReads = AtomicLong(0)

    /**
     * 标记一次**帧边界** (每次 `TUN read` 之前调一次)。
     *
     * 惰性地址的生命周期就是这一帧: `readBuffer` 在下一轮 read 时被整块覆盖, 因此
     * [ParsedPacket.srcIp]/[ParsedPacket.dstIp] 越帧读取拿到的必然是**别人包的字节** ——
     * 比抛异常更危险 (看起来是个合法地址)。这里递增代际, 供惰性读取比对。
     */
    fun newFrame() {
        frameId++
    }

    /** 惰性地址的失效读取次数。**恒为 0 才是对的**; > 0 = 帧不变量被破坏 (T-A05 自证点)。 */
    fun staleAddressReads(): Long = staleReads.get()

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

        // 地址只记**绝对下标** (T-A05): 低 32 位就地零分配算出, 完整 `InetAddress` 惰性物化 ——
        // 上行热路径绝大多数包只查连接 key/端口, 不再每包建 2 个 InetAddress + 2 个 byte[]。
        val srcPos = buffer.position()
        val srcLow = addressLow32(buffer, 4, srcPos)
        buffer.position(srcPos + 4)
        val dstPos = buffer.position()
        val dstLow = addressLow32(buffer, 4, dstPos)
        buffer.position(dstPos + 4)

        // 处理选项
        if (ihl > 5) {
            buffer.position(buffer.position() + (ihl - 5) * 4)
        }

        val payloadStart = buffer.position()
        val payloadLength = buffer.remaining()

        return ParsedPacket(buffer, srcPos, 4, protocol, payloadStart, payloadLength, srcLow, dstLow, frameId)
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

        val srcPos = buffer.position()
        val srcLow = addressLow32(buffer, 6, srcPos)
        buffer.position(srcPos + 16)
        val dstPos = buffer.position()
        val dstLow = addressLow32(buffer, 6, dstPos)
        buffer.position(dstPos + 16)

        // 解析 IPv6 扩展头部，找到最终的上层协议
        val (payloadStart, finalNextHeader) = parseIpv6ExtensionHeaders(buffer, payloadLength, nextHeader)
        if (payloadStart < 0) return null

        // 重新计算实际 payload 长度 (扣除扩展头部)
        val extensionHeaderLen = payloadStart - 40
        val actualPayloadLength = payloadLength - extensionHeaderLen
        if (actualPayloadLength < 0) return null

        return ParsedPacket(
            buffer,
            srcPos,
            6,
            finalNextHeader,
            payloadStart,
            actualPayloadLength,
            srcLow,
            dstLow,
            frameId,
        )
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

    /**
     * 从 [buffer] 的**绝对**下标 [absOffset] 拷出地址字节 (IPv4 = 4 / IPv6 = 16); 越界返回 null。
     *
     * 与 [addressLow32] 一样用绝对下标读, 不推进 `position`。返回的数组是新分配的 ——
     * **惰性地址的一次物化 = 1 个 byte[] + 1 个 InetAddress**, 而不再是每包 2+2。
     */
    fun addressBytes(
        buffer: ByteBuffer,
        absOffset: Int,
        version: Int,
    ): ByteArray? {
        val len = if (version == 6) 16 else 4
        if (absOffset < 0 || absOffset + len > buffer.limit()) return null
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = buffer.get(absOffset + i)
        return bytes
    }

    /**
     * 取 IP 地址的**低 32 位**，供 [connectionKey] 使用。
     *
     * 取样规则与历史实现完全一致: IPv4 = 全部 4 字节 (offset 起)，IPv6 = **后 4 字节**
     * (offset+12 起，不是前 4 字节)。这条规则不改 —— 改了 key 就会变，连接会被错投。
     *
     * 用**绝对下标**读而非推进 `position`，因此可在解析过程中紧挨着 `readIpv4Address` /
     * `readIpv6Address` 调用而不破坏 buffer 游标。**零分配**（不建 ByteArray / InetAddress）。
     *
     * @param buffer 报文缓冲区
     * @param version IP 版本号 (4 / 6)
     * @param absOffset 该地址在 buffer 里的**绝对**起始下标
     * @return 低 32 位 (0..0xFFFFFFFF); 越界返回 0 (调用方已做过头部长度校验)
     */
    fun addressLow32(
        buffer: ByteBuffer,
        version: Int,
        absOffset: Int,
    ): Long {
        val start = if (version == 6) absOffset + 12 else absOffset
        if (start < 0 || start + 3 >= buffer.limit()) return 0L
        return ((buffer.get(start).toLong() and 0xFF) shl 24) or
            ((buffer.get(start + 1).toLong() and 0xFF) shl 16) or
            ((buffer.get(start + 2).toLong() and 0xFF) shl 8) or
            (buffer.get(start + 3).toLong() and 0xFF)
    }

    /** [addressLow32] 的 [InetAddress] 版本 (冷路径: 测试 / 无原始报文的场景)。 */
    fun addressLow32(address: InetAddress): Long {
        val bytes = address.address
        val off = if (bytes.size == 16) 12 else 0
        return ((bytes[off].toLong() and 0xFF) shl 24) or
            ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or
            (bytes[off + 3].toLong() and 0xFF)
    }

    /**
     * 五元组连接标识符。
     *
     * @param srcIp 源地址
     * @param dstIp 目的地址
     * @param srcPort 源端口
     * @param dstPort 目的端口
     */
    fun connectionKey(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): Long = connectionKey(addressLow32(srcIp), addressLow32(dstIp), srcPort, dstPort)

    /**
     * 五元组连接标识符 (**零分配**重载): 直接由两个地址的低 32 位组合。
     *
     * 位布局与 [connectionKey] 的 [InetAddress] 版本**逐位一致** —— 由
     * `IpPacketParserTest` 的 golden 用例锁定, 改动即视为破坏性变更。
     */
    fun connectionKey(
        srcLow: Long,
        dstLow: Long,
        srcPort: Int,
        dstPort: Int,
    ): Long =
        (srcLow shl 48) xor
            (dstLow shl 32) xor
            (srcPort.toLong() shl 16) xor
            dstPort.toLong()

    /**
     * 解析结果：协议、载荷区间, 以及**惰性物化**的源/目的地址。
     *
     * 生产路径只记 `buffer` + 绝对下标 (T-A05): 上行热路径的绝大多数包只用来查连接 key
     * (由零分配的 [srcLow]/[dstLow] 组合) 与端口, 于是每包省掉 2 个 `InetAddress` + 2 个 byte[]。
     *
     * **帧不变量**: [srcIp]/[dstIp] 必须在**同一次 TUN read 的帧内**读取完毕 —— 帧外 buffer
     * 已被下一包覆盖。越帧读取会被 [IpPacketParser.staleAddressReads] 计数 (不是静默错误值)。
     * 所有生产消费者都在 `processPacket` 的同一次调用内物化 (新建连接 / UDP 拦截 / ICMPv6 回包),
     * `TcpConnection` 另行拷字节自持, 因此不存在跨帧持有。
     */
    class ParsedPacket internal constructor(
        private val buffer: ByteBuffer,
        private val srcOffset: Int,
        private val version: Int,
        val protocol: Int,
        val payloadStart: Int,
        val payloadLength: Int,
        /** 源地址低 32 位 (零分配算出, 见 [addressLow32]); 供 `connectionKey` 直接使用。 */
        val srcLow: Long,
        /** 目的地址低 32 位; 供 `connectionKey` 直接使用。 */
        val dstLow: Long,
        private val frame: Int,
    ) {
        /** 目的地址紧跟在源地址之后 (IPv4 4 字节 / IPv6 16 字节), 因此只存一个偏移。 */
        private val dstOffset: Int = srcOffset + if (version == 6) 16 else 4

        // 惰性地址用**普通可空字段**而不是 `by lazy`: 后者每个属性额外建一个 Lazy 对象,
        // 会把这里刚省下来的分配又加回去。本对象是单帧作用域 (只在 packetLoop 线程内使用),
        // 因此不需要同步 —— 最坏情况是同一地址物化两次, 无正确性影响。
        private var srcCache: InetAddress? = null
        private var dstCache: InetAddress? = null

        /** 源地址 (惰性物化 + 缓存; 见类 KDoc 的帧不变量)。 */
        val srcIp: InetAddress
            get() = srcCache ?: materialize(srcOffset).also { srcCache = it }

        /** 目的地址 (惰性物化 + 缓存)。 */
        val dstIp: InetAddress
            get() = dstCache ?: materialize(dstOffset).also { dstCache = it }

        /**
         * 测试 / 无原始报文场景的构造: 地址直接给定, 立刻拷进一个私有缓冲区。
         *
         * 刻意走**同一条**物化代码 (读地址字节 → `InetAddress.getByAddress`), 所以测试覆盖的
         * 就是线上路径, 而不是一条只有测试才走的旁路。
         */
        constructor(
            srcIp: InetAddress,
            dstIp: InetAddress,
            protocol: Int,
            payloadStart: Int,
            payloadLength: Int,
        ) : this(
            buffer = addressPairBuffer(srcIp, dstIp),
            srcOffset = 0,
            version = if (srcIp.address.size == 16) 6 else 4,
            protocol = protocol,
            payloadStart = payloadStart,
            payloadLength = payloadLength,
            srcLow = addressLow32(srcIp),
            dstLow = addressLow32(dstIp),
            frame = frameId,
        )

        private fun materialize(offset: Int): InetAddress {
            if (frame != frameId) staleReads.incrementAndGet()
            val bytes = addressBytes(buffer, offset, version)
            return bytes?.let { InetAddress.getByAddress(it) } ?: InetAddress.getLoopbackAddress()
        }
    }

    private fun addressPairBuffer(
        srcIp: InetAddress,
        dstIp: InetAddress,
    ): ByteBuffer {
        val bytes = srcIp.address + dstIp.address
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    }
}
