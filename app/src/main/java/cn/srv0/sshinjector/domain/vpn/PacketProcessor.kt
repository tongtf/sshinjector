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
import java.util.concurrent.atomic.AtomicLong
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
        // 域名访问统计 (T-02)。**带默认值**: `PacketProcessorTest` 按 2 参构造; Hilt 场景下
        // Dagger 仍会注入真正的 @Singleton 单例 (默认值只服务测试, 这是刻意的)。
        private val domainStats: DomainStatsStore = DomainStatsStore(),
        // 「连接 → uid」反查 (④ 按应用归属); 同样只带默认值服务测试。
        private val appUidResolver: AppUidResolver = AppUidResolver.NONE,
    ) {
        companion object {
            const val DEFAULT_CONNECTION_CLEANUP_TIMEOUT_MS = 300000L // 5 分钟默认值
            private const val STATS_FLUSH_INTERVAL_MS = 100L
        }

        private val stats = PacketStats()
        private val statsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        @Volatile private var tunSink: TunSink? = null

        @Volatile private var dnsInterceptor: DnsInterceptor? = null

        private val tcpStateMachine =
            TcpStateMachine(tunnelManager, { tunSink }, stats, sshIoDispatcher, domainStats, appUidResolver)
        private val udpRelay = UdpRelay(stats)
        private val icmpv6Responder = Icmpv6Responder({ tunSink })

        val errors: StateFlow<Long> = stats.errors

        init {
            // 节流发布统计: 热点路径只累加 AtomicLong, 避免每包 StateFlow 发射
            statsScope.launch {
                while (isActive) {
                    delay(STATS_FLUSH_INTERVAL_MS)
                    stats.syncToFlows()
                    // 域名统计的低频维护: 清超窗 + 超限淘汰 (maybeTrim 内部按 30s 门控, 这里每 100ms
                    // 调一次几乎全是 no-op)。**绝不**放进任何逐包路径 —— 淘汰含 O(n log n) 排序。
                    domainStats.maybeTrim(System.currentTimeMillis())
                }
            }
        }

        /**
         * 接上/摘掉 TUN 写出口。生产路径接 [cn.srv0.sshinjector.domain.vpn.TunWriter];
         * `null` = 无出口 (未连接/正在重建), 此时写入按失败结算 (分阶段健康的 TUN 段据此判)。
         */
        fun setTunSink(sink: TunSink?) {
            tunSink = sink
        }

        fun setDnsInterceptor(interceptor: DnsInterceptor) {
            dnsInterceptor = interceptor
            tcpStateMachine.setDnsInterceptor(interceptor)
            udpRelay.setDnsInterceptor(interceptor)
        }

        // S5: IPv6 关闭时 TUN 捕获到的 v6 包一律丢弃 —— 关闭 = 不用 IPv6, 而非让其逃逸物理网卡
        @Volatile private var enableIPv6 = true
        private val droppedIpv6Count =
            AtomicLong(0)

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
                    0x06 -> tcpStateMachine.processTcpPacket(buffer, parsed)
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
                    0x06 -> tcpStateMachine.processTcpPacket(buffer, parsed)
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
         * 域名访问统计快照 (60s 数据面快照的资源节): "域名统计 N/20000(淘汰 K)"。
         * O(1), 由 VpnController 的资源节拼接 (T-06)。
         */
        fun domainStatsDiagnostics(): String = domainStats.diagnostics()

        /**
         * ④ 按应用归属的反查命中率 (`命中 H/Q 查询`), 进 60s 数据面快照资源节 ——
         * 无 adb 现场时这是"uid 到底有没有拿到"的唯一判据。无平台能力时返回空串。
         */
        fun appUidDiagnostics(): String = appUidResolver.diagnostics()

        /**
         * UDP 非 DNS 丢弃量 (QUIC/游戏等)。UdpRelay 自己会按目标端口周期上报,
         * 但 60s 数据面快照是唯一一张"全局对账单", 少了它就无法一眼看出
         * 「下载慢」里有多少是 QUIC 一直在被丢弃后才回退 TCP。
         */
        fun udpDiagnostics(): String = "UDP 丢弃 ${udpRelay.droppedUdp} 个"

        /**
         * IPv6 关闭时的丢弃量 (S5)。进 60s 数据面快照: 非 0 说明客户端在持续尝试 v6
         * (路由没盖住或应用只发 AAAA), 是"某站点打不开"的一眼线索。
         */
        fun ipv6Diagnostics(): String = "IPv6 丢弃 ${droppedIpv6Count.get()} 个"
    }
