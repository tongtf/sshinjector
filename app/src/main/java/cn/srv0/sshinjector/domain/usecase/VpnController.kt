package cn.srv0.sshinjector.domain.usecase

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import cn.srv0.sshinjector.data.local.DomainListManager
import cn.srv0.sshinjector.domain.model.ConnectionStats
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.model.VpnState
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.PacketProcessor
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
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
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

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
        private val settingsDataStore: cn.srv0.sshinjector.data.local.preferences.SettingsDataStore,
        private val domainListManager: DomainListManager,
        @ApplicationContext private val context: Context,
    ) : CoroutineScope by CoroutineScope(Dispatchers.IO + Job()) {
        private var vpnInterface: FileDescriptor? = null
        private var inputStream: FileInputStream? = null
        private var outputStream: FileOutputStream? = null
        private var packetLoopJob: Job? = null
        private var tunGeneration = 0L
        private val readBuffer = ByteBuffer.allocate(32768).order(ByteOrder.BIG_ENDIAN)

        // 热点路径只累加原子计数, 由 statsFlushLoop 节流发布到 connectionStats
        private val bytesSentCounter = java.util.concurrent.atomic.AtomicLong(0)
        private val bytesReceivedCounter = java.util.concurrent.atomic.AtomicLong(0)
        private val packetsSentCounter = java.util.concurrent.atomic.AtomicLong(0)
        private val packetsReceivedCounter = java.util.concurrent.atomic.AtomicLong(0)

        // 用于 SYSTEM 模式 DNS 绕过的 socket 保护函数
        private var protectDatagramChannel: ((java.net.DatagramSocket) -> Boolean)? = null

        val vpnState = MutableStateFlow<VpnState>(VpnState())
        val connectionStats = MutableStateFlow<ConnectionStats>(ConnectionStats())

        private val _logFlow =
            MutableSharedFlow<Pair<String, cn.srv0.sshinjector.ui.viewmodel.LogLevel>>(extraBufferCapacity = 10)
        val logFlow: SharedFlow<Pair<String, cn.srv0.sshinjector.ui.viewmodel.LogLevel>> = _logFlow.asSharedFlow()

        private var currentServer: ServerConfig? = null
        private var isRunning = false
        private var excludedRoutes: List<CidrRoute> = emptyList()
        private var transportMode: DnsInterceptor.DnsTransport = DnsInterceptor.DnsTransport.REMOTE

        // 用于 SYSTEM 模式 DNS 转发的线程池
        private val executor = Executors.newCachedThreadPool()

        fun setProtectFunction(protectDatagramChannel: (java.net.DatagramSocket) -> Boolean) {
            addLog(">>> [VpnController] setProtectFunction 被调用", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)
            this.protectDatagramChannel = protectDatagramChannel
        }

        fun addLog(
            message: String,
            level: cn.srv0.sshinjector.ui.viewmodel.LogLevel,
        ) {
            _logFlow.tryEmit(message to level)
        }

        private data class CidrRoute(
            val network: InetAddress,
            val prefixLength: Int,
        )

        companion object {
            private const val TAG = "VpnController"
            private const val IPPROTO_TCP = 6
            private const val IPPROTO_UDP = 17
            private const val DNS_PORT = 53
            private const val SOCKET_TIMEOUT_MS = 5000
            private const val CONNECTION_CLEANUP_INTERVAL_MS = 60000L
            private const val STALE_CONNECTION_TIMEOUT_MS = 300000L
            private const val STATS_FLUSH_INTERVAL_MS = 100L

            private fun parseCidr(cidr: String): CidrRoute? {
                try {
                    val parts = cidr.split("/")
                    if (parts.size != 2) return null
                    val ip = InetAddress.getByName(parts[0])
                    val prefix = parts[1].toIntOrNull() ?: return null
                    return CidrRoute(ip, prefix)
                } catch (_: Exception) {
                    return null
                }
            }

            private fun ipMatchesCidr(
                ip: InetAddress,
                route: CidrRoute,
            ): Boolean {
                val ipBytes = ip.address
                val netBytes = route.network.address
                if (ipBytes.size != netBytes.size) return false

                val prefixLen = route.prefixLength
                val fullBytes = prefixLen / 8
                val remainingBits = prefixLen % 8

                for (i in 0 until fullBytes) {
                    if (ipBytes[i] != netBytes[i]) return false
                }
                if (remainingBits > 0) {
                    val mask = (0xFF shl (8 - remainingBits))
                    if ((ipBytes[fullBytes].toInt() and mask) != (netBytes[fullBytes].toInt() and mask)) {
                        return false
                    }
                }
                return true
            }
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
                addLog("VPN 已在运行中", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
                return Result.failure(IllegalStateException("VPN already running"))
            }

            currentServer = server
            isRunning = true
            updateState { it.copy(status = VpnState.VpnStatus.Connecting, server = server) }

            return try {
                // 1. 启动隧道插件
                val tunnelConfig = buildTunnelConfig(server, password)
                addLog("正在连接隧道 (socks5)...", cn.srv0.sshinjector.ui.viewmodel.LogLevel.INFO)
                val tunnelResult = tunnelManager.startPlugin("socks5", tunnelConfig)
                if (tunnelResult.isFailure) {
                    val errorMsg = tunnelResult.exceptionOrNull()?.message ?: "Tunnel connection failed"
                    addLog("隧道连接失败: $errorMsg", cn.srv0.sshinjector.ui.viewmodel.LogLevel.ERROR)
                    throw Exception(errorMsg)
                }
                addLog("隧道连接成功: socks5", cn.srv0.sshinjector.ui.viewmodel.LogLevel.SUCCESS)

                // 3. 设置 DNS 拦截器
                packetProcessor.setDnsInterceptor(dnsInterceptor)
                val dnsModeValue = settingsDataStore.dnsMode.first()
                this.transportMode =
                    when (dnsModeValue) {
                        0 -> DnsInterceptor.DnsTransport.REMOTE // 全部走隧道
                        1 -> DnsInterceptor.DnsTransport.SYSTEM // 系统默认，完全透传
                        2 -> DnsInterceptor.DnsTransport.WHITELIST // 白名单分流
                        3 -> DnsInterceptor.DnsTransport.DOMAIN_SPLIT // 域名分流
                        else -> DnsInterceptor.DnsTransport.REMOTE
                    }
                dnsInterceptor.setTransportMode(this.transportMode)
                dnsInterceptor.setDomainListManager(domainListManager)

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
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                        )
                        if (needRefresh) {
                            addLog("正在更新域名列表...", cn.srv0.sshinjector.ui.viewmodel.LogLevel.INFO)
                            domainListManager.update()
                        }
                    }
                }

                addLog("DNS 拦截器已配置 (模式: $transportMode)", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                // 4. 解析排除路由 (CIDR)
                excludedRoutes = currentServer?.excludedRoutes?.mapNotNull { parseCidr(it) } ?: emptyList()

                // SYSTEM 模式: 获取 DHCP 分配的 DNS 服务器，添加到绕过列表
                if (transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
                    for (dnsIp in systemDns) {
                        // 将 DNS 服务器 IP 转为 /32 路由加入排除列表
                        try {
                            val addr = InetAddress.getByName(dnsIp)
                            excludedRoutes += CidrRoute(addr, if (addr is java.net.Inet6Address) 128 else 32)
                        } catch (e: Exception) {
                            addLog("解析 DNS IP 失败: $dnsIp", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
                        }
                    }
                    if (systemDns.isNotEmpty()) {
                        addLog(
                            "SYSTEM 模式: 绕过 VPN 的 DNS 服务器: ${systemDns.joinToString(", ")}",
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.INFO,
                        )
                    } else {
                        addLog("SYSTEM 模式: 未获取到系统 DNS，使用默认 8.8.8.8", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
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
                    addLog("排除路由: ${excludedRoutes.size} 条规则", cn.srv0.sshinjector.ui.viewmodel.LogLevel.INFO)
                }

                // 5. 注册 TUN 写回回调
                packetProcessor.setTunWriter { data -> writeToTun(data) }
                addLog("TUN 写回通道已就绪", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                // 6. 启动连接清理定时任务
                launch { connectionCleanupLoop() }
                addLog("连接清理任务已启动", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                // 6.1 启动独立 DNS 响应投递协程 (修复 DNS 死锁)
                launch { dnsResponseDeliveryLoop() }
                addLog("DNS 响应投递协程已启动", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                // 6.2 启动统计节流发布协程
                launch { statsFlushLoop() }
                addLog("统计发布协程已启动", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                updateState {
                    it.copy(
                        status = VpnState.VpnStatus.Connected,
                        stats = it.stats.copy(startTime = java.util.Date()),
                    )
                }

                addLog("VPN 连接已建立，开始处理数据包", cn.srv0.sshinjector.ui.viewmodel.LogLevel.SUCCESS)

                // 7. 启动数据包处理循环
                packetLoopJob?.cancel()
                packetLoopJob = launch { packetLoop(++tunGeneration) }

                Result.success(Unit)
            } catch (e: Exception) {
                addLog("连接失败: ${e.message}", cn.srv0.sshinjector.ui.viewmodel.LogLevel.ERROR)
                disconnect()
                Result.failure(e)
            }
        }

        /**
         * 断开 VPN 连接
         */
        suspend fun disconnect() {
            if (!isRunning) return

            addLog("正在断开 VPN 连接...", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
            isRunning = false
            updateState { it.copy(status = VpnState.VpnStatus.Disconnecting) }

            // 取消所有子协程
            coroutineContext.cancelChildren()
            packetLoopJob?.cancel()
            packetLoopJob = null
            addLog("已取消所有子任务", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

            // 断开 SSH 连接
            // 停止所有隧道插件
            try {
                addLog("正在断开隧道连接...", cn.srv0.sshinjector.ui.viewmodel.LogLevel.INFO)
                tunnelManager.stopAll()
                addLog("隧道连接已断开", cn.srv0.sshinjector.ui.viewmodel.LogLevel.SUCCESS)
            } catch (e: Exception) {
                addLog("隧道断开错误: ${e.message}", cn.srv0.sshinjector.ui.viewmodel.LogLevel.ERROR)
            }

            // 关闭输入输出流
            try {
                inputStream?.close()
                outputStream?.close()
                addLog("TUN 数据流已关闭", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)
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
            addLog("VPN 连接已完全断开", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
        }

        /**
         * 实时更新 DNS 传输模式
         */
        suspend fun updateDnsMode() {
            if (!isRunning) return
            val dnsModeValue = settingsDataStore.dnsMode.first()
            this.transportMode =
                when (dnsModeValue) {
                    0 -> DnsInterceptor.DnsTransport.REMOTE // 全部走隧道
                    1 -> DnsInterceptor.DnsTransport.SYSTEM // 系统默认，完全透传
                    2 -> DnsInterceptor.DnsTransport.WHITELIST // 白名单分流
                    3 -> DnsInterceptor.DnsTransport.DOMAIN_SPLIT // 域名分流
                    else -> DnsInterceptor.DnsTransport.REMOTE
                }
            dnsInterceptor.setTransportMode(this.transportMode)
            dnsInterceptor.setDomainListManager(domainListManager)

            // SYSTEM/DOMAIN_SPLIT 模式: 设置/更新 socket 保护函数，用于 DNS 查询绕过 VPN
            if (this.transportMode == DnsInterceptor.DnsTransport.SYSTEM ||
                this.transportMode == DnsInterceptor.DnsTransport.DOMAIN_SPLIT
            ) {
                addLog(
                    ">>> [VpnController] updateDnsMode: 设置 DNS Interceptor 保护函数 ($transportMode)",
                    cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                )
                dnsInterceptor.setProtectFunction { socket ->
                    addLog(
                        ">>> [VpnController] 保护函数被调用: socket=$socket",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    val result = protectDatagramChannel?.invoke(socket) ?: false
                    addLog(">>> [VpnController] 保护函数返回: $result", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)
                    result
                }
            }

            // 所有模式都不排除 DNS 服务器，让 DNS 流量走 VPN 隧道
            val commonDohEndpoints = emptyList<CidrRoute>()
            val dnsExcludes = emptyList<CidrRoute>()

            val baseRoutes = currentServer?.excludedRoutes?.mapNotNull { parseCidr(it) } ?: emptyList()
            excludedRoutes = baseRoutes + commonDohEndpoints + dnsExcludes
            addLog(
                "DNS 模式已切换: $transportMode, 排除路由: ${excludedRoutes.size} 条",
                cn.srv0.sshinjector.ui.viewmodel.LogLevel.INFO,
            )
        }

        /**
         * 强制重置状态 (超时后调用)
         */
        fun forceReset() {
            isRunning = false
            try {
                coroutineContext.cancelChildren()
            } catch (_: Exception) {
            }
            packetLoopJob?.cancel()
            packetLoopJob = null
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
            if (!isRunning) return
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
            addLog("TUN 接口已重建", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)
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
                    if (bytesRead <= 0) continue

                    bytesReceivedCounter.addAndGet(bytesRead.toLong())
                    packetsReceivedCounter.incrementAndGet()

                    readBuffer.limit(bytesRead)
                    readBuffer.position(0)

                    processPacket(readBuffer)

                    readBuffer.clear()
                } catch (e: Exception) {
                    if (isRunning) {
                        android.util.Log.e("VpnController", "packetLoop error: ${e.message}")
                        updateState { it.copy(error = e.message) }
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

                // 提取目标 IP 以检查排除路由 (无排除路由且非域名分流时短路, 避免每包分配)
                val needDstIp =
                    excludedRoutes.isNotEmpty() ||
                        transportMode == DnsInterceptor.DnsTransport.DOMAIN_SPLIT
                val dstIp = if (needDstIp) extractDstIp(workBuffer, workVersion) else null
                if (dstIp != null && shouldBypassVpn(dstIp)) {
                    if (transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
                        forwardDnsBypassPacket(readBuffer, dstIp, workVersion)
                    } else {
                        writeToTun(readBuffer.array().copyOfRange(0, readBuffer.limit()))
                    }
                    return
                }

                // 域名分流: 命中列表域名拿到假 IP(198.18.x.x / fd00::x)走隧道,
                // 未命中域名拿到真实 IP, 该 TCP/非 53 UDP 直连透传回 TUN。
                // DNS(UDP:53) 与 ICMP 放行给 DnsInterceptor/系统处理, 保证 DNS 拦截与 IPv6 ND 正常。
                if (transportMode == DnsInterceptor.DnsTransport.DOMAIN_SPLIT && dstIp != null && !isFakeIp(dstIp)) {
                    val proto = extractProtocol(workBuffer, workVersion)
                    val dstPort = extractDstPort(workBuffer, workVersion)
                    val direct =
                        when (proto) {
                            IPPROTO_TCP -> true
                            IPPROTO_UDP -> dstPort != DNS_PORT
                            else -> false
                        }
                    if (direct) {
                        val data = ByteArray(workBuffer.remaining())
                        workBuffer.duplicate().get(data)
                        addLog(
                            "域名分流直连: proto=$proto dst=${dstIp.hostAddress}:$dstPort",
                            cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                        )
                        writeToTun(data)
                        return
                    }
                }

                when (workVersion) {
                    4 -> {
                        val processed = packetProcessor.processIpv4Packet(workBuffer)
                        if (!processed) {
                            writeToTun(readBuffer.array().copyOfRange(0, readBuffer.limit()))
                        }
                    }
                    6 -> {
                        val processed = packetProcessor.processIpv6Packet(workBuffer)
                        if (!processed) {
                            writeToTun(readBuffer.array().copyOfRange(0, readBuffer.limit()))
                        }
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
        ): InetAddress? {
            return try {
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
        }

        private fun extractProtocol(
            buffer: ByteBuffer,
            version: Int,
        ): Int {
            return try {
                buffer.mark()
                val protoOffset = if (version == 4) 9 else 6
                val proto = buffer.get(buffer.position() + protoOffset).toInt() and 0xFF
                buffer.reset()
                proto
            } catch (_: Exception) {
                -1
            }
        }

        private fun extractDstPort(
            buffer: ByteBuffer,
            version: Int,
        ): Int {
            return try {
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
        }

        /**
         * 判定 IP 是否为 DnsInterceptor 分配的假 IP (198.18.0.0/15, fd00::/8)。
         */
        private fun isFakeIp(ip: InetAddress): Boolean {
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

        private fun shouldBypassVpn(dstIp: InetAddress): Boolean {
            val result = excludedRoutes.any { ipMatchesCidr(dstIp, it) }
            if (result) {
                android.util.Log.d(
                    "VpnController",
                    "shouldBypassVpn: TRUE for $dstIp (excludedRoutes=${excludedRoutes.size})",
                )
            }
            return result
        }

        private fun writeDnsResponse(response: DnsInterceptor.DnsResponse) {
            try {
                val vpnIp = InetAddress.getByName("10.0.0.2")
                val packet =
                    packetProcessor.buildUdpResponsePacket(
                        srcIp = vpnIp.address,
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
                try {
                    addLog(
                        ">>> [VpnController] forwardDnsBypassPacket: dstIp=$dstIp, version=$version, " +
                            "packetSize=${packetData.size}",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    val socket = java.net.DatagramSocket()
                    val protected = protectDatagramChannel?.invoke(socket) ?: false
                    addLog(
                        ">>> [VpnController] VpnService.protect()=$protected",
                        cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG,
                    )
                    if (!protected) {
                        addLog("SYSTEM DNS: VpnService.protect() 失败", cn.srv0.sshinjector.ui.viewmodel.LogLevel.WARNING)
                    }
                    socket.soTimeout = SOCKET_TIMEOUT_MS

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
                    socket.send(packet)
                    addLog(">>> [VpnController] 已发送，等待响应...", cn.srv0.sshinjector.ui.viewmodel.LogLevel.DEBUG)

                    // 接收响应
                    val responseBuf = ByteArray(512)
                    val responsePacket = java.net.DatagramPacket(responseBuf, responseBuf.size)
                    socket.receive(responsePacket)
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

                    val vpnIp = InetAddress.getByName("10.0.0.2")
                    val responsePkt =
                        packetProcessor.buildUdpResponsePacket(
                            srcIp = vpnIp.address,
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
                    socket.close()
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
                        }
                        .partition { it.contains(':') }
                dnsList.addAll(v4)
                dnsList.addAll(v6)
            } catch (e: Exception) {
                android.util.Log.w("VpnController", "获取系统 DNS 失败: ${e.message}")
            }
            return dnsList
        }

        private fun buildTunnelConfig(
            server: ServerConfig,
            password: String?,
        ): TunnelConfig {
            return TunnelConfig.Socks5(
                sshHost = server.host,
                sshPort = server.port,
                sshUsername = server.username,
                sshKeyAlias = server.keyAlias,
                sshPassword = password ?: server.password,
                sshKeyAlgorithm = server.keyAlgorithm.name,
                common =
                    TunnelConfig.CommonConfig(
                        connectTimeout = server.connectTimeout,
                        keepAliveInterval = server.keepAliveInterval,
                    ),
                socksPort = server.socksPort,
            )
        }

        fun getCurrentServer(): ServerConfig? = currentServer

        fun isVpnRunning(): Boolean = isRunning

        private fun updateState(block: (VpnState) -> VpnState) {
            vpnState.update(block)
        }
    }
