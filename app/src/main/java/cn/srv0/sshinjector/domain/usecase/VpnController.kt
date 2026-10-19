package cn.srv0.sshinjector.domain.usecase

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.domain.model.ConnectStage
import cn.srv0.sshinjector.domain.model.ConnectionStats
import cn.srv0.sshinjector.domain.model.HealthStep
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.model.VpnState
import cn.srv0.sshinjector.domain.obs.AppMetrics
import cn.srv0.sshinjector.domain.vpn.AdBlocker
import cn.srv0.sshinjector.domain.vpn.CidrRoute
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_WHITELIST
import cn.srv0.sshinjector.domain.vpn.DataPlaneSnapshot
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.GfwListMatcher
import cn.srv0.sshinjector.domain.vpn.IpPacketParser
import cn.srv0.sshinjector.domain.vpn.PacketProcessor
import cn.srv0.sshinjector.domain.vpn.StageCounters
import cn.srv0.sshinjector.domain.vpn.StageHealthEvaluator
import cn.srv0.sshinjector.domain.vpn.TunWriter
import cn.srv0.sshinjector.domain.vpn.VpnNetwork
import cn.srv0.sshinjector.domain.vpn.dnsTransportFor
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import cn.srv0.sshinjector.ui.viewmodel.LogLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 每包级 DEBUG 日志门: 开启后才进应用内日志, 否则只走 logcat (防刷穿 replay 缓存)。 */
private val IS_DEBUG = Log.isLoggable("VpnController", Log.DEBUG)

/**
 * VPN 控制器 - 管理 VPN 连接的完整生命周期
 * 协调隧道插件、DNS 拦截、数据包处理
 */
@Singleton
@Suppress("TooManyFunctions") // 编排 + TUN 读写 + DNS 投递 + 统计 + 健康; 待按 F-03 拆 StatsPublisher/DnsDelivery
class VpnController
    @Inject
    constructor(
        private val tunnelManager: TunnelManager,
        private val packetProcessor: PacketProcessor,
        private val dnsInterceptor: DnsInterceptor,
        private val adBlocker: AdBlocker,
        private val settingsDataStore: cn.srv0.sshinjector.data.local.preferences.SettingsDataStore,
        private val domainListManager: DomainListManager,
        private val adBlockManager: cn.srv0.sshinjector.data.local.AdBlockManager,
        @ApplicationContext private val context: Context,
    ) : CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {
        @Volatile private var vpnInterface: FileDescriptor? = null

        @Volatile private var inputStream: FileInputStream? = null

        @Volatile private var outputStream: FileOutputStream? = null

        // 健康线程在 stats/健康环里读它算 packetLoopActive, 不加 @Volatile 会读到陈旧 Job
        // → 虚假 TUN FAIL (需连续 2 窗才降级, 影响小但不该有)
        @Volatile private var packetLoopJob: Job? = null
        private var tunGeneration = 0L
        private val readBuffer = ByteBuffer.allocate(32768).order(ByteOrder.BIG_ENDIAN)

        // 分阶段健康 (TUN/DNS/转发三段): 端到端探测探不到的盲区由它补
        private val stageEvaluator = StageHealthEvaluator()

        // F12-i: TUN 读连续失败计数 (成功读取即清零), 防止死流 fd 的无退避忙循环
        @Volatile private var consecutiveReadFailures = 0

        // 热点路径只累加原子计数, 由 statsFlushLoop 节流发布到 connectionStats。
        // 方向语义 (勿再弄反 —— 弄反过一次, 导致"上行是下行 12 倍"的误判):
        //   bytesDownCounter = 写 TUN = 隧道下行 (远端 → 应用), 在 writeToTun 累加;
        //   bytesUpCounter   = 读 TUN = 隧道上行 (应用 → 远端), 在 packetLoop 累加。
        private val bytesDownCounter =
            AtomicLong(0)
        private val bytesUpCounter =
            AtomicLong(0)
        private val packetsSentCounter =
            AtomicLong(0)
        private val packetsReceivedCounter =
            AtomicLong(0)

        // 数据面快照的上一窗口基线 (用于算速率)
        private var lastSnapshotUp = 0L
        private var lastSnapshotDown = 0L
        private var lastSnapshotAtMs = 0L

        /** TUN 写出口 (单写者)。null = 当前无出口 (未连接/正在重建)。 */
        @Volatile private var tunWriter: TunWriter? = null

        /** 因写队列满被丢弃的**控制包** (SYN-ACK/ACK/关闭包); 客户端会重传, 但连续增长说明 TUN 写跟不上。 */
        private val droppedTunControlPackets = AtomicLong(0)
        private val droppedTunControlBytes = AtomicLong(0)

        /** 一次 TUN 读取里没能解析成包的残留字节 (批量读取路径的监控点)。 */
        private val trailingBytesDropped = AtomicLong(0)

        /** TUN 接口为空时被整包跳过的计数 —— 隧道看似在线但流量全被吞, 必须进快照可见。 */
        private val droppedNoTun = AtomicLong(0)

        // 用于 SYSTEM 模式 DNS 绕过的 socket 保护函数
        private var protectDatagramChannel: ((DatagramSocket) -> Boolean)? = null

        // F1: TCP 用户态直连的 protect (必须在 connect 前调用, 由 SshVpnService 注入)
        @Volatile private var protectTcpSocket: ((java.net.Socket) -> Boolean)? = null

        init {
            // TCP bypass 策略实时读 excludedRoutes/transportMode (无需随配置变化重挂)
            packetProcessor.setTcpBypass(::shouldBypassTcp) { socket -> protectTcpSocket?.invoke(socket) ?: false }
            // 数据面 (TcpStateMachine/UdpRelay) 由 DI 独立提供、拿不到本实例,
            // 经静态 sink 写应用内诊断日志 (VpnController 是 @Singleton, 注册一次即可)
            setAppLogSink { message, level -> addLog(message, level) }
        }

        /**
         * 编译广告过滤规则, 优先级从高到低:
         * 1) 运行时编辑器保存的规则 —— 仅用于查漏补缺(增/减), 叠加在下面的基础规则之上, 不整体替换;
         * 2) 基础规则取 [AdBlockManager] 缓存内的远程清单(已内含内置规则)优先, 未配置远程 URL /
         *    缓存过期(≥3 天未更新) / 首次启动时回退内置 assets/adblock.txt。
         * 连接时调用; 读取失败不影响连接, 仅降级为可用内容。
         */
        private suspend fun loadAdBlockRules(): GfwListMatcher {
            // 运行时编辑器保存的规则仅用于查漏补缺(增/减), 叠加在下面的基础规则之上, 不整体替换。
            val localEdit =
                try {
                    settingsDataStore.adBlockRules.first().takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.w("VpnController", "read ad block rules failed: ${e.message}")
                    null
                }
            // 基础规则: 优先用缓存内的远程清单(已内含内置), 否则回退内置 assets/adblock.txt。
            val base = remoteOrBuiltIn()
            return GfwListMatcher.parse(if (localEdit.isNullOrBlank()) base else "$base\n$localEdit")
        }

        /** 基础规则源: 缓存内的远程清单(已内含内置规则)优先, 未配置/首次则回退内置清单。 */
        private suspend fun remoteOrBuiltIn(): String {
            val remote = runCatching { adBlockManager.currentRules() }.getOrNull().orEmpty()
            return if (remote.isNotBlank()) remote else readBuiltinAsset()
        }

        /** 读取编译期内置广告清单 assets/adblock.txt, 失败返回空串。 */
        private fun readBuiltinAsset(): String =
            try {
                context.assets.open("adblock.txt").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                Log.w("VpnController", "load adblock.txt failed: ${e.message}")
                appLogThrottled(
                    "广告清单加载失败 — ${e.message} · 内置规则未生效",
                    level = LogLevel.WARNING,
                    throttleKey = "广告清单加载失败",
                )
                ""
            }

        fun setProtectTcpFunction(protectSocket: ((java.net.Socket) -> Boolean)?) {
            addLog(">>> [VpnController] setProtectTcpFunction 被调用", LogLevel.DEBUG)
            protectTcpSocket = protectSocket
        }

        // 只读暴露: 注入者 (ViewModel/Service) 只允许 collect/读 value, 状态真相只由本类更新
        private val _vpnState = MutableStateFlow<VpnState>(VpnState())
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()
        private val _connectionStats = MutableStateFlow<ConnectionStats>(ConnectionStats())
        val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()

        // replay=500: 日志界面未打开时保留最近 500 条 (无订阅者时 replay 缓存是唯一副本);
        // 界面打开时订阅者先收到 replay 批量, 再持续接收增量。
        private val _logFlow =
            MutableSharedFlow<LogLine>(
                replay = 500,
                extraBufferCapacity = 10,
            )
        val logFlow: SharedFlow<LogLine> = _logFlow.asSharedFlow()

        private var currentServer: ServerConfig? = null

        @Volatile private var isRunning = false
        private var excludedRoutes: List<CidrRoute> = emptyList()
        private var transportMode: DnsInterceptor.DnsTransport = DnsInterceptor.DnsTransport.REMOTE

        // 白名单模式 (dnsMode=2) 的启用应用; 空名单 → DNS 退化为本地直连解析 (见 dnsTransportFor)
        @Volatile private var whitelistPackages: List<String> = emptyList()

        // 用于 SYSTEM 模式 DNS 转发的线程池
        // R5: daemon 线程, 进程退出不被 DNS bypass 任务挂住
        private val executor = Executors.newCachedThreadPool { r -> Thread(r, "dns-bypass").apply { isDaemon = true } }

        fun setProtectFunction(protectDatagramChannel: ((DatagramSocket) -> Boolean)?) {
            addLog(">>> [VpnController] setProtectFunction 被调用", LogLevel.DEBUG)
            this.protectDatagramChannel = protectDatagramChannel
        }

        /**
         * 注入白名单模式的启用应用 (SshVpnService 在 establish 前 / 白名单增删重建时调用)。
         * 空名单 = 全部流量不走隧道, DNS 必须退化为本地直连解析 —— 否则假 IP 无路由会破坏直连。
         *
         * @param whitelistEnabled 是否处于白名单模式 (dnsMode==2)。非白名单模式下调用方**本来就传
         *   空列表** (它只代表"没有白名单"), 此时必须为 false —— 否则默认 REMOTE 模式每次连接都会
         *   打出「白名单为空 · 所有应用均直连 (DNS 退化为 SYSTEM)」这条**完全相反**的 WARNING,
         *   在"日志是唯一诊断面"的前提下等于主动误导排障。
         */
        fun setWhitelistPackages(
            packages: List<String>,
            whitelistEnabled: Boolean = false,
        ) {
            whitelistPackages = packages
            if (!whitelistEnabled) {
                // 中性表述: 非白名单模式下"空列表"只代表没有白名单, 流量其实**全部走隧道**,
                // 说成"所有应用均直连"是反的 (这条曾是 WARNING, 会直接把排障带偏)
                addLog("白名单未启用 · 全部应用流量走隧道", LogLevel.INFO)
                return
            }
            // 记录名单本身 (连接/重建/白名单变更时): 无需口述即可核对某应用是否走隧道
            if (packages.isEmpty()) {
                addLog(
                    "白名单为空 · 所有应用均直连 (DNS 退化为 SYSTEM 模式)",
                    LogLevel.WARNING,
                )
            } else {
                addLog(
                    "白名单生效 · ${packages.size} 个应用走隧道: ${packages.joinToString()}",
                    LogLevel.INFO,
                )
            }
        }

        fun addLog(
            message: String,
            level: LogLevel,
        ) {
            // 故障归因: **全仓唯一的落盘出口**, WARNING/ERROR 按静态前缀计数 (见 AppMetrics)。
            // 放这里而不是 appLogThrottled: 后者经 appLogSink 转调本函数, 两处都记会重复计数。
            AppMetrics.record(level, message)
            // 时间戳在写入侧生成: LogViewModel 打开界面时回放 replay 缓存不会把历史条目
            // 全部伪造成 "现在"; clear 也能按 cutoff 精确剔除存量
            _logFlow.tryEmit(
                LogLine(System.currentTimeMillis(), level, message),
            )
        }

        /** 清空日志 (replay 缓存 + 订阅方各自的列表由 LogViewModel 同步清理)。 */
        fun clearLogs() {
            _logFlow.resetReplayCache()
        }

        companion object {
            private const val TAG = "VpnController"
            private const val IPPROTO_UDP = 17
            private const val DNS_PORT = 53
            private const val SOCKET_TIMEOUT_MS = 5000
            private const val CONNECTION_CLEANUP_INTERVAL_MS = 60000L
            private const val STALE_CONNECTION_TIMEOUT_MS = 300000L
            private const val STATS_FLUSH_INTERVAL_MS = 100L

            /** 最小合法 IP 包长度 (IPv4 头 20 / IPv6 头 40); 小于此视为无法解析的残留字节。 */
            private const val MIN_IP_PACKET_BYTES = 20

            /** 数据面快照周期: 60s 一条, 让"隧道此刻是否还在搬数据"可见 (应用内日志唯一诊断面)。 */
            private const val DATA_SNAPSHOT_INTERVAL_MS = 60_000L

            // ---- 数据面静态诊断日志: TcpStateMachine/UdpRelay 由 DI 独立提供, 无本实例引用 ----
            private const val APP_LOG_THROTTLE_MS = 10_000L

            /** 节流表淘汰阈值: 取最大节流窗口 (SOCKS 失败/零回程等) 的 6 倍, 安全起见取 60s。 */
            private const val APP_LOG_THROTTLE_PRUNE_MS = 60_000L

            private var appLogSink: ((String, LogLevel) -> Unit)? = null

            /** 由 [init] 注册 (companion 无法引用实例 addLog, 只能走 sink)。 */
            private fun setAppLogSink(sink: (String, LogLevel) -> Unit) {
                appLogSink = sink
            }

            /**
             * 应用内日志 (用户无 adb, 这是唯一可见的诊断面)。
             * 默认 INFO —— **异常必须显式声明级别**, 否则正常的生命周期/观测日志会被刷成警告,
             * 真正的故障反而淹没在噪声里 (曾把"隧道通道就绪/连接结束"全标成 WARNING)。
             */
            fun appLog(
                message: String,
                level: LogLevel =
                    LogLevel.INFO,
            ) {
                appLogSink?.invoke(message, level)
            }

            // 同文案节流表: Play 下载失败会按秒级重试, 不节流会把应用内日志刷穿
            private val appLogThrottleMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

            /**
             * 同 [appLog], 同文案节流窗口内只记一次 (高频故障/重试风暴会刷穿 replay 缓存)。
             *
             * @param throttleKey 节流表的键, 默认 = [message]。**文案里拼进了动态内容
             *   (域名/IP/异常信息/计数) 的失败类日志必须传静态前缀**: 默认键随每条动态内容变化,
             *   重试风暴 (Play 每秒重连、每条连接的异常文案都不同) 会同时造成两件事 ——
             *   节流形同虚设 (每条都放行), 以及节流表被不同键灌爆后触发整表 clear,
             *   把其它文案已建立的节流窗口一次性全部解除, 正好刷穿 replay=500 日志缓存。
             *   生命周期类 (每连接一条、需要看域名区分) 的 INFO 日志保持默认键即可。
             *   节流表只存 Long 时间戳, 键短而固定才安全; [message] 仍按原样输出 (含动态细节)。
             */
            fun appLogThrottled(
                message: String,
                windowMs: Long = APP_LOG_THROTTLE_MS,
                level: LogLevel =
                    LogLevel.INFO,
                throttleKey: String = message,
            ) {
                val now = System.currentTimeMillis()
                val last = appLogThrottleMap[throttleKey]
                if (last != null && now - last < windowMs) return
                // 溢出时**只淘汰过期条目**, 不能整表 clear: 那会一次性解除所有节流窗口,
                // 让积压的高频文案同时放行, 正好刷穿 replay=500 缓存
                if (appLogThrottleMap.size > 512) {
                    appLogThrottleMap.entries.removeIf { now - it.value > APP_LOG_THROTTLE_PRUNE_MS }
                }
                if (appLogThrottleMap.size > 2048) appLogThrottleMap.clear()
                appLogThrottleMap[throttleKey] = now
                appLog(message, level)
            }

            /**
             * 纯策略函数 (可单测): 排除路由命中 → 直连; DOMAIN_SPLIT 非假 IP → 直连。
             * UDP 不走此判定 (UDP 已降级, 非 53 丢弃计数)。
             */
            internal fun computeShouldBypassTcp(
                dstIp: InetAddress,
                excludedRoutes: List<CidrRoute>,
                transportMode: DnsInterceptor.DnsTransport,
            ): Boolean {
                if (excludedRoutes.any { CidrRoute.matches(dstIp, it) }) return true
                return transportMode == DnsInterceptor.DnsTransport.DOMAIN_SPLIT && !isFakeIp(dstIp)
            }

            /**
             * 判定 IP 是否为 DnsInterceptor 分配的假 IP (198.18.0.0/15, fd00::/8)。
             */
            internal fun isFakeIp(ip: InetAddress): Boolean = VpnNetwork.isFakeIp(ip)

            /** 假 IP 的字符串形式 (是假 IP 才返回, 否则 null); 供引用计数表做 key。 */
            internal fun fakeIpOrNull(ip: InetAddress): String? = ip.hostAddress?.takeIf { isFakeIp(ip) }
        }

        /**
         * 启动 VPN 连接
         * 正确顺序: 隧道连接 → DNS 设置 → 数据包处理
         */
        suspend fun connect(
            server: ServerConfig,
            password: String? = null,
        ): Result<Unit> {
            if (isRunning) {
                addLog("VPN 已在运行中", LogLevel.WARNING)
                return Result.failure(IllegalStateException("VPN already running"))
            }

            currentServer = server
            isRunning = true
            // 新一轮连接: 分阶段计数与评估窗口清零, 避免上一会话的增量污染本轮归因
            StageCounters.reset()
            stageEvaluator.reset()
            // 故障归因按会话清零: "本会话 N 类 M 次" 必须名副其实
            AppMetrics.resetSession()
            // 新一轮连接: 清掉上一次的健康归因/出口 IP/流程阶段, 重新从"未验证"开始
            updateState {
                it.copy(
                    status = VpnState.VpnStatus.Connecting,
                    server = server,
                    verified = false,
                    failedStep = null,
                    exitIp = null,
                    connectStage = null,
                )
            }

            return try {
                // 1. 启动隧道插件 (内含 SSH TCP+握手, 连接流程中最慢阶段)
                val tunnelConfig = TunnelConfig.forSocks5(server, password)
                updateState { it.copy(connectStage = ConnectStage.TUNNEL) }
                addLog("正在连接隧道 (socks5)...", LogLevel.INFO)
                val tunnelResult = tunnelManager.startPlugin("socks5", tunnelConfig)
                if (tunnelResult.isFailure) {
                    val errorMsg = tunnelResult.exceptionOrNull()?.message ?: "Tunnel connection failed"
                    addLog("隧道连接失败: $errorMsg", LogLevel.ERROR)
                    throw Exception(errorMsg)
                }
                addLog("隧道连接成功: socks5", LogLevel.SUCCESS)

                // 3. 设置 DNS 拦截器
                updateState { it.copy(connectStage = ConnectStage.DNS) }
                packetProcessor.setDnsInterceptor(dnsInterceptor)
                // S5: IPv6 开关联动 — TUN 侧丢弃 v6 包 + DNS AAAA 回空应答
                packetProcessor.setEnableIPv6(server.enableIPv6)
                dnsInterceptor.setEnableIPv6(server.enableIPv6)
                val dnsModeValue = settingsDataStore.dnsMode.first()
                this.transportMode = dnsTransportFor(dnsModeValue, whitelistPackages)
                dnsInterceptor.setTransportMode(this.transportMode)
                if (dnsModeValue == DNS_MODE_WHITELIST && whitelistPackages.isEmpty()) {
                    addLog(
                        "白名单为空: 全部流量不走隧道, DNS 按本地直连解析 (避免假 IP 无路由)",
                        LogLevel.INFO,
                    )
                }
                dnsInterceptor.setDomainListManager(domainListManager)

                // 广告过滤: 连接时配置, 对所有 DNS 传输模式统一生效 (命中即回 0.0.0.0,
                //   不解析 / 不进缓存 / 不消耗隧道或系统 DNS); 规则 = 内置清单 + 用户自定义域名
                val adEnabled = settingsDataStore.adBlockEnabled.first()
                // 必须先接线再设开关: DnsInterceptor.adBlocker 不注入就是死代码
                // (isBlocked 恒 null 放行, 开关/规则全是摆设), 与本控制器持有同一 @Singleton 实例
                dnsInterceptor.setAdBlocker(adBlocker)
                dnsInterceptor.setEnabledAdBlock(adEnabled)
                if (adEnabled) {
                    launch {
                        try {
                            adBlocker.setMatcher(loadAdBlockRules())
                            addLog("广告过滤规则已加载", LogLevel.INFO)
                        } catch (e: Exception) {
                            addLog(
                                "广告规则加载失败, 将仅使用可用清单: ${e.message}",
                                LogLevel.WARNING,
                            )
                        }
                    }
                }

                // 传递系统 DNS 服务器到 DnsInterceptor (SYSTEM/DOMAIN_SPLIT 模式需要)
                val systemDns = getSystemDnsServers()
                dnsInterceptor.setSystemDnsServers(systemDns)

                // 设置 socket 保护函数: SYSTEM/DOMAIN_SPLIT 用于 DNS 绕过 VPN 直查;
                // REMOTE/WHITELIST 用于非 A/AAAA 查询 (MX/TXT/PTR) 走系统 DNS 直查, 避免被吞
                dnsInterceptor.setProtectFunction { socket ->
                    protectDatagramChannel?.invoke(socket) ?: false
                }

                // 域名分流模式: 启动时检查并后台刷新域名列表 (24h 间隔)
                if (transportMode == DnsInterceptor.DnsTransport.DOMAIN_SPLIT) {
                    launch {
                        val needRefresh = domainListManager.shouldRefresh()
                        addLog(
                            "域名列表刷新检查: ${if (needRefresh) "需要" else "无需"}",
                            LogLevel.DEBUG,
                        )
                        if (needRefresh) {
                            addLog("正在更新域名列表...", LogLevel.INFO)
                            domainListManager.update()
                        }
                    }
                }

                addLog("DNS 拦截器已配置 (模式: $transportMode)", LogLevel.INFO)

                // 4. 解析排除路由 (CIDR)
                updateState { it.copy(connectStage = ConnectStage.ROUTES) }
                excludedRoutes = baseExcludedRoutes()

                // SYSTEM 模式: 获取 DHCP 分配的 DNS 服务器，添加到绕过列表
                if (transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
                    for (dnsIp in systemDns) {
                        // 将 DNS 服务器 IP 转为 /32(/128) 路由加入排除列表
                        dnsHostRouteOrNull(dnsIp)?.let { excludedRoutes += it }
                    }
                    if (systemDns.isNotEmpty()) {
                        addLog(
                            "SYSTEM 模式: 绕过 VPN 的 DNS 服务器: ${systemDns.joinToString(", ")}",
                            LogLevel.INFO,
                        )
                    } else {
                        addLog("SYSTEM 模式: 未获取到系统 DNS，使用默认 8.8.8.8", LogLevel.WARNING)
                        // 兜底：添加常用公共 DNS 到排除路由
                        for (dnsIp in listOf("8.8.8.8", "1.1.1.1", "114.114.114.114")) {
                            dnsHostRouteOrNull(dnsIp)?.let { excludedRoutes += it }
                        }
                    }
                }

                if (excludedRoutes.isNotEmpty()) {
                    addLog("排除路由: ${excludedRoutes.size} 条规则", LogLevel.INFO)
                }

                // 5. 建 TUN 写出口 (单写者队列) 并接上 PacketProcessor
                startTunWriter()
                addLog("TUN 写回通道已就绪", LogLevel.DEBUG)

                // 6. 启动连接清理定时任务
                launch { connectionCleanupLoop() }
                addLog("连接清理任务已启动", LogLevel.DEBUG)

                // 6.1 启动独立 DNS 响应投递协程 (修复 DNS 死锁)
                launch { dnsResponseDeliveryLoop() }
                addLog("DNS 响应投递协程已启动", LogLevel.DEBUG)

                // 6.2 启动统计节流发布协程
                launch { statsFlushLoop() }
                addLog("统计发布协程已启动", LogLevel.DEBUG)

                updateState {
                    it.copy(
                        status = VpnState.VpnStatus.Connected,
                        stats = it.stats.copy(startTime = java.util.Date()),
                    )
                }

                addLog("VPN 连接已建立，开始处理数据包", LogLevel.SUCCESS)

                // 7. 启动数据包处理循环
                packetLoopJob?.cancel()
                packetLoopJob = launch { packetLoop(++tunGeneration) }

                Result.success(Unit)
            } catch (e: Exception) {
                addLog("连接失败: ${e.message}", LogLevel.ERROR)
                disconnect()
                Result.failure(e)
            }
        }

        /**
         * 断开 VPN 连接
         */
        suspend fun disconnect() {
            // F12-j: 释放对 VpnService 的 lambda 引用 (早退路径也必须清)
            setProtectFunction(null)
            setProtectTcpFunction(null)
            if (!isRunning) return

            addLog("正在断开 VPN 连接...", LogLevel.INFO)
            isRunning = false
            updateState { it.copy(status = VpnState.VpnStatus.Disconnecting) }

            // 取消所有子协程
            coroutineContext.cancelChildren()
            packetLoopJob?.cancel()
            packetLoopJob = null
            addLog("已取消所有子任务", LogLevel.DEBUG)

            // 排空 DNS 残留响应, 避免旧会话数据泄漏到下一次连接
            dnsInterceptor.clearPendingResponses()

            // 断开前补一条数据面快照: 60s 周期在 30~50s 的短会话里一次都打不出来, 而"无 adb"
            // 现场只有导出的日志 —— 资源节/回程闸门/地址惰性失效/在途通道 必须每条会话都留痕。
            // 位置很关键: 必须在 resetTcpState() (清 TCP 计数) 与 tunnelManager.stopAll() /
            // closeTunWriter() (拆掉 SSH 池与写队列) **之前**取样, 否则拿到的是空壳。
            emitDataPlaneSnapshot(force = true)

            // 清空 TCP 连接表, 跨会话残留不复用 (F6-4)
            packetProcessor.resetTcpState()

            // 断开 SSH 连接
            // 停止所有隧道插件
            try {
                addLog("正在断开隧道连接...", LogLevel.INFO)
                tunnelManager.stopAll()
                addLog("隧道连接已断开", LogLevel.INFO)
            } catch (e: Exception) {
                addLog("隧道断开错误: ${e.message}", LogLevel.ERROR)
            }

            // 关闭输入输出流
            try {
                inputStream?.close()
                closeTunWriter()
                outputStream?.close()
                addLog("TUN 数据流已关闭", LogLevel.DEBUG)
            } catch (_: Exception) {
            }

            // 清理状态
            vpnInterface = null
            inputStream = null
            outputStream = null
            currentServer = null

            updateState {
                it.copy(
                    status = VpnState.VpnStatus.Disconnected,
                    server = null,
                )
            }
            // 会话结束前打一次最终归因: 导出的日志末尾就能看到"这次会话哪类故障占主导",
            // 不用在几百行里数 (无 adb 现场的唯一判据)
            AppMetrics.summary().takeIf { it.isNotEmpty() }?.let { addLog(it, LogLevel.INFO) }
            addLog("VPN 连接已完全断开", LogLevel.INFO)
        }

        /** 当前服务器配置的基础排除路由 (IP 字面量 CIDR, 无法解析的条目跳过)。 */
        private fun baseExcludedRoutes() = currentServer?.excludedRoutes?.mapNotNull(CidrRoute::parse) ?: emptyList()

        /** DNS 服务器 IP → 单主机排除路由 (/32 或 /128); 解析失败上报并返回 null。 */
        private fun dnsHostRouteOrNull(dnsIp: String): CidrRoute? =
            try {
                val addr = InetAddress.getByName(dnsIp)
                CidrRoute(addr, if (addr is Inet6Address) 128 else 32)
            } catch (e: Exception) {
                appLogThrottled(
                    "排除路由 DNS 解析失败 · $dnsIp — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "排除路由 DNS 解析失败",
                )
                null
            }

        /**
         * 实时更新 DNS 传输模式
         */
        suspend fun updateDnsMode() {
            if (!isRunning) return
            val dnsModeValue = settingsDataStore.dnsMode.first()
            this.transportMode = dnsTransportFor(dnsModeValue, whitelistPackages)
            dnsInterceptor.setTransportMode(this.transportMode)
            dnsInterceptor.setDomainListManager(domainListManager)

            // SYSTEM/DOMAIN_SPLIT 模式: 设置/更新 socket 保护函数，用于 DNS 查询绕过 VPN
            if (this.transportMode == DnsInterceptor.DnsTransport.SYSTEM ||
                this.transportMode == DnsInterceptor.DnsTransport.DOMAIN_SPLIT
            ) {
                addLog(
                    ">>> [VpnController] updateDnsMode: 设置 DNS Interceptor 保护函数 ($transportMode)",
                    LogLevel.DEBUG,
                )
                dnsInterceptor.setProtectFunction { socket ->
                    if (IS_DEBUG) {
                        addLog(
                            ">>> [VpnController] 保护函数被调用: socket=$socket",
                            LogLevel.DEBUG,
                        )
                    }
                    val result = protectDatagramChannel?.invoke(socket) ?: false
                    if (IS_DEBUG) {
                        addLog(
                            ">>> [VpnController] 保护函数返回: $result",
                            LogLevel.DEBUG,
                        )
                    }
                    result
                }
            }

            // 所有模式都不排除 DNS 服务器，让 DNS 流量走 VPN 隧道 (只保留服务器配置的基础排除)
            excludedRoutes = baseExcludedRoutes()
            addLog(
                "DNS 模式已切换: $transportMode, 排除路由: ${excludedRoutes.size} 条",
                LogLevel.INFO,
            )
        }

        /**
         * 强制重置状态 (超时后调用)
         *
         * 必须与 [disconnect] 的清理段保持一致 —— 少了任意一项就会跨会话泄漏:
         * ① `clearPendingResponses()` 不做, 未排空的 DNS 应答会在下一次 connect 重开的
         *    dnsResponseDeliveryLoop 里被投给**新会话**的 TUN;
         * ② `resetTcpState()` 不做, 旧连接表原样留到新会话 (新 SYN 虽会关旧重建,
         *    但关闭包/黑洞统计会算在新会话头上);
         * ③ `tunnelManager.stopAll()` 不做, SSH 会话池与本地 SOCKS 监听 socket 泄漏 ——
         *    本函数先置 `isRunning=false`, 之后 Service onDestroy 里的 `disconnect()` 会在
         *    开头提前 return, 永远轮不到它清理隧道;
         * ④ TUN 流不关, fd 泄漏 (与 ③ 同因, disconnect 的关闭段也被跳过)。
         * 调用点: 断开卡在 Disconnecting 超 3s 后 MainViewModel 直接 stopService + forceReset。
         */
        suspend fun forceReset() {
            // F12-j: 释放对 VpnService 的 lambda 引用
            setProtectFunction(null)
            setProtectTcpFunction(null)
            isRunning = false
            try {
                coroutineContext.cancelChildren()
            } catch (_: Exception) {
            }
            packetLoopJob?.cancel()
            packetLoopJob = null

            // 投递协程已随 cancelChildren 停掉, 此刻排空不会与新会话竞争
            dnsInterceptor.clearPendingResponses()
            packetProcessor.resetTcpState()

            // 与 disconnect() 的清理段对齐: 先停隧道 (SSH 会话/本地代理), 再关 TUN 流
            try {
                tunnelManager.stopAll()
            } catch (e: Exception) {
                addLog("隧道断开错误: ${e.message}", LogLevel.ERROR)
            }
            try {
                inputStream?.close()
                closeTunWriter()
                outputStream?.close()
            } catch (_: Exception) {
            }

            vpnInterface = null
            inputStream = null
            outputStream = null
            currentServer = null
            updateState {
                it.copy(
                    status = VpnState.VpnStatus.Disconnected,
                    server = null,
                )
            }
        }

        /**
         * 设置 VPN 接口 (由 VpnService 调用)
         */
        fun setVpnInterface(fd: FileDescriptor) {
            vpnInterface = fd
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
        }

        /**
         * 重建 TUN 接口 (白名单/模式热更新时由 VpnService 调用)
         * 关闭旧 TUN 流, 替换为新的 fd, 并重启数据包循环。SSH 隧道不受影响。
         */
        fun rebuildTunInterface(fd: FileDescriptor) {
            if (!isRunning) {
                // 未运行时不换流, 关闭传入 fd 防止泄漏
                try {
                    java.io.FileInputStream(fd).close()
                } catch (_: Exception) {
                }
                return
            }
            val generation = ++tunGeneration
            try {
                inputStream?.close()
                closeTunWriter()
                outputStream?.close()
            } catch (_: Exception) {
            }
            vpnInterface = fd
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
            startTunWriter()
            packetLoopJob?.cancel()
            packetLoopJob = launch { packetLoop(generation) }
            addLog("TUN 接口已重建", LogLevel.INFO)
        }

        /**
         * 数据包处理主循环
         * @param generation 接口代次, 用于检测接口重建后旧循环退出
         */
        private fun packetLoop(generation: Long) {
            Log.d("VpnController", "packetLoop started gen=$generation")
            while (isRunning && inputStream != null && generation == tunGeneration) {
                try {
                    // T-A05: 帧边界。下面的 read 会整块覆盖 readBuffer, 惰性地址必须在本次迭代内读完
                    // (越帧读取会被 IpPacketParser.staleAddressReads 计数, 见 ParsedPacket 的类 KDoc)。
                    IpPacketParser.newFrame()
                    val bytesRead = inputStream!!.read(readBuffer.array())
                    // EOF 不忙循环: 转异常走 catch 的连续失败计数 + backoff
                    if (bytesRead < 0) throw IOException("TUN read EOF")
                    // 空读不 continue — detekt LoopWithTooManyJumpStatements: loop 内只留 catch 的 break
                    if (bytesRead > 0) {
                        consecutiveReadFailures = 0
                        bytesUpCounter.addAndGet(bytesRead.toLong())

                        readBuffer.limit(bytesRead)
                        readBuffer.position(0)

                        // 一次 TUN read 常常包含**多个粘连的 IP 包** (内核批量投递)。
                        // 旧实现只处理第一个就 clear, 其余全部静默丢弃 —— 客户端因此不断重传,
                        // 现场表现为"上行 5.6MB 而隧道只收到 166KB, 各丢弃计数全为 0"。
                        // 按**实际解析出的包数**计: 原来是"每次 read 计 1 个", 定界失败时会虚高。
                        val received = drainPackets(readBuffer)
                        if (received > 0) packetsReceivedCounter.addAndGet(received.toLong())

                        readBuffer.clear()
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        consecutiveReadFailures++
                        Log.e("VpnController", "packetLoop error ($consecutiveReadFailures): ${e.message}")
                        // 计数不进文案: appLogThrottled 以整条 message 为节流 key,
                        // "#$N" 每次唯一会让节流彻底失效 (>=20 那条 ERROR 才带计数)
                        appLogThrottled(
                            "TUN 读取失败 · ${e::class.simpleName}: ${e.message}",
                            level = LogLevel.WARNING,
                            throttleKey = "TUN 读取失败",
                        )
                        if (consecutiveReadFailures >= 20) {
                            // F12-i 兜底: 连续失败说明 TUN 已死 (如断开竞态), 停止忙循环并上报 Failed,
                            // 由 SshVpnService 观察到后执行完整清理 (保留 lastServerId 供重连)。
                            // 不在此置 isRunning=false — 否则 disconnect 的资源清理链在
                            // isVpnRunning() 检查处短路, SSH tunnel 会泄漏
                            Log.e("VpnController", "packetLoop failing continuously, stopping")
                            appLog(
                                "TUN 连续读取失败 $consecutiveReadFailures 次 · 数据循环已停止 (接口已死)",
                                LogLevel.ERROR,
                            )
                            updateState {
                                it.copy(
                                    status = VpnState.VpnStatus.Failed,
                                    error = e.message,
                                    failedStep = HealthStep.TUN,
                                )
                            }
                            break
                        }
                        updateState { it.copy(error = e.message) }
                        Thread.sleep(100)
                    }
                }
            }
            Log.d("VpnController", "packetLoop ended")
        }

        /**
         * 处理一次读取内的所有粘连 IP 包。
         *
         * 一次 TUN read 常包含多个包 (内核批量投递); 旧实现只处理第一个就 clear, 其余静默
         * 丢弃 —— 客户端因此不断重传, 现场表现为"上行 5.6MB 而隧道只收到 166KB,
         * 各类丢弃计数全为 0"。返回本次解析出的包数。
         */
        private fun drainPackets(buffer: ByteBuffer): Int {
            var count = 0
            var progressed = true
            while (progressed && buffer.remaining() >= MIN_IP_PACKET_BYTES) {
                if (processPacket(buffer) <= 0) break
                count++
                progressed = buffer.remaining() >= MIN_IP_PACKET_BYTES
            }
            if (buffer.remaining() > 0) trailingBytesDropped.addAndGet(buffer.remaining().toLong())
            return count
        }

        /**
         * 处理一个 IP 包 (进入时 `buffer.position()` 即包起点)。
         *
         * @return 本次消费的字节数; 0 = 未能识别/未定界 (调用方丢弃剩余避免死循环)。
         */
        private fun processPacket(buffer: ByteBuffer): Int {
            val outerLimit = buffer.limit()
            try {
                // 解析 IP 版本
                val firstByte = buffer.get(buffer.position()).toInt() and 0xFF
                val version = firstByte shr 4
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "processPacket: firstByte=0x${"%02x".format(firstByte)} " +
                            "version=$version remaining=${buffer.remaining()}",
                    )

                    // Debug: dump first 16 bytes
                    val debugBytes = ByteArray(16)
                    val origPos = buffer.position()
                    buffer.get(debugBytes)
                    buffer.position(origPos)
                    Log.d(
                        TAG,
                        "raw bytes: ${debugBytes.joinToString("") { "%02x".format(it) }}",
                    )
                }

                val fd = vpnInterface
                if (fd == null) {
                    droppedNoTun.incrementAndGet()
                    Log.w("VpnController", "VPN interface is null, skipping packet")
                    appLogThrottled(
                        "TUN 接口为空 · 数据包被丢弃 (计数进快照)",
                        level = LogLevel.WARNING,
                        throttleKey = "TUN 接口为空",
                    )
                    return 0
                }

                // 某些 Android 设备返回的包带前缀 (tun_pi flags+proto 或 PacketInfo)，版本字段为 0
                // 逐字节扫描寻找有效 IP 版本 (4 或 6)。**只算偏移, 不动 position/limit** ——
                // 定界 (clamp limit) 必须等 packetStart 确定后一次完成, 提前 slice 会让
                // `parseIpv4Header` 的 payloadLength 又变回"到 buffer 末尾"。
                var packetStart = buffer.position()
                var workVersion = version
                if (version == 0 && buffer.remaining() >= 5) {
                    val maxSkip = minOf(buffer.remaining() - 1, 8) // 最多跳 8 字节
                    var found = false
                    for (skip in 1..maxSkip) {
                        val probeVersion = (buffer.get(packetStart + skip).toInt() shr 4) and 0x0F
                        if (probeVersion == 4 || probeVersion == 6) {
                            packetStart += skip
                            workVersion = probeVersion
                            found = true
                            Log.d(
                                "VpnController",
                                "skipped $skip bytes prefix, version=$probeVersion",
                            )
                            break
                        }
                    }
                    if (!found) {
                        val dump = ByteArray(minOf(16, buffer.remaining())) { buffer.get(packetStart + it) }
                        Log.d(
                            "VpnController",
                            "unrecognized packet prefix, first bytes: ${dump.joinToString("") { "%02x".format(it) }}",
                        )
                        return 0
                    }
                }

                // 定界: 用 IP 头自带的长度字段, 而不是 buffer.remaining() —— 一次 TUN read 里
                // 粘连的后续包必须排除在本包之外, 否则会被算进本包 payload 转发进隧道
                // (字节流被污染 + 本包 ACK 推过了头), 而后续包又被丢弃。
                // 版本未知 (ipPacketTotalLength 返回 -1) 与截断/字段非法走同一个出口:
                // 无法确定下一个包起点, 丢弃剩余避免死循环。
                val totalLen = IpPacketParser.ipPacketTotalLength(buffer, packetStart)
                if (totalLen < MIN_IP_PACKET_BYTES || packetStart + totalLen > outerLimit) {
                    if (workVersion != 4 && workVersion != 6) {
                        addLog(">>> [VpnController] 未知 IP 版本: $workVersion，丢弃", LogLevel.DEBUG)
                    }
                    return 0
                }

                buffer.position(packetStart)
                buffer.limit(packetStart + totalLen)
                try {
                    // 排除路由/域名分流: 不再 writeToTun 回注 —— 包已进 TUN, 用户态无法塞回物理网卡,
                    // 回注 = ip_forward 黑洞或 0/0 路由读写死循环 (F1)。
                    // 仅 SYSTEM 模式 DNS(UDP:53) 走 protected socket 直接转发 (对 UDP 有效, 非回注);
                    // TCP 落入 PacketProcessor → forwardSynToTunnel → shouldBypassTcp → 用户态直连;
                    // UDP 非 53 由 UdpRelay 丢弃计数。
                    // 旁路判定改为**直接在报文缓冲区里做前缀匹配** (T-A04): 旧路径为每个包构造 dstIp
                    // 并对每条排除路由克隆一次地址字节 (N 条规则 = N+1 次分配/包)。dstIp 只在真正要
                    // 转发 (SYSTEM 模式 DNS:53) 时才构造。
                    val bypassVpn =
                        excludedRoutes.isNotEmpty() && shouldBypassVpnRaw(buffer, workVersion)
                    val dstIp =
                        if (bypassVpn && isSystemDnsQuery(buffer, workVersion)) {
                            extractDstIp(buffer, workVersion)
                        } else {
                            null
                        }
                    if (dstIp != null) {
                        forwardDnsBypassPacket(buffer, dstIp, workVersion)
                    } else {
                        when (workVersion) {
                            // F12-e: false = 未处理 → 丢弃计数已在 PacketProcessor 内完成;
                            // 不 writeToTun 回注 —— 包已进 TUN, 回注是黑洞或读写死循环 (F1 同类)
                            4 -> packetProcessor.processIpv4Packet(buffer)
                            6 -> packetProcessor.processIpv6Packet(buffer)
                        }
                    }
                } finally {
                    // limit 必须还原, 否则 drainPackets 后续所有包都按错位的边界解析
                    buffer.limit(outerLimit)
                }
                buffer.position(packetStart + totalLen)
                return totalLen
            } catch (e: Exception) {
                // 异常路径同样要还原 limit (正常路径由内层 finally 负责)
                try {
                    buffer.limit(outerLimit)
                } catch (_: Exception) {
                }
                Log.e("VpnController", "processPacket error: ${e.message}")
                appLogThrottled(
                    "数据包处理异常 · ${e::class.simpleName}: ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "数据包处理异常",
                )
                return 0
            }
        }

        private fun extractDstIp(
            buffer: ByteBuffer,
            version: Int,
        ): InetAddress? =
            try {
                buffer.mark()
                buffer.position(buffer.position() + (if (version == 4) 16 else 24)) // Skip to dst IP
                if (version == 4) {
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    InetAddress.getByAddress(bytes)
                } else {
                    val bytes = ByteArray(16)
                    buffer.get(bytes)
                    InetAddress.getByAddress(bytes)
                }
            } catch (_: Exception) {
                null
            } finally {
                buffer.reset()
            }

        /**
         * SYSTEM 模式下的 DNS 查询 (UDP:53) —— 只有它能走 protected socket 直接转发 (F1)。
         * 单独拆出来是因为合并进调用点会让条件数超过 detekt 的 ComplexCondition 阈值。
         */
        private fun isSystemDnsQuery(
            buffer: ByteBuffer,
            version: Int,
        ): Boolean =
            transportMode == DnsInterceptor.DnsTransport.SYSTEM &&
                extractProtocol(buffer, version) == IPPROTO_UDP &&
                extractDstPort(buffer, version) == DNS_PORT

        private fun extractProtocol(
            buffer: ByteBuffer,
            version: Int,
        ): Int =
            try {
                buffer.mark()
                val protoOffset = if (version == 4) 9 else 6
                val proto = buffer.get(buffer.position() + protoOffset).toInt() and 0xFF
                buffer.reset()
                proto
            } catch (_: Exception) {
                -1
            }

        private fun extractDstPort(
            buffer: ByteBuffer,
            version: Int,
        ): Int =
            try {
                buffer.mark()
                val ipHeaderLen =
                    if (version == 4) {
                        (buffer.get(buffer.position()).toInt() and 0x0F) * 4
                    } else {
                        40
                    }
                val portOffset = buffer.position() + ipHeaderLen + 2
                val port =
                    ((buffer.get(portOffset).toInt() and 0xFF) shl 8) or
                        (buffer.get(portOffset + 1).toInt() and 0xFF)
                buffer.reset()
                port
            } catch (_: Exception) {
                -1
            }

        /**
         * 排除路由直连判定 (**零分配**): 直接在报文缓冲区里对每条路由做前缀匹配。
         *
         * 旧写法 `excludedRoutes.any { CidrRoute.matches(dstIp, it) }` 需要先为每个包构造 dstIp
         * (另一次 InetAddress 分配), 再对每条路由克隆 `InetAddress.address`。
         * 语义与 [computeShouldBypassTcp] 里的同一分支一致 (同一 [CidrRoute] 算法), 差别只是
         * 数据来源是报文缓冲区而非 InetAddress; 由 `CidrRouteTest` 的等价性矩阵锁定。
         *
         * @param buffer 当前包缓冲区 (position 已指向包起点)
         * @param version 4 / 6
         */
        private fun shouldBypassVpnRaw(
            buffer: ByteBuffer,
            version: Int,
        ): Boolean {
            val routes = excludedRoutes
            if (routes.isEmpty()) return false
            val isV4 = version == 4
            val dstOffset = buffer.position() + if (isV4) 16 else 24
            val ipSize = if (isV4) 4 else 16
            for (i in routes.indices) {
                if (CidrRoute.matchesRaw(buffer, dstOffset, ipSize, routes[i])) {
                    Log.d("VpnController", "shouldBypassVpn: TRUE (excludedRoutes=${routes.size})")
                    return true
                }
            }
            return false
        }

        /**
         * F1: TCP 是否走用户态直连 (不经隧道)。
         * 排除路由命中, 或 DOMAIN_SPLIT 模式下未命中域名列表 (拿到真实 IP 而非假 IP)。
         */
        fun shouldBypassTcp(
            dstIp: InetAddress,
            @Suppress("UNUSED_PARAMETER") dstPort: Int,
        ): Boolean = computeShouldBypassTcp(dstIp, excludedRoutes, transportMode)

        private fun writeDnsResponse(response: DnsInterceptor.DnsResponse) {
            try {
                // F10: 源 IP 跟随响应本身 (查询的目标 DNS), 不再硬编码 TUN_IP
                val packet =
                    packetProcessor.buildUdpResponsePacket(
                        srcIp = response.srcIp.address,
                        dstIp = response.dstIp.address,
                        srcPort = 53,
                        dstPort = response.dstPort,
                        payload = response.data,
                    )
                // 只有真写进 TUN 才算一次"DNS 应答已投递": writeToTun 吞异常返回 false 时
                // 无脑计成功会让分阶段健康把"应答根本没出去"当成健康窗口
                if (writeToTun(packet)) {
                    StageCounters.onDnsDelivered()
                }
            } catch (e: Exception) {
                Log.e("VpnController", "writeDnsResponse failed: ${e.message}", e)
                // src/dst 不进文案 (每条 DNS 应答都不同 → 节流 key 唯一, TUN 持续故障时
                // 会刷穿 replay=500 缓存把真正要看的日志挤掉); 详情只进 logcat
                appLogThrottled(
                    "DNS 响应写入 TUN 失败 · ${e::class.simpleName}: ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "DNS 响应写入 TUN 失败",
                )
            }
        }

        /**
         * 独立 DNS 响应投递协程
         * 修复 REMOTE 模式下 DNS 死锁：DNS 响应不再依赖 processPacket 轮询，
         * 而是由独立协程持续投递到 TUN。
         */
        private suspend fun dnsResponseDeliveryLoop() {
            while (isRunning) {
                try {
                    val dnsResponse = dnsInterceptor.pollResponse()
                    if (dnsResponse != null) {
                        writeDnsResponse(dnsResponse)
                    } else {
                        delay(10)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 协程一抛即死 = 之后所有 DNS 应答都投不出去 (解析全挂却无人知), 必须吞住继续
                    appLogThrottled(
                        "DNS 响应投递协程异常 — ${e.message} (循环继续)",
                        level = LogLevel.ERROR,
                        throttleKey = "DNS 响应投递协程异常",
                    )
                    delay(100)
                }
            }
        }

        /**
         * 建 TUN 写出口 (单写者队列) 并接上 [PacketProcessor]。幂等。
         *
         * 旧实现是"谁产生包谁抢 `@Synchronized` 写" —— packetLoop 的 ACK、DNS 投递协程、每条 SSH
         * 回程线程共用一把 monitor, 一条慢连接就能把其它连接一起拖住。改成 [TunWriter] 后:
         * 控制包 (可丢) 与回程数据 (必须送达) 语义分开, packetLoop 永不阻塞。
         *
         * 因为**同一时刻只有一个线程**在写输出流, 写入本身不再需要互斥 —— 这与 T-20 遗留的
         * "`OutputStream.write(byte[])` 整包原子性未经真机验证"不冲突: 那条遗留针对的是**并发写**
         * 交错, 单写者结构下并发根本不存在 (不是"赌它原子", 而是"没有第二个写者")。
         */
        private fun startTunWriter() {
            closeTunWriter()
            val stream =
                outputStream ?: run {
                    addLog("TUN 写出口未建立: 输出流为空", LogLevel.WARNING)
                    return
                }
            tunWriter =
                TunWriter(
                    onWritten = { size ->
                        bytesDownCounter.addAndGet(size.toLong())
                        packetsSentCounter.incrementAndGet()
                        StageCounters.onTunWrite(success = true)
                    },
                    onWriteFailed = { cause ->
                        StageCounters.onTunWrite(success = false)
                        appLogThrottled(
                            "TUN 写入失败 · " +
                                (cause?.let { "${it::class.simpleName}: ${it.message}" } ?: "输出流未就绪"),
                            level = LogLevel.WARNING,
                            throttleKey = "TUN 写入失败",
                        )
                    },
                    onControlDropped = { size ->
                        droppedTunControlBytes.addAndGet(size.toLong())
                        droppedTunControlPackets.incrementAndGet()
                        appLogThrottled(
                            "TUN 写队列已满 · 丢弃控制包 ${size}B (客户端会重传; 回程数据不丢)",
                            level = LogLevel.WARNING,
                            throttleKey = "TUN 写队列已满",
                        )
                    },
                ).also { it.attach(stream) }
            packetProcessor.setTunSink(tunWriter)
        }

        /** 停 TUN 写出口: 先停写线程 (排空队列 + 释放等待者), 再由调用方关流。幂等。 */
        private fun closeTunWriter() {
            tunWriter?.close()
            tunWriter = null
            packetProcessor.setTunSink(null)
        }

        /**
         * 写 TUN (回程数据 / DNS 应答语义)。
         *
         * **只有真正写成功才累计下行字节/包数** —— 早先计数在 try-catch 之外, 写失败
         * (流为 null / IOException) 也会被算进 "下行 7MB", 数据面快照据此报出根本没送到应用的
         * 流量, 把"隧道在搬数据"和"隧道全死"混成同一副样子。现在计数在 [TunWriter] 的写入线程
         * 回调里完成, 语义不变 (返回 true = 真的进了 TUN)。
         *
         * @return 是否已成功写入 TUN (调用方据此决定要不要计一次分阶段成功信号)
         */
        fun writeToTun(data: ByteArray): Boolean {
            val writer = tunWriter
            if (writer == null) {
                // 与旧行为一致: 没有出口就是一次失败 (分阶段健康的 TUN 段要能看到)
                StageCounters.onTunWrite(success = false)
                return false
            }
            return writer.writeBlocking(data)
        }

        /**
         * 节流发布连接统计: 热点路径只累加原子计数, 每 100ms 汇总一次到 StateFlow,
         * 避免每包触发 MutableStateFlow 发射与 collector 唤醒 (SshVpnService + MainViewModel)。
         */
        private suspend fun statsFlushLoop() {
            // 置为当前时间: 否则首帧就满足 >=60s, 连接后 ~100ms 打一条
            // "数据面快照 … 速率待下一窗口" 的空基线噪声
            lastSnapshotAtMs = System.currentTimeMillis()
            // 基线同时取当前值: 计数器是**跨会话累计**的, 不重置会让首窗口速率
            // 把上个会话已传的字节也摊进来 (首次启动时为 0, 无影响)
            lastSnapshotUp = bytesUpCounter.get()
            lastSnapshotDown = bytesDownCounter.get()
            while (isRunning) {
                delay(STATS_FLUSH_INTERVAL_MS)
                // 整段必须包 try: 任一快照字段抛异常都会让本协程退出, 流量统计从此冻结在
                // 最后一帧、60s 数据面快照永久消失 —— 而这是无 adb 现场唯一的观测面。
                // delay 留在 try 之外: 取消时必须直接抛出, 不能被下面的 catch 吞掉。
                try {
                    _connectionStats.update {
                        it.copy(
                            bytesSent = bytesDownCounter.get(),
                            bytesReceived = bytesUpCounter.get(),
                        )
                    }
                    emitDataPlaneSnapshot(force = false)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("VpnController", "statsFlushLoop failed: ${e.message}", e)
                    appLogThrottled(
                        "统计发布异常 · ${e::class.simpleName}: ${e.message} — 循环继续",
                        level = LogLevel.WARNING,
                        throttleKey = "统计发布异常",
                    )
                }
            }
        }

        /**
         * 打一条**数据面快照** (60s 周期 / 断开前强制)。
         *
         * 两个调用点: [statsFlushLoop] 的 60s 周期, 与 [disconnect] 断开前的强制一条。
         * 后者是必需的 —— 真机试用的会话普遍只有 30~50s (装完就断/来回切服务器),
         * 60s 周期**一次都打不出来**, 于是"无 adb 现场"看不到 资源节 / 回程闸门等待 /
         * 地址惰性失效 / 在途通道 这些计数, 正是本轮真机日志暴露的观测面缺口。
         *
         * `@Synchronized`: `statsFlushLoop` 被 [disconnect] 里的 `cancelChildren()` 取消时可能
         * 正跑到这里 (取消是协作式的), 与强制快照并发写 `lastSnapshotUp/Down` 基线会让速率算错。
         *
         * @param force true = 不看 60s 间隔, 立即输出 (断开前那次)
         */
        @Synchronized
        private fun emitDataPlaneSnapshot(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastSnapshotAtMs < DATA_SNAPSHOT_INTERVAL_MS) return
            val prevAt = lastSnapshotAtMs
            lastSnapshotAtMs = now
            val windowSec = if (prevAt == 0L) 0 else (now - prevAt) / 1000
            val up = bytesUpCounter.get()
            val down = bytesDownCounter.get()
            val rateText = DataPlaneSnapshot.rateText(windowSec, up, down, lastSnapshotUp, lastSnapshotDown)
            lastSnapshotUp = up
            lastSnapshotDown = down
            val tunnelStats = tunnelManager.getActiveOrFallback().tunnelDiagnostics()
            // 文案拼装与速率算术已抽到 DataPlaneSnapshot (纯函数 + 单测锁死方向语义):
            // up = 读 TUN = 上行, down = 写 TUN = 下行, 别再弄反。
            appLog(
                DataPlaneSnapshot(
                    upBytes = up,
                    downBytes = down,
                    packetsReceived = packetsReceivedCounter.get(),
                    packetsSent = packetsSentCounter.get(),
                    rateText = rateText,
                    tunControlDropped = droppedTunControlPackets.get(),
                    tunControlDroppedBytes = droppedTunControlBytes.get(),
                    tunTrailingDropped = trailingBytesDropped.get(),
                    tunNoInterfaceDropped = droppedNoTun.get(),
                    // 资源占用: 泄漏类问题 (队列堆积 / 映射表打满) 的唯一可见面
                    resourceDiagnostics =
                        "TUN 写队列 ${tunWriter?.depth() ?: 0} · ${dnsInterceptor.diagnostics()} · " +
                            packetProcessor.domainStatsDiagnostics(),
                    tcpDiagnostics = packetProcessor.tcpDiagnostics(),
                    udpDiagnostics = packetProcessor.udpDiagnostics(),
                    ipv6Diagnostics = packetProcessor.ipv6Diagnostics(),
                    tunnelDiagnostics = tunnelStats,
                    // 本会话 WARNING/ERROR 归因 (无故障时空串, 该节整省)。
                    // 断开前那条不吃这一节: 紧接着就是完整的 `故障归因` 行, 不重复。
                    failureSummary = if (force) "" else AppMetrics.summary(),
                ).render(),
            )
        }

        /**
         * SYSTEM 模式 DNS 绕过: 通过受保护 socket 转发 DNS 查询到物理网卡
         */
        private fun forwardDnsBypassPacket(
            buffer: ByteBuffer,
            dstIp: InetAddress,
            version: Int,
        ) {
            // 复制必要的数据到新数组，避免与主线程共享 buffer
            val packetData = ByteArray(buffer.remaining())
            buffer.duplicate().get(packetData)

            executor.submit {
                var socket: DatagramSocket? = null
                try {
                    if (IS_DEBUG) {
                        addLog(
                            "DNS 绕过转发 · 发起 dstIp=$dstIp version=$version size=${packetData.size}",
                            LogLevel.DEBUG,
                        )
                    }
                    socket = DatagramSocket()
                    val protected = protectDatagramChannel?.invoke(socket!!) ?: false
                    if (IS_DEBUG) {
                        addLog(
                            "DNS 绕过转发 · protect()=$protected",
                            LogLevel.DEBUG,
                        )
                    }
                    if (!protected) {
                        appLogThrottled(
                            "DNS 绕过保护失败 · protect() 返回 false — 查询可能被 VPN 回环",
                            level = LogLevel.WARNING,
                            throttleKey = "DNS 绕过保护失败",
                        )
                    }
                    socket!!.soTimeout = SOCKET_TIMEOUT_MS

                    // DNS 消息 = IP 载荷 − IP 头 − **8 字节 UDP 头**。
                    // 少减 8 会把 UDP 头一起当 DNS 发出去 (dstPort=0x0035 被对端当成 QDCOUNT
                    // → 丢包 → 5s 超时), 而日志只报 "DNS 响应超时", 排障方向被完全带偏。
                    val ipHeaderLen = IpPacketParser.ipHeaderLength(packetData, version)
                    val payloadStart = IpPacketParser.udpPayloadOffset(packetData, version)
                    if (payloadStart < 0) {
                        appLogThrottled(
                            "DNS 绕过转发失败 · UDP 载荷偏移非法 (ipHeaderLen=$ipHeaderLen)",
                            level = LogLevel.WARNING,
                            throttleKey = "DNS 绕过转发失败",
                        )
                        return@submit
                    }

                    val payload = packetData.copyOfRange(payloadStart, packetData.size)

                    val dnsServer = dstIp.hostAddress
                    val packet =
                        java.net.DatagramPacket(
                            payload,
                            payload.size,
                            java.net.InetAddress.getByName(dnsServer),
                            53,
                        )
                    if (IS_DEBUG) {
                        addLog(
                            "DNS 绕过转发 · 发送查询到 $dnsServer:53 payload=${payload.size}B",
                            LogLevel.DEBUG,
                        )
                    }
                    socket!!.send(packet)

                    // 接收响应
                    val responseBuf = ByteArray(512)
                    val responsePacket = java.net.DatagramPacket(responseBuf, responseBuf.size)
                    socket!!.receive(responsePacket)
                    val responseData = responseBuf.copyOfRange(0, responsePacket.length)
                    if (IS_DEBUG) {
                        addLog(
                            "DNS 绕过转发 · 收到响应 ${responsePacket.address}:${responsePacket.port} " +
                                "(${responseData.size}B)",
                            LogLevel.DEBUG,
                        )
                    }

                    // 从复制的原始包提取源 IP 和源端口
                    var srcIp: InetAddress
                    var srcPort: Int
                    try {
                        val srcIpBytes =
                            if (version == 4) {
                                packetData.copyOfRange(12, 16)
                            } else {
                                packetData.copyOfRange(8, 24)
                            }
                        srcIp = InetAddress.getByAddress(srcIpBytes)
                        // 源端口 = IP 头之后的 UDP 头前 2 字节。用 ipHeaderLen 而非硬编码 20:
                        // IPv4 带选项时 IHL > 20, 硬编码会读到选项字节 → 应答发错端口。
                        srcPort = IpPacketParser.readU16(packetData, ipHeaderLen)
                        if (IS_DEBUG) {
                            addLog(
                                "DNS 绕过转发 · 解析源地址 srcIp=$srcIp srcPort=$srcPort",
                                LogLevel.DEBUG,
                            )
                        }
                    } catch (e: Exception) {
                        appLogThrottled(
                            "DNS 绕过转发失败 · 解析源 IP/端口异常 — ${e.message}",
                            level = LogLevel.ERROR,
                            throttleKey = "DNS 绕过转发失败",
                        )
                        srcIp = InetAddress.getByName(VpnNetwork.TUN_GATEWAY)
                        srcPort = 53
                    }

                    // F10: 源 IP = 被查询的 DNS 服务器 (入参 dstIp), 非 TUN_IP
                    val responsePkt =
                        packetProcessor.buildUdpResponsePacket(
                            srcIp = dstIp.address,
                            dstIp = srcIp.address,
                            srcPort = 53,
                            dstPort = srcPort,
                            payload = responseData,
                        )
                    if (IS_DEBUG) {
                        addLog(
                            "DNS 绕过转发 · 构造响应包 dstPort=$srcPort size=${responsePkt.size}",
                            LogLevel.DEBUG,
                        )
                    }
                    // 必须认返回值: 否则 DNS 绕过路径的写失败既不计 onDnsDelivered 也不计
                    // onTunWrite(false), 分阶段健康对它是全盲的。
                    if (writeToTun(responsePkt)) {
                        StageCounters.onDnsDelivered()
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    appLogThrottled(
                        "DNS 绕过响应超时 · 服务器 $dstIp — 客户端将按自身重试退避",
                        level = LogLevel.WARNING,
                        throttleKey = "DNS 绕过响应超时",
                    )
                } catch (e: Exception) {
                    appLogThrottled(
                        "DNS 绕过转发失败 · ${e::class.simpleName}: ${e.message}",
                        level = LogLevel.ERROR,
                        throttleKey = "DNS 绕过转发失败",
                    )
                    Log.e("VpnController", "forwardDnsBypassPacket exception", e)
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        /**
         * 连接清理循环 - 定期清理过期的 TCP/UDP 连接
         */
        private suspend fun connectionCleanupLoop() {
            while (isRunning) {
                try {
                    kotlinx.coroutines.delay(CONNECTION_CLEANUP_INTERVAL_MS)
                    if (!isRunning) break
                    packetProcessor.cleanupStaleConnections(STALE_CONNECTION_TIMEOUT_MS)
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e("VpnController", "Connection cleanup error: ${e.message}")
                    }
                }
            }
        }

        /**
         * 获取系统配置的 DNS 服务器 (IPv4 优先), 作为**受保护 socket** 查询的上游。
         *
         * 必须显式跳过 VPN 网络 (2026-10-19 真机定位): 白名单模式 (dnsMode=2) 下 App 自己的流量
         * 也进隧道, `activeNetwork` 就是本 VPN, 而它的 DNS 恒为 [VpnNetwork.TUN_IP]
         * (`SshVpnService.buildVpnBuilder` 的 `addDnsServer(10.0.0.2)`)。受保护 socket 会把查询发到
         * **物理链路**上的 10.0.0.2 —— 那是本机 TUN 地址, 物理网上无人应答 → 3s×2 静默超时。
         * 表现: 「解析真实 IP」永远"解析不到"、MX/TXT 的直查回退同样哑掉 (两者都看不出原因)。
         * 非白名单模式 App 被 `addDisallowedApplication` 排除, 取到的本来就是物理网络 ——
         * 过滤对它是恒等变换, 无行为变化。
         */
        private fun getSystemDnsServers(): List<String> {
            val dnsList = mutableListOf<String>()
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val isVpn = { net: Network ->
                    cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                val isValidated = { net: Network ->
                    cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                }
                // App 实际会用的那条网络优先 (非 VPN 时), 其余物理网络按"已通过系统校验"排前
                val active = cm.activeNetwork?.takeIf { !isVpn(it) }
                val ordered =
                    buildList {
                        active?.let { add(it) }
                        cm.allNetworks
                            .filter { it != active && !isVpn(it) }
                            .sortedByDescending { isValidated(it) }
                            .forEach { add(it) }
                    }
                val (v4, v6) =
                    ordered
                        .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty() }
                        .map { it.hostAddress }
                        .filterNotNull()
                        .filter { host ->
                            !host.startsWith("fe80") && !host.startsWith("::1") && !host.startsWith("127.")
                        }.distinct()
                        .partition { it.contains(':') }
                dnsList.addAll(v4)
                dnsList.addAll(v6)
            } catch (e: Exception) {
                Log.w("VpnController", "获取系统 DNS 失败: ${e.message}")
                appLogThrottled(
                    "获取系统 DNS 失败 — ${e.message} · DNS 绕过路由将缺失",
                    level = LogLevel.WARNING,
                    throttleKey = "获取系统 DNS 失败",
                )
            }
            return dnsList
        }

        fun isVpnRunning(): Boolean = isRunning

        /**
         * 上报端到端健康结果 (SshVpnService 的探测循环驱动)。
         * 相同值重复上报会被 StateFlow 的相等性去重, 无需调用方节流。
         */
        fun reportHealth(
            verified: Boolean,
            failedStep: HealthStep?,
        ) {
            updateState { it.copy(verified = verified, failedStep = failedStep) }
        }

        /**
         * 分阶段健康评估 (与端到端探测同节奏, SshVpnService 驱动): 补探测器探不到的
         * TUN 写回 / DNS 回程 / 本地 SOCKS 转发三段盲区。窗口增量在 [StageHealthEvaluator] 内。
         */
        fun evaluateStageHealth(): HealthStep? =
            stageEvaluator.evaluate(
                StageCounters.snapshot(
                    packetLoopActive = packetLoopJob?.isActive == true,
                    dnsQueries = dnsInterceptor.queriesIntercepted.get(),
                ),
            )

        /** 上报隧道出口 IP (探测成功后经 IP 回显取回); UI 断开时自行降级为占位符。 */
        fun reportExitIp(exitIp: String?) {
            updateState { it.copy(exitIp = exitIp) }
        }

        /** 上报连接流程阶段 (SshVpnService 在 TUN 建立前调用; connect() 内部步骤自行推进 TUNNEL/CONFIG)。 */
        fun reportConnectStage(stage: ConnectStage) {
            updateState { it.copy(status = VpnState.VpnStatus.Connecting, connectStage = stage) }
        }

        private fun updateState(block: (VpnState) -> VpnState) {
            _vpnState.update(block)
        }
    }
