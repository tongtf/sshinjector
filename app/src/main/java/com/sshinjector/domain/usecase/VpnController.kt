package com.sshinjector.domain.usecase

import com.sshinjector.domain.model.ServerConfig
import com.sshinjector.domain.model.ConnectionStats
import com.sshinjector.domain.model.VpnState
import com.sshinjector.domain.vpn.DnsInterceptor
import com.sshinjector.domain.vpn.PacketProcessor
import com.sshinjector.domain.vpn.tunnel.TunnelConfig
import com.sshinjector.domain.vpn.tunnel.TunnelManager
import com.sshinjector.domain.vpn.tunnel.TunnelRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
class VpnController @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val tunnelRouter: TunnelRouter,
    private val packetProcessor: PacketProcessor,
    private val dnsInterceptor: DnsInterceptor,
    private val serverRepository: ServerRepository,
    private val settingsDataStore: com.sshinjector.data.local.preferences.SettingsDataStore,
    @ApplicationContext private val context: Context
) : CoroutineScope by CoroutineScope(Dispatchers.IO + Job()) {
    
    private var vpnInterface: FileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private val readBuffer = ByteBuffer.allocate(32768).order(ByteOrder.BIG_ENDIAN)
    
    // 用于 SYSTEM 模式 DNS 绕过的 socket 保护函数
    private var protectDatagramChannel: ((java.net.DatagramSocket) -> Boolean)? = null
    
    val vpnState = MutableStateFlow<VpnState>(VpnState())
    val connectionStats = MutableStateFlow<ConnectionStats>(ConnectionStats())
    
    private val _logFlow = MutableSharedFlow<Pair<String, com.sshinjector.ui.viewmodel.MainViewModel.LogLevel>>(extraBufferCapacity = 10)
    val logFlow: SharedFlow<Pair<String, com.sshinjector.ui.viewmodel.MainViewModel.LogLevel>> = _logFlow.asSharedFlow()
    
    private var currentServer: ServerConfig? = null
    private var isRunning = false
    private var excludedRoutes: List<CidrRoute> = emptyList()
    private var transportMode: DnsInterceptor.DnsTransport = DnsInterceptor.DnsTransport.REMOTE
    
    // 用于 SYSTEM 模式 DNS 转发的线程池
    private val executor = Executors.newCachedThreadPool()

    fun setProtectFunction(protectDatagramChannel: (java.net.DatagramSocket) -> Boolean) {
        addLog(">>> [VpnController] setProtectFunction 被调用", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
        this.protectDatagramChannel = protectDatagramChannel
    }

    fun addLog(message: String, level: com.sshinjector.ui.viewmodel.MainViewModel.LogLevel) {
        _logFlow.tryEmit(message to level)
    }

    private data class CidrRoute(
        val network: InetAddress,
        val prefixLength: Int
    )

    companion object {
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

        private fun ipMatchesCidr(ip: InetAddress, route: CidrRoute): Boolean {
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
    suspend fun connect(server: ServerConfig, password: String? = null): Result<Unit> {
        if (isRunning) {
            addLog("VPN 已在运行中", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
            return Result.failure(IllegalStateException("VPN already running"))
        }

        currentServer = server
        isRunning = true
        updateState { it.copy(status = VpnState.VpnStatus.Connecting, server = server) }

        return try {
            // 1. 启动隧道插件
            val tunnelConfig = buildTunnelConfig(server, password)
            addLog("正在连接隧道 (${server.tunnelType})...", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            val tunnelResult = tunnelManager.startPlugin(server.tunnelType, tunnelConfig)
            if (tunnelResult.isFailure) {
                val errorMsg = tunnelResult.exceptionOrNull()?.message ?: "Tunnel connection failed"
                addLog("隧道连接失败: $errorMsg", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
                throw Exception(errorMsg)
            }
            addLog("隧道连接成功: ${server.tunnelType}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.SUCCESS)

            // 2.5 加载路由配置
            loadRouteConfig()

            // 3. 设置 DNS 拦截器
            packetProcessor.setDnsInterceptor(dnsInterceptor)
            val dnsModeValue = settingsDataStore.dnsMode.first()
            this.transportMode = when (dnsModeValue) {
                0 -> DnsInterceptor.DnsTransport.REMOTE      // 全部走隧道
                1 -> DnsInterceptor.DnsTransport.SYSTEM      // 系统默认，完全透传
                2 -> DnsInterceptor.DnsTransport.WHITELIST   // 白名单分流
                else -> DnsInterceptor.DnsTransport.REMOTE
            }
            dnsInterceptor.setTransportMode(this.transportMode)

            // 传递系统 DNS 服务器到 DnsInterceptor (SYSTEM 模式需要)
            val systemDns = getSystemDnsServers()
            dnsInterceptor.setSystemDnsServers(systemDns)
            
            // SYSTEM 模式: 设置 socket 保护函数，用于 DNS 查询绕过 VPN
            if (transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
                addLog(">>> [VpnController] 设置 DNS Interceptor 保护函数 (SYSTEM 模式)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                dnsInterceptor.setProtectFunction { socket ->
                    addLog(">>> [VpnController] 保护函数被调用: socket=$socket", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                    val result = protectDatagramChannel?.invoke(socket) ?: false
                    addLog(">>> [VpnController] 保护函数返回: $result", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                    result
                }
            }
            
            addLog("DNS 拦截器已配置 (模式: $transportMode)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

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
                        addLog("解析 DNS IP 失败: $dnsIp", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                    }
                }
                if (systemDns.isNotEmpty()) {
                    addLog("SYSTEM 模式: 绕过 VPN 的 DNS 服务器: ${systemDns.joinToString(", ")}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
                } else {
                    addLog("SYSTEM 模式: 未获取到系统 DNS，使用默认 8.8.8.8", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                    // 兜底：添加常用公共 DNS 到排除路由
                    for (dnsIp in listOf("8.8.8.8", "1.1.1.1", "114.114.114.114")) {
                        try {
                            val addr = InetAddress.getByName(dnsIp)
                            excludedRoutes += CidrRoute(addr, if (addr is java.net.Inet6Address) 128 else 32)
                        } catch (_: Exception) {}
                    }
                }
            }
            
            if (excludedRoutes.isNotEmpty()) {
                addLog("排除路由: ${excludedRoutes.size} 条规则", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            }

            // 5. 注册 TUN 写回回调
            packetProcessor.setTunWriter { data -> writeToTun(data) }
            addLog("TUN 写回通道已就绪", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

            // 6. 启动连接清理定时任务
            launch { connectionCleanupLoop() }
            addLog("连接清理任务已启动", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

            // 6.1 启动独立 DNS 响应投递协程 (修复 DNS 死锁)
            launch { dnsResponseDeliveryLoop() }
            addLog("DNS 响应投递协程已启动", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

            updateState {
                it.copy(
                    status = VpnState.VpnStatus.Connected,
                    stats = it.stats.copy(startTime = java.util.Date())
                )
            }

            addLog("VPN 连接已建立，开始处理数据包", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.SUCCESS)

            // 7. 启动数据包处理循环
            launch { packetLoop() }

            Result.success(Unit)

        } catch (e: Exception) {
            addLog("连接失败: ${e.message}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * 断开 VPN 连接
     */
    suspend fun disconnect() {
        if (!isRunning) return

        addLog("正在断开 VPN 连接...", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
        isRunning = false
        updateState { it.copy(status = VpnState.VpnStatus.Disconnecting) }

        // 取消所有子协程
        coroutineContext.cancelChildren()
        addLog("已取消所有子任务", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

        // 断开 SSH 连接
        // 停止所有隧道插件
        try {
            addLog("正在断开隧道连接...", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            tunnelManager.stopAll()
            addLog("隧道连接已断开", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.SUCCESS)
        } catch (e: Exception) {
            addLog("隧道断开错误: ${e.message}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
        }

        // 关闭输入输出流
        try {
            inputStream?.close()
            outputStream?.close()
            addLog("TUN 数据流已关闭", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
        } catch (_: Exception) {}

        // 清理状态
        vpnInterface = null
        inputStream = null
        outputStream = null
        currentServer = null

        updateState {
            it.copy(
                status = VpnState.VpnStatus.Disconnected,
                server = null,
                stats = it.stats.copy(
                    lastUpdate = java.util.Date()
                )
            )
        }
        addLog("VPN 连接已完全断开", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
    }

    /**
     * 实时更新 DNS 传输模式
     */
    suspend fun updateDnsMode() {
        if (!isRunning) return
        val dnsModeValue = settingsDataStore.dnsMode.first()
        this.transportMode = when (dnsModeValue) {
            0 -> DnsInterceptor.DnsTransport.REMOTE      // 全部走隧道
            1 -> DnsInterceptor.DnsTransport.SYSTEM      // 系统默认，完全透传
            2 -> DnsInterceptor.DnsTransport.WHITELIST   // 白名单分流
            else -> DnsInterceptor.DnsTransport.REMOTE
        }
        dnsInterceptor.setTransportMode(this.transportMode)
        
        // SYSTEM 模式: 设置/更新 socket 保护函数，用于 DNS 查询绕过 VPN
        if (this.transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
            addLog(">>> [VpnController] updateDnsMode: 设置 DNS Interceptor 保护函数 (SYSTEM 模式)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
            dnsInterceptor.setProtectFunction { socket ->
                addLog(">>> [VpnController] 保护函数被调用: socket=$socket", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                val result = protectDatagramChannel?.invoke(socket) ?: false
                addLog(">>> [VpnController] 保护函数返回: $result", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                result
            }
        }

        // 所有模式都不排除 DNS 服务器，让 DNS 流量走 VPN 隧道
        val commonDohEndpoints = emptyList<CidrRoute>()
        val dnsExcludes = emptyList<CidrRoute>()

        val baseRoutes = currentServer?.excludedRoutes?.mapNotNull { parseCidr(it) } ?: emptyList()
        excludedRoutes = baseRoutes + commonDohEndpoints + dnsExcludes
        addLog("DNS 模式已切换: $transportMode, 排除路由: ${excludedRoutes.size} 条", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
    }

    /**
     * 强制重置状态 (超时后调用)
     */
    fun forceReset() {
        isRunning = false
        try { coroutineContext.cancelChildren() } catch (_: Exception) {}
        vpnInterface = null
        inputStream = null
        outputStream = null
        currentServer = null
        updateState {
            it.copy(
                status = VpnState.VpnStatus.Disconnected,
                server = null
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
     * 数据包处理主循环
     */
    private fun packetLoop() {
        android.util.Log.d("VpnController", "packetLoop started")
        while (isRunning && inputStream != null) {
            try {
                val bytesRead = inputStream!!.read(readBuffer.array())
                if (bytesRead <= 0) continue

                readBuffer.limit(bytesRead)
                readBuffer.position(0)
                
                android.util.Log.d("VpnController", "packetLoop read ${bytesRead} bytes")
                processPacket(readBuffer)

                // 更新统计
                val stats = connectionStats.value
                connectionStats.value = stats.copy(
                    bytesReceived = stats.bytesReceived + bytesRead.toLong(),
                    lastUpdate = java.util.Date()
                )

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
            val firstByte = buffer.get(buffer.position()) .toInt() and 0xFF
            val version = firstByte shr 4
            android.util.Log.d("VpnController", "processPacket: firstByte=0x${"%02x".format(firstByte)} version=$version remaining=${buffer.remaining()}")
            
            // Debug: dump first 16 bytes
            val debugBytes = ByteArray(16)
            val origPos = buffer.position()
            buffer.get(debugBytes)
            buffer.position(origPos)
            android.util.Log.d("VpnController", "raw bytes: ${debugBytes.joinToString("") { "%02x".format(it) }}")

            
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
                    android.util.Log.d("VpnController", "unrecognized packet prefix, first bytes: ${dump.joinToString("") { "%02x".format(it) }}")
                    buffer.position(savedPos)
                }
            }
            
            // 提取目标 IP 以检查排除路由
            val dstIp = extractDstIp(workBuffer, workVersion)
            if (dstIp != null && shouldBypassVpn(dstIp)) {
                if (transportMode == DnsInterceptor.DnsTransport.SYSTEM) {
                    forwardDnsBypassPacket(readBuffer, dstIp, workVersion)
                } else {
                    writeToTun(readBuffer.array().copyOfRange(0, readBuffer.limit()))
                }
                return
            }

            when (workVersion) {
                4 -> {
                    val processed = packetProcessor.processIpv4Packet(workBuffer, fd)
                    if (!processed) {
                        writeToTun(readBuffer.array().copyOfRange(0, readBuffer.limit()))
                    }
                }
                6 -> {
                    val processed = packetProcessor.processIpv6Packet(workBuffer, fd)
                    if (!processed) {
                        writeToTun(readBuffer.array().copyOfRange(0, readBuffer.limit()))
                    }
                }
                else -> {
                    addLog(">>> [VpnController] 未知 IP 版本: $workVersion，丢弃", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                    // 未知版本，丢弃
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VpnController", "processPacket error: ${e.message}")
        }
    }

    private fun extractDstIp(buffer: ByteBuffer, version: Int): InetAddress? {
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

    private fun shouldBypassVpn(dstIp: InetAddress): Boolean {
        val result = excludedRoutes.any { ipMatchesCidr(dstIp, it) }
        if (result) {
            android.util.Log.d("VpnController", "shouldBypassVpn: TRUE for $dstIp (excludedRoutes=${excludedRoutes.size})")
        }
        return result
    }

    private fun writeDnsResponse(response: DnsInterceptor.DnsResponse) {
        try {
            val vpnIp = InetAddress.getByName("10.0.0.2")
            val packet = packetProcessor.buildUdpResponsePacket(
                srcIp = vpnIp.address,
                dstIp = response.dstIp.address,
                srcPort = 53,
                dstPort = response.dstPort,
                payload = response.data
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
        val stats = connectionStats.value
        connectionStats.value = stats.copy(
            bytesSent = stats.bytesSent + data.size.toLong()
        )
    }

    /**
     * SYSTEM 模式 DNS 绕过: 通过受保护 socket 转发 DNS 查询到物理网卡
     */
    private fun forwardDnsBypassPacket(buffer: ByteBuffer, dstIp: InetAddress, version: Int) {
        // 复制必要的数据到新数组，避免与主线程共享 buffer
        val packetData = ByteArray(buffer.remaining())
        buffer.duplicate().get(packetData)
        
        executor.submit {
            try {
                addLog(">>> [VpnController] forwardDnsBypassPacket: dstIp=$dstIp, version=$version, packetSize=${packetData.size}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                val socket = java.net.DatagramSocket()
                val protected = protectDatagramChannel?.invoke(socket) ?: false
                addLog(">>> [VpnController] VpnService.protect()=$protected", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                if (!protected) {
                    addLog("SYSTEM DNS: VpnService.protect() 失败", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                }
                socket.soTimeout = 5000
                
                // 提取 IP 载荷 (UDP 数据) - 从复制的数据中解析
                val ipHeaderLen = if (version == 4) {
                    val ihl = ((packetData[0].toInt() and 0x0F) * 4)
                    ihl
                } else {
                    40 // IPv6 固定头部
                }
                val payloadStart = ipHeaderLen
                val payloadLen = packetData.size - ipHeaderLen
                if (payloadLen <= 0) {
                    addLog(">>> [VpnController] payloadLen <= 0, 返回", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                    return@submit
                }
                
                val payload = packetData.copyOfRange(payloadStart, packetData.size)
                
                val dnsServer = dstIp.hostAddress
                val packet = java.net.DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(dnsServer), 53)
                addLog(">>> [VpnController] 发送 DNS 查询到 $dnsServer:53, payload=${payload.size} bytes", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                socket.send(packet)
                addLog(">>> [VpnController] 已发送，等待响应...", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                
                // 接收响应
                val responseBuf = ByteArray(512)
                val responsePacket = java.net.DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(responsePacket)
                val responseData = responseBuf.copyOfRange(0, responsePacket.length)
                addLog("<<< [VpnController] 收到 DNS 响应来自 ${responsePacket.address}:${responsePacket.port} (${responseData.size} bytes)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                
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
                    addLog(">>> [VpnController] 解析原始包: srcIp=$srcIp, srcPort=$srcPort", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                } catch (e: Exception) {
                    addLog(">>> [VpnController] 解析源 IP/端口失败: ${e.message}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
                    srcIp = InetAddress.getByName("10.0.0.1")
                    srcPort = 53
                }
                
                val vpnIp = InetAddress.getByName("10.0.0.2")
                val responsePkt = packetProcessor.buildUdpResponsePacket(
                    srcIp = vpnIp.address,
                    dstIp = srcIp.address,
                    srcPort = 53,
                    dstPort = srcPort,
                    payload = responseData
                )
                addLog(">>> [VpnController] 构造响应包完成: srcPort=53, dstPort=$srcPort, packetSize=${responsePkt.size}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                writeToTun(responsePkt)
                addLog(">>> [VpnController] 已写回 TUN", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                socket.close()
                
            } catch (e: java.net.SocketTimeoutException) {
                addLog(">>> [VpnController] DNS 响应超时 (SocketTimeoutException)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
            } catch (e: Exception) {
                addLog(">>> [VpnController] forwardDnsBypassPacket exception: ${e.message}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
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
            kotlinx.coroutines.delay(60000) // 每分钟清理一次
            if (!isRunning) break
            packetProcessor.cleanupStaleConnections(300000) // 5 分钟超时
        } catch (e: Exception) {
            if (isRunning) {
                android.util.Log.e("VpnController", "Connection cleanup error: ${e.message}")
            }
        }
    }
}

    /**
     * 获取系统配置的 DNS 服务器
     */
    private fun getSystemDnsServers(): List<String> {
        val dnsList = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return dnsList
            val networkCapabilities = cm.getNetworkCapabilities(network) ?: return dnsList
            
            // 使用反射获取 LinkProperties (Android 14+)
            val linkProperties = networkCapabilities.javaClass.getMethod("getLinkProperties").invoke(networkCapabilities)
                ?: return dnsList
            
            val dnsServers = linkProperties.javaClass.getField("dnsServers").get(linkProperties) as? java.util.List<*>
                ?: return dnsList
            
            for (dns in dnsServers) {
                val host = dns.javaClass.getField("hostAddress").get(dns) as? String
                if (host != null && host.isNotEmpty() && !host.startsWith("fe80") && !host.startsWith("::1") && !host.startsWith("127.")) {
                    dnsList.add(host)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VpnController", "获取系统 DNS 失败: ${e.message}")
        }
        return dnsList
    }

    private suspend fun loadRouteConfig() {
        try {
            val json = settingsDataStore.routeConfig.first()
            if (!json.isNullOrBlank()) {
                val obj = org.json.JSONObject(json)
                val appTags = mutableListOf<com.sshinjector.domain.vpn.tunnel.AppTagEntry>()
                val appTagsArr = obj.optJSONArray("appTags")
                if (appTagsArr != null) {
                    for (i in 0 until appTagsArr.length()) {
                        val entry = appTagsArr.getJSONObject(i)
                        val pkg = entry.getString("packageName")
                        val tags = (0 until entry.getJSONArray("tags").length())
                            .map { entry.getJSONArray("tags").getString(it) }.toSet()
                        appTags.add(com.sshinjector.domain.vpn.tunnel.AppTagEntry(pkg, tags))
                    }
                }
                val tagTunnels = mutableListOf<com.sshinjector.domain.vpn.tunnel.TagTunnelEntry>()
                val tagTunnelsArr = obj.optJSONArray("tagTunnels")
                if (tagTunnelsArr != null) {
                    for (i in 0 until tagTunnelsArr.length()) {
                        val entry = tagTunnelsArr.getJSONObject(i)
                        tagTunnels.add(
                            com.sshinjector.domain.vpn.tunnel.TagTunnelEntry(
                                tag = entry.getString("tag"),
                                primaryTunnelId = entry.getString("primaryTunnelId"),
                            )
                        )
                    }
                }
                val config = com.sshinjector.domain.vpn.tunnel.RouteConfig(
                    appTags = appTags,
                    tagTunnels = tagTunnels,
                    defaultTunnelId = obj.optString("defaultTunnelId", "socks5"),
                )
                tunnelRouter.updateConfig(config)
            }
        } catch (_: Exception) {}
    }

    private fun buildTunnelConfig(server: ServerConfig, password: String?): TunnelConfig {
        return when (server.tunnelType) {
            "socks5" -> TunnelConfig.Socks5(
                sshHost = server.host,
                sshPort = server.port,
                sshUsername = server.username,
                sshKeyAlias = server.keyAlias,
                sshPassword = password ?: server.password,
                sshKeyAlgorithm = server.keyAlgorithm.name,
                common = TunnelConfig.CommonConfig(
                    connectTimeout = server.connectTimeout,
                    keepAliveInterval = server.keepAliveInterval,
                ),
            )
            "direct" -> TunnelConfig.Direct
            "https_proxy" -> TunnelConfig.HttpsProxy(
                proxyHost = server.host,
                proxyPort = server.port,
                common = TunnelConfig.CommonConfig(connectTimeout = server.connectTimeout),
            )
            "v2ray" -> TunnelConfig.V2Ray(
                serverHost = server.host,
                serverPort = server.port,
                uuid = server.keyAlias,
                common = TunnelConfig.CommonConfig(connectTimeout = server.connectTimeout),
            )
            "trojan" -> TunnelConfig.Trojan(
                serverHost = server.host,
                serverPort = server.port,
                password = server.keyAlias,
                common = TunnelConfig.CommonConfig(connectTimeout = server.connectTimeout),
            )
            "shadowsocks" -> TunnelConfig.Shadowsocks(
                serverHost = server.host,
                serverPort = server.port,
                password = server.keyAlias,
                common = TunnelConfig.CommonConfig(connectTimeout = server.connectTimeout),
            )
            else -> TunnelConfig.Socks5(
                sshHost = server.host,
                sshPort = server.port,
                sshUsername = server.username,
                sshKeyAlias = server.keyAlias,
                sshPassword = password ?: server.password,
                sshKeyAlgorithm = server.keyAlgorithm.name,
            )
        }
    }

    fun getCurrentServer(): ServerConfig? = currentServer
    fun isVpnRunning(): Boolean = isRunning

    private fun updateState(block: (VpnState) -> VpnState) {
        vpnState.update(block)
    }
}