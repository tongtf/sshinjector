package cn.srv0.sshinjector.domain.usecase

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.domain.model.ConnectStage
import cn.srv0.sshinjector.domain.model.ConnectionStats
import cn.srv0.sshinjector.domain.model.HealthStep
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.model.VpnState
import cn.srv0.sshinjector.domain.vpn.AdBlocker
import cn.srv0.sshinjector.domain.vpn.CidrRoute
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.GfwListMatcher
import cn.srv0.sshinjector.domain.vpn.PacketProcessor
import cn.srv0.sshinjector.domain.vpn.StageCounters
import cn.srv0.sshinjector.domain.vpn.StageHealthEvaluator
import cn.srv0.sshinjector.domain.vpn.dnsTransportFor
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import cn.srv0.sshinjector.ui.viewmodel.LogLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 每包级 DEBUG 日志门: 开启后才进应用内日志, 否则只走 logcat (防刷穿 replay 缓存)。 */
private val IS_DEBUG = android.util.Log.isLoggable("VpnController", android.util.Log.DEBUG)

/**
 * VPN 控制器 - 管理 VPN 连接的完整生命周期
 * 协调隧道插件、DNS 拦截、数据包处理
 */
@Singleton
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
    ) : CoroutineScope by CoroutineScope(Dispatchers.IO + Job()) {
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

        // 热点路径只累加原子计数, 由 statsFlushLoop 节流发布到 connectionStats
        private val bytesSentCounter =
            java.util.concurrent.atomic
                .AtomicLong(0)
        private val bytesReceivedCounter =
            java.util.concurrent.atomic
                .AtomicLong(0)
        private val packetsSentCounter =
            java.util.concurrent.atomic
                .AtomicLong(0)
        private val packetsReceivedCounter =
            java.util.concurrent.atomic
                .AtomicLong(0)

        // 用于 SYSTEM 模式 DNS 绕过的 socket 保护函数
        private var protectDatagramChannel: ((java.net.DatagramSocket) -> Boolean)? = null

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
                ""
            }

        fun setProtectTcpFunction(protectSocket: ((java.net.Socket) -> Boolean)?) {
            addLog(">>> [VpnController] setProtectTcpFunction 被调用", LogLevel.DEBUG)
            protectTcpSocket = protectSocket
        }

        val vpnState = MutableStateFlow<VpnState>(VpnState())
        val connectionStats = MutableStateFlow<ConnectionStats>(ConnectionStats())

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

        fun setProtectFunction(protectDatagramChannel: ((java.net.DatagramSocket) -> Boolean)?) {
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
            internal fun isFakeIp(ip: InetAddress): Boolean {
                val bytes = ip.address
                if (bytes.size == 4) {
                    val b0 = bytes[0].toInt() and 0xFF
                    val b1 = bytes[1].toInt() and 0xFF
                    return b0 == 198 && (b1 == 18 || b1 == 19)
                }
                if (bytes.size == 16) {
                    return (bytes[0].toInt() and 0xFF) == 0xFD
                }
                return false
            }

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
                if (dnsModeValue == 2 && whitelistPackages.isEmpty()) {
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
                excludedRoutes = currentServer?.excludedRoutes?.mapNotNull { CidrRoute.parse(it) } ?: emptyList()

                // SYSTEM 模式: 获取 DHCP 分配的 DNS 服务器，添加到绕过列表
                if (transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
                    for (dnsIp in systemDns) {
                        // 将 DNS 服务器 IP 转为 /32 路由加入排除列表
                        try {
                            val addr = InetAddress.getByName(dnsIp)
                            excludedRoutes += CidrRoute(addr, if (addr is java.net.Inet6Address) 128 else 32)
                        } catch (e: Exception) {
                            addLog("解析 DNS IP 失败: $dnsIp", LogLevel.WARNING)
                        }
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
                            try {
                                val addr = InetAddress.getByName(dnsIp)
                                excludedRoutes += CidrRoute(addr, if (addr is java.net.Inet6Address) 128 else 32)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                if (excludedRoutes.isNotEmpty()) {
                    addLog("排除路由: ${excludedRoutes.size} 条规则", LogLevel.INFO)
                }

                // 5. 注册 TUN 写回回调
                packetProcessor.setTunWriter { data -> writeToTun(data) }
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
                    stats =
                        it.stats.copy(
                            lastUpdate = java.util.Date(),
                        ),
                )
            }
            addLog("VPN 连接已完全断开", LogLevel.INFO)
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

            // 所有模式都不排除 DNS 服务器，让 DNS 流量走 VPN 隧道
            val commonDohEndpoints = emptyList<CidrRoute>()
            val dnsExcludes = emptyList<CidrRoute>()

            val baseRoutes = currentServer?.excludedRoutes?.mapNotNull { CidrRoute.parse(it) } ?: emptyList()
            excludedRoutes = baseRoutes + commonDohEndpoints + dnsExcludes
            addLog(
                "DNS 模式已切换: $transportMode, 排除路由: ${excludedRoutes.size} 条",
                LogLevel.INFO,
            )
        }

        /**
         * 强制重置状态 (超时后调用)
         *
         * 必须与 [disconnect] 的清理段保持一致 —— 少了下面两项就会跨会话泄漏:
         * ① `clearPendingResponses()` 不做, 未排空的 DNS 应答会在下一次 connect 重开的
         *    dnsResponseDeliveryLoop 里被投给**新会话**的 TUN;
         * ② `resetTcpState()` 不做, 旧连接表原样留到新会话 (新 SYN 虽会关旧重建,
         *    但关闭包/黑洞统计会算在新会话头上)。
         * 调用点: 断开卡在 Disconnecting 超 3s 后 MainViewModel 直接 stopService + forceReset。
         */
        fun forceReset() {
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
                outputStream?.close()
            } catch (_: Exception) {
            }
            vpnInterface = fd
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
            packetLoopJob?.cancel()
            packetLoopJob = launch { packetLoop(generation) }
            addLog("TUN 接口已重建", LogLevel.INFO)
        }

        /**
         * 数据包处理主循环
         * @param generation 接口代次, 用于检测接口重建后旧循环退出
         */
        private fun packetLoop(generation: Long) {
            android.util.Log.d("VpnController", "packetLoop started gen=$generation")
            while (isRunning && inputStream != null && generation == tunGeneration) {
                try {
                    val bytesRead = inputStream!!.read(readBuffer.array())
                    // EOF 不忙循环: 转异常走 catch 的连续失败计数 + backoff
                    if (bytesRead < 0) throw IOException("TUN read EOF")
                    // 空读不 continue — detekt LoopWithTooManyJumpStatements: loop 内只留 catch 的 break
                    if (bytesRead > 0) {
                        consecutiveReadFailures = 0
                        bytesReceivedCounter.addAndGet(bytesRead.toLong())
                        packetsReceivedCounter.incrementAndGet()

                        readBuffer.limit(bytesRead)
                        readBuffer.position(0)

                        processPacket(readBuffer)

                        readBuffer.clear()
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        consecutiveReadFailures++
                        android.util.Log.e("VpnController", "packetLoop error ($consecutiveReadFailures): ${e.message}")
                        if (consecutiveReadFailures >= 20) {
                            // F12-i 兜底: 连续失败说明 TUN 已死 (如断开竞态), 停止忙循环并上报 Failed,
                            // 由 SshVpnService 观察到后执行完整清理 (保留 lastServerId 供重连)。
                            // 不在此置 isRunning=false — 否则 disconnect 的资源清理链在
                            // isVpnRunning() 检查处短路, SSH tunnel 会泄漏
                            android.util.Log.e("VpnController", "packetLoop failing continuously, stopping")
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
            android.util.Log.d("VpnController", "packetLoop ended")
        }

        private fun processPacket(buffer: ByteBuffer) {
            try {
                // 解析 IP 版本
                val firstByte = buffer.get(buffer.position()).toInt() and 0xFF
                val version = firstByte shr 4
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    android.util.Log.d(
                        TAG,
                        "processPacket: firstByte=0x${"%02x".format(firstByte)} " +
                            "version=$version remaining=${buffer.remaining()}",
                    )

                    // Debug: dump first 16 bytes
                    val debugBytes = ByteArray(16)
                    val origPos = buffer.position()
                    buffer.get(debugBytes)
                    buffer.position(origPos)
                    android.util.Log.d(
                        TAG,
                        "raw bytes: ${debugBytes.joinToString("") { "%02x".format(it) }}",
                    )
                }

                val fd = vpnInterface
                if (fd == null) {
                    android.util.Log.w("VpnController", "VPN interface is null, skipping packet")
                    return
                }

                // 某些 Android 设备返回的包带前缀 (tun_pi flags+proto 或 PacketInfo)，版本字段为 0
                // 逐字节扫描寻找有效 IP 版本 (4 或 6)
                var workBuffer = buffer
                var workVersion = version
                if (version == 0 && buffer.remaining() >= 5) {
                    val savedPos = buffer.position()
                    val maxSkip = minOf(buffer.remaining() - 1, 8) // 最多跳 8 字节
                    var found = false
                    for (skip in 1..maxSkip) {
                        val probeByte = buffer.get(savedPos + skip).toInt() and 0xFF
                        val probeVersion = probeByte shr 4
                        if (probeVersion == 4 || probeVersion == 6) {
                            buffer.position(savedPos + skip)
                            workVersion = probeVersion
                            workBuffer = buffer.slice()
                            workBuffer.position(0)
                            workBuffer.limit(buffer.remaining())
                            android.util.Log.d("VpnController", "skipped $skip bytes prefix, version=$probeVersion")
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        val dump = ByteArray(minOf(16, buffer.remaining())) { buffer.get(savedPos + it) }
                        android.util.Log.d(
                            "VpnController",
                            "unrecognized packet prefix, first bytes: ${dump.joinToString("") { "%02x".format(it) }}",
                        )
                        buffer.position(savedPos)
                    }
                }

                // 排除路由/域名分流: 不再 writeToTun 回注 —— 包已进 TUN, 用户态无法塞回物理网卡,
                // 回注 = ip_forward 黑洞或 0/0 路由读写死循环 (F1)。
                // 仅 SYSTEM 模式 DNS(UDP:53) 走 protected socket 直接转发 (对 UDP 有效, 非回注);
                // TCP 落入 PacketProcessor → forwardSynToTunnel → shouldBypassTcp → 用户态直连;
                // UDP 非 53 由 UdpRelay 丢弃计数。
                val needDstIp = excludedRoutes.isNotEmpty()
                val dstIp = if (needDstIp) extractDstIp(workBuffer, workVersion) else null
                val isSystemDnsUdp53 =
                    transportMode == DnsInterceptor.DnsTransport.SYSTEM &&
                        extractProtocol(workBuffer, workVersion) == IPPROTO_UDP &&
                        extractDstPort(workBuffer, workVersion) == DNS_PORT
                if (dstIp != null &&
                    shouldBypassVpn(dstIp) &&
                    isSystemDnsUdp53
                ) {
                    forwardDnsBypassPacket(readBuffer, dstIp, workVersion)
                    return
                }

                when (workVersion) {
                    4 -> {
                        // F12-e: false = 未处理 → 丢弃计数已在 PacketProcessor 内完成;
                        // 不 writeToTun 回注 —— 包已进 TUN, 回注是黑洞或读写死循环 (F1 同类)
                        packetProcessor.processIpv4Packet(workBuffer)
                    }
                    6 -> {
                        packetProcessor.processIpv6Packet(workBuffer)
                    }
                    else -> {
                        addLog(
                            ">>> [VpnController] 未知 IP 版本: $workVersion，丢弃",
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                        )
                        // 未知版本，丢弃
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VpnController", "processPacket error: ${e.message}")
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

        private fun shouldBypassVpn(dstIp: InetAddress): Boolean {
            val result = excludedRoutes.any { CidrRoute.matches(dstIp, it) }
            if (result) {
                android.util.Log.d(
                    "VpnController",
                    "shouldBypassVpn: TRUE for $dstIp (excludedRoutes=${excludedRoutes.size})",
                )
            }
            return result
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
                writeToTun(packet)
            } catch (e: Exception) {
                android.util.Log.e("VpnController", "writeDnsResponse failed: ${e.message}")
            }
        }

        /**
         * 独立 DNS 响应投递协程
         * 修复 REMOTE 模式下 DNS 死锁：DNS 响应不再依赖 processPacket 轮询，
         * 而是由独立协程持续投递到 TUN。
         */
        private suspend fun dnsResponseDeliveryLoop() {
            while (isRunning) {
                val dnsResponse = dnsInterceptor.pollResponse()
                if (dnsResponse != null) {
                    writeDnsResponse(dnsResponse)
                } else {
                    delay(10)
                }
            }
        }

        @Synchronized
        fun writeToTun(data: ByteArray) {
            try {
                outputStream?.write(data)
            } catch (e: Exception) {
                android.util.Log.e("VpnController", "writeToTun FAILED: ${e.message}")
            }
            bytesSentCounter.addAndGet(data.size.toLong())
            packetsSentCounter.incrementAndGet()
        }

        /**
         * 节流发布连接统计: 热点路径只累加原子计数, 每 100ms 汇总一次到 StateFlow,
         * 避免每包触发 MutableStateFlow 发射与 collector 唤醒 (SshVpnService + MainViewModel)。
         */
        private suspend fun statsFlushLoop() {
            while (isRunning) {
                delay(STATS_FLUSH_INTERVAL_MS)
                connectionStats.update {
                    it.copy(
                        bytesSent = bytesSentCounter.get(),
                        bytesReceived = bytesReceivedCounter.get(),
                        packetsSent = packetsSentCounter.get(),
                        packetsReceived = packetsReceivedCounter.get(),
                        lastUpdate = java.util.Date(),
                    )
                }
            }
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
                var socket: java.net.DatagramSocket? = null
                try {
                    addLog(
                        ">>> [VpnController] forwardDnsBypassPacket: dstIp=$dstIp, version=$version, " +
                            "packetSize=${packetData.size}",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    socket = java.net.DatagramSocket()
                    val protected = protectDatagramChannel?.invoke(socket!!) ?: false
                    addLog(
                        ">>> [VpnController] VpnService.protect()=$protected",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    if (!protected) {
                        addLog("SYSTEM DNS: VpnService.protect() 失败", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
                    }
                    socket!!.soTimeout = SOCKET_TIMEOUT_MS

                    // 提取 IP 载荷 (UDP 数据) - 从复制的数据中解析
                    val ipHeaderLen =
                        if (version == 4) {
                            val ihl = ((packetData[0].toInt() and 0x0F) * 4)
                            ihl
                        } else {
                            40 // IPv6 固定头部
                        }
                    val payloadStart = ipHeaderLen
                    val payloadLen = packetData.size - ipHeaderLen
                    if (payloadLen <= 0) {
                        addLog(
                            ">>> [VpnController] payloadLen <= 0, 返回",
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING,
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
                    addLog(
                        ">>> [VpnController] 发送 DNS 查询到 $dnsServer:53, payload=${payload.size} bytes",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    socket!!.send(packet)
                    addLog(">>> [VpnController] 已发送，等待响应...", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                    // 接收响应
                    val responseBuf = ByteArray(512)
                    val responsePacket = java.net.DatagramPacket(responseBuf, responseBuf.size)
                    socket!!.receive(responsePacket)
                    val responseData = responseBuf.copyOfRange(0, responsePacket.length)
                    addLog(
                        "<<< [VpnController] 收到 DNS 响应来自 ${responsePacket.address}:" +
                            "${responsePacket.port} (${responseData.size} bytes)",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )

                    // 从复制的原始包提取源 IP 和源端口
                    var srcIp: InetAddress
                    var srcPort: Int
                    try {
                        if (version == 4) {
                            val srcIpBytes = packetData.copyOfRange(12, 16)
                            srcIp = InetAddress.getByAddress(srcIpBytes)
                            srcPort = ((packetData[20].toInt() and 0xFF) shl 8) or (packetData[21].toInt() and 0xFF)
                        } else {
                            val srcIpBytes = packetData.copyOfRange(8, 24)
                            srcIp = InetAddress.getByAddress(srcIpBytes)
                            srcPort = ((packetData[40].toInt() and 0xFF) shl 8) or (packetData[41].toInt() and 0xFF)
                        }
                        addLog(
                            ">>> [VpnController] 解析原始包: srcIp=$srcIp, srcPort=$srcPort",
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                        )
                    } catch (e: Exception) {
                        addLog(
                            ">>> [VpnController] 解析源 IP/端口失败: ${e.message}",
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.ERROR,
                        )
                        srcIp = InetAddress.getByName("10.0.0.1")
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
                    addLog(
                        ">>> [VpnController] 构造响应包完成: srcPort=53, dstPort=$srcPort, packetSize=${responsePkt.size}",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    writeToTun(responsePkt)
                    addLog(">>> [VpnController] 已写回 TUN", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)
                } catch (e: java.net.SocketTimeoutException) {
                    addLog(
                        ">>> [VpnController] DNS 响应超时 (SocketTimeoutException)",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING,
                    )
                } catch (e: Exception) {
                    addLog(
                        ">>> [VpnController] forwardDnsBypassPacket exception: ${e.message}",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.ERROR,
                    )
                    android.util.Log.e("VpnController", "forwardDnsBypassPacket exception", e)
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
                        android.util.Log.e("VpnController", "Connection cleanup error: ${e.message}")
                    }
                }
            }
        }

        /**
         * 获取系统配置的 DNS 服务器 (IPv4 优先)
         */
        private fun getSystemDnsServers(): List<String> {
            val dnsList = mutableListOf<String>()
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return dnsList
                val linkProperties = cm.getLinkProperties(network) ?: return dnsList

                val (v4, v6) =
                    linkProperties.dnsServers
                        .map { it.hostAddress }
                        .filterNotNull()
                        .filter { host ->
                            !host.startsWith("fe80") && !host.startsWith("::1") && !host.startsWith("127.")
                        }.partition { it.contains(':') }
                dnsList.addAll(v4)
                dnsList.addAll(v6)
            } catch (e: Exception) {
                android.util.Log.w("VpnController", "获取系统 DNS 失败: ${e.message}")
            }
            return dnsList
        }

        fun getCurrentServer(): ServerConfig? = currentServer

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
            vpnState.update(block)
        }
    }
