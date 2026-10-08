package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val IS_DEBUG = android.util.Log.isLoggable("PacketProcessor", android.util.Log.DEBUG)

/**
 * UDP 处理（降级模式）。
 *
 * UDP relay 已明确不支持: 进入 TUN 的非 53 端口 UDP (QUIC/游戏) 直接丢弃并计数,
 * 不再假装通过 SOCKS5 UDP ASSOCIATE 转发 (该链路从未可用: 本地代理只监听 TCP)。
 * 仅保留: DNS 拦截 (53) 与 UDP 回程包构造 (DNS 响应)。
 */
class UdpRelay(
    private val stats: PacketStats,
) {
    companion object {
        private const val TAG = "PacketProcessor"
        private const val DROP_LOG_INTERVAL_MS = 60_000L
    }

    private val droppedUdpCount = AtomicLong(0)

    /**
     * 被丢弃 UDP 的**目的端口**分布 (QUIC 走 443)。
     *
     * 用途: 判定"Play 分页卡顿"是否由 QUIC 黑洞造成。注意**不能**用 ICMP 不可达去促使
     * 客户端回退 TCP —— RFC 9000 §8 明确 QUIC 不把 ICMP 错误用于路径验证, 收到也忽略。
     * 客户端只能靠自己的重试退避后回退, 实测可达数十秒。
     */
    private val droppedByDstPort = ConcurrentHashMap<Int, Long>()

    /** 应用内日志节流: 丢包是每包级事件, 60s 最多上报一次 */
    @Volatile private var lastDropLogAt = 0L

    val droppedUdp: Long
        get() = droppedUdpCount.get()

    private var dnsInterceptor: DnsInterceptor? = null

    fun setDnsInterceptor(interceptor: DnsInterceptor) {
        dnsInterceptor = interceptor
    }

    /**
     * 处理 UDP 数据包: 53 端口走 DNS 拦截, 其余明确丢弃 (UDP relay 不支持)。
     */
    fun processUdpPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        payloadStart: Int,
        payloadLength: Int,
    ): Boolean {
        if (payloadLength < 8) return false

        buffer.position(payloadStart)
        val srcPort = buffer.getShort().toInt() and 0xFFFF
        val dstPort = buffer.getShort().toInt() and 0xFFFF
        buffer.getShort() // length
        buffer.getShort() // checksum

        // DNS 查询拦截 (UDP 53)
        if (dstPort == 53 || srcPort == 53) {
            buffer.limit(payloadStart + payloadLength)
            return handleDnsPacket(buffer, srcIp, dstIp, srcPort, dstPort)
        }

        // UDP relay 不支持: 明确丢弃并计数 (QUIC/游戏等), 避免静默吞包
        droppedUdpCount.incrementAndGet()
        droppedByDstPort.merge(dstPort, 1L) { a, b -> a + b }
        if (IS_DEBUG) {
            Log.d(TAG, "UDP ${dstIp.hostAddress}:$dstPort dropped (UDP relay not supported)")
        }
        // 应用内可见 (60s 节流): Play/浏览器下载常走 HTTP/3(UDP)。日志措辞已修正:
        // 旧文案"(应由应用回退 TCP)"是**未经验证的假设** —— QUIC 不会因 ICMP 错误回退
        // (RFC 9000 §8), 客户端只能靠自身重试退避, 实测可达数十秒, 期间表现为"卡住"。
        val now = System.currentTimeMillis()
        if (now - lastDropLogAt >= DROP_LOG_INTERVAL_MS) {
            lastDropLogAt = now
            VpnController.appLog(
                "UDP 包丢弃累计 ${droppedUdpCount.get()} 个 — UDP 中继不支持," +
                    " 目的端口分布 ${portBreakdown()};" +
                    " 依赖 HTTP/3(QUIC/443) 的请求需等客户端自行回退 TCP, 期间表现为卡顿",
                level = LogLevel.WARNING,
            )
        }
        return true
    }

    /** 丢弃端口分布 (降序, 最多 3 项): 出现 443 即说明 QUIC 被黑洞。 */
    private fun portBreakdown(): String =
        droppedByDstPort.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(",") { "${it.key}→${it.value}" }
            .ifEmpty { "无" }

    /**
     * DNS 包处理: 委托 DnsInterceptor 进行解析
     */
    private fun handleDnsPacket(
        buffer: ByteBuffer,
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
    ): Boolean {
        val interceptor = dnsInterceptor
        if (interceptor == null) {
            Log.w(TAG, "DnsInterceptor not set, DNS packet ignored")
            VpnController.appLogThrottled("DNS 拦截器未初始化 · DNS 包被丢弃", level = LogLevel.ERROR)
            return false
        }

        try {
            // 诊断日志：记录所有 53 端口 UDP 包
            if (IS_DEBUG) {
                Log.d(
                    TAG,
                    "DNS packet from $srcIp:$srcPort to $dstIp:$dstPort (${buffer.remaining()}B)",
                )
            }

            // 提取 DNS 查询 payload (buffer 已跳过 UDP 头部 8 字节, limit 为 DNS 负载长度)
            val dnsBuffer = buffer.slice()
            val dnsPayloadLen = dnsBuffer.remaining()
            dnsBuffer.limit(dnsPayloadLen)

            // 委托 DnsInterceptor 处理 — false = 未拦截/解析失败 → 丢弃 (回注是黑洞, 无透传语义)
            if (!interceptor.processDnsQuery(dnsBuffer, srcIp, dstIp, srcPort, dstPort)) {
                return false
            }
            stats.addPacket(dnsPayloadLen.toLong())
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "handleDnsPacket failed", e)
            stats.addError()
            return false
        }
    }

    /**
     * 构建 UDP 响应包 (用于 DNS 回程)
     */
    fun buildUdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(srcIp.size == dstIp.size && (srcIp.size == 4 || srcIp.size == 16)) {
            "unexpected DNS address sizes: src=${srcIp.size} dst=${dstIp.size}"
        }
        val isV6 = srcIp.size == 16
        val udpLen = 8 + payload.size
        val ipHeaderLen = if (isV6) 40 else 20
        val totalLen = ipHeaderLen + udpLen

        // UDP header + payload 先组好: IPv6 校验和覆盖伪头+它们, 算完回填 csum 字段
        val udp = ByteArray(udpLen)
        ByteBuffer.wrap(udp).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(srcPort.toShort())
            putShort(dstPort.toShort())
            putShort(udpLen.toShort())
            putShort(0)
            put(payload)
        }

        val packet = ByteBuffer.allocate(totalLen)
        packet.order(ByteOrder.BIG_ENDIAN)

        if (isV6) {
            // IPv6 header (RFC 8200): version=6, payloadLen, nextHeader=UDP(17), hop=64, src, dst
            packet.put(0x60.toByte())
            packet.put(0x00)
            packet.put(0x00)
            packet.put(0x00)
            packet.putShort(udpLen.toShort())
            packet.put(17.toByte())
            packet.put(64.toByte())
            packet.put(srcIp)
            packet.put(dstIp)
            // IPv6 UDP 校验和必填 (RFC 8200); IPv4 分支保持 0 (可选)。
            // RFC 8200 §8.1: 算出 0 必须写成 0xFFFF —— 0 在 IPv6 UDP 里表示"无校验和"
            // 是被保留的非法值, 收端直接丢包 (表现为偶发 DNS 无响应)。
            val raw = udpChecksumV6(srcIp, dstIp, udp)
            val csum = if (raw == 0) 0xFFFF else raw
            udp[6] = (csum ushr 8).toByte()
            udp[7] = csum.toByte()
            packet.put(udp)
            return packet.array()
        }

        // IPv4 Header
        packet.put(0x45.toByte())
        packet.put(0x00)
        packet.putShort(totalLen.toShort())
        packet.putShort((System.currentTimeMillis() and 0xFFFF).toShort())
        packet.putShort(0x4000.toShort())
        packet.put(64.toByte())
        packet.put(17.toByte()) // Protocol: UDP
        packet.putShort(0)
        packet.put(srcIp)
        packet.put(dstIp)

        // UDP Header (checksum 0, IPv4 可选) + Payload
        packet.put(udp)

        // IP 校验和
        packet.position(0)
        val ipChecksum = ChecksumCalculator.ipChecksum(packet, ipHeaderLen)
        packet.position(10)
        packet.putShort(ipChecksum)

        return packet.array()
    }

    /**
     * IPv6 UDP 校验和: 伪头 (src+dst+udpLen 4B+nextHeader=17) + UDP header/payload (csum 字段为 0)。
     *
     * 仅供单测: 找到一个让原始校验和恰好为 0 的负载, 锁死"0 必须写成 0xFFFF"。
     */
    internal fun udpChecksumV6(
        src: ByteArray,
        dst: ByteArray,
        udp: ByteArray,
    ): Int {
        var sum = 0L

        fun addBytes(b: ByteArray) {
            var i = 0
            while (i + 1 < b.size) {
                sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < b.size) sum += (b[i].toInt() and 0xFF) shl 8
        }
        addBytes(src)
        addBytes(dst)
        // UDP length (4 字节) 与 nextHeader=17 (4 字节, 0x00000011)
        sum += (udp.size ushr 16)
        sum += (udp.size and 0xFFFF)
        sum += 17
        addBytes(udp)
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }
}
