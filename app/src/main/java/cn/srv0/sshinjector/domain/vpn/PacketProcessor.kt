package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据包处理器（协调器）：解析 IP 头，按协议分派到 TCP/UDP/ICMPv6 子模块。
 */
@Singleton
class PacketProcessor
    @Inject
    constructor(
        private val tunnelManager: TunnelManager,
        private val sshIoDispatcher: SshIoDispatcher,
    ) {
        companion object {
            const val DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS = 300000L // 5 分钟默认值
            private const val STATS_FLUSH_INTERVAL_MS = 100L
        }

        private val stats = PacketStats()
        private val statsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        @Volatile private var tunWriter: ((ByteArray) -> Unit)? = null

        @Volatile private var dnsInterceptor: DnsInterceptor? = null

        private val tcpStateMachine =
            TcpStateMachine(tunnelManager, { tunWriter }, stats, sshIoDispatcher)
        private val udpRelay = UdpRelay(stats)
        private val icmpv6Responder = Icmpv6Responder({ tunWriter })

        val packetsProcessed: StateFlow<Long> = stats.packetsProcessed
        val bytesProcessed: StateFlow<Long> = stats.bytesProcessed
        val errors: StateFlow<Long> = stats.errors

        init {
            // 节流发布统计: 热点路径只累加 AtomicLong, 避免每包 StateFlow 发射
            statsScope.launch {
                while (isActive) {
                    delay(STATS_FLUSH_INTERVAL_MS)
                    stats.syncToFlows()
                }
            }
        }

        fun setTunWriter(writer: (ByteArray) -> Unit) {
            tunWriter = writer
        }

        fun setDnsInterceptor(interceptor: DnsInterceptor) {
            dnsInterceptor = interceptor
            tcpStateMachine.setDnsInterceptor(interceptor)
            udpRelay.setDnsInterceptor(interceptor)
        }

        // S5: IPv6 关闭时 TUN 捕获到的 v6 包一律丢弃 —— 关闭 = 不用 IPv6, 而非让其逃逸物理网卡
        @Volatile private var enableIPv6 = true
        private val droppedIpv6Count =
            java.util.concurrent.atomic
                .AtomicLong(0)

        val droppedIpv6: Long
            get() = droppedIpv6Count.get()

        fun setEnableIPv6(enable: Boolean) {
            enableIPv6 = enable
        }

        /** 会话断开时清空 TCP 连接表, 防止跨会话残留 (F6-4) */
        fun resetTcpState() {
            tcpStateMachine.reset()
        }

        /** F1: 注入 TCP 用户态直连策略 (VpnController 的排除路由/域名分流判定 + VpnService.protect)。 */
        fun setTcpBypass(
            shouldBypass: (java.net.InetAddress, Int) -> Boolean,
            protect: (java.net.Socket) -> Boolean,
        ) {
            tcpStateMachine.setBypass(shouldBypass, protect)
        }

        /**
         * 处理来自 TUN 的 IPv4 数据包。
         * 返回 false = 未处理（解析失败/不支持协议/DNS 拦截失败）→ 已计数，调用方丢弃即可，**不得回注 TUN**。
         */
        fun processIpv4Packet(buffer: ByteBuffer): Boolean {
            val parsed = IpPacketParser.parseIpv4Header(buffer) ?: return dropUnhandled()
            val handled =
                when (parsed.protocol) {
                    0x06 ->
                        tcpStateMachine.processTcpPacket(
                            buffer,
                            parsed.srcIp,
                            parsed.dstIp,
                            parsed.payloadStart,
                            parsed.payloadLength,
                        )
                    0x11 ->
                        udpRelay.processUdpPacket(
                            buffer,
                            parsed.srcIp,
                            parsed.dstIp,
                            parsed.payloadStart,
                            parsed.payloadLength,
                        )
                    else -> false
                }
            return handled || dropUnhandled()
        }

        /**
         * 处理来自 TUN 的 IPv6 数据包。返回 false 语义同 [processIpv4Packet]。
         */
        fun processIpv6Packet(buffer: ByteBuffer): Boolean {
            if (!enableIPv6) {
                // S5: 丢弃而非处理 (含 ND/DNS 假 IP 路径), 不回注 TUN
                droppedIpv6Count.incrementAndGet()
                return false
            }
            val parsed = IpPacketParser.parseIpv6Header(buffer) ?: return dropUnhandled()
            val handled =
                when (parsed.protocol) {
                    0x06 ->
                        tcpStateMachine.processTcpPacket(
                            buffer,
                            parsed.srcIp,
                            parsed.dstIp,
                            parsed.payloadStart,
                            parsed.payloadLength,
                        )
                    0x11 ->
                        udpRelay.processUdpPacket(
                            buffer,
                            parsed.srcIp,
                            parsed.dstIp,
                            parsed.payloadStart,
                            parsed.payloadLength,
                        )
                    0x3A ->
                        icmpv6Responder.processIcmpv6Packet(
                            buffer,
                            parsed.srcIp,
                            parsed.dstIp,
                            parsed.payloadStart,
                            parsed.payloadLength,
                        )
                    else -> false
                }
            return handled || dropUnhandled()
        }

        // F12-e: 未处理 → 丢弃计数; writeToTun 回注是 ip_forward 黑洞/读写死循环 (F1 同类)
        private fun dropUnhandled(): Boolean {
            stats.addError()
            return false
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
        ): ByteArray = udpRelay.buildUdpResponsePacket(srcIp, dstIp, srcPort, dstPort, payload)

        fun cleanupStaleConnections(timeoutMs: Long = DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS) {
            tcpStateMachine.cleanupStaleConnections(timeoutMs)
        }

        /** TCP 数据面诊断快照 (周期日志): 活跃连接 / 背压丢段 / 回程闸门阻塞。 */
        fun tcpDiagnostics(): String = tcpStateMachine.diagnostics()

        /**
         * UDP 非 DNS 丢弃量 (QUIC/游戏等)。UdpRelay 自己会按目标端口周期上报,
         * 但 60s 数据面快照是唯一一张"全局对账单", 少了它就无法一眼看出
         * 「下载慢」里有多少是 QUIC 一直在被丢弃后才回退 TCP。
         */
        fun udpDiagnostics(): String = "UDP 丢弃 ${udpRelay.droppedUdp} 个"
    }
