package com.sshinjector.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.domain.usecase.ServerRepository
import com.sshinjector.domain.usecase.VpnController
import com.sshinjector.vpn.SshVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpnController: VpnController,
    private val serverRepository: ServerRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    data class DnsDiagnostics(
        val dnsLatencyMs: Long? = null,
        val dnsSuccess: Boolean = false,
        val dnsSuccessCount: Int = 0,
        val httpLatencyMs: Long? = null,
        val httpSuccess: Boolean = false,
        val httpSuccessCount: Int = 0,
        val httpStatusCode: Int = 0,
        val isRunning: Boolean = false,
        val lastTestTime: Long = 0
    )

    data class UiState(
        val isConnected: Boolean = false,
        val hasDefaultServer: Boolean = false,
        val defaultServerId: Long = 0,
        val defaultServerName: String = "",
        val currentServer: String = "未连接",
        val currentServerHost: String = "",
        val currentServerUser: String = "",
        val bytesSent: Long = 0,
        val bytesReceived: Long = 0,
        val uploadSpeed: Long = 0,
        val downloadSpeed: Long = 0,
        val connectionStatus: String = "断开",
        val errorMessage: String? = null,
        val startTime: Date? = null,
        val localIp: String? = null,
        val remoteIp: String? = null,
        // 网络信息
        val deviceIpv4: String = "---",
        val deviceIpv6: String = "---",
        val dnsMode: String = "默认",
        val proxyAddress: String = "---",

        // VPN 权限
        val vpnPermissionIntent: Intent? = null,
        // 连接日志
        val connectionLogs: List<ConnectionLog> = emptyList(),
        val logLevel: Int = 1, // 0=简洁 1=详细
        // 诊断信息
        val diagnostics: DnsDiagnostics = DnsDiagnostics()
    )

    data class ConnectionLog(
        val timestamp: String,
        val message: String,
        val level: LogLevel
    )

    enum class LogLevel {
        INFO,      // 基本信息
        DEBUG,     // 调试信息
        SUCCESS,   // 成功
        ERROR,     // 错误
        WARNING    // 警告
    }

    init {
        observeVpnState()
        loadDefaultServer()
        loadNetworkInfo()
        observeDnsModeChanges()
    }

    private fun observeDnsModeChanges() {
        viewModelScope.launch {
            settingsDataStore.dnsMode.collect { dnsModeValue ->
                val dnsModeText = when (dnsModeValue) {
                    0 -> "远程代理"
                    1 -> "本地直连"
                    2 -> "自动模式"
                    else -> "远程代理"
                }
                _uiState.update { it.copy(dnsMode = dnsModeText) }
            }
        }
    }

    private fun loadDefaultServer() {
        viewModelScope.launch {
            serverRepository.activeServerFlow.collect { server ->
                _uiState.update {
                    it.copy(
                        hasDefaultServer = server != null,
                        defaultServerId = server?.id ?: 0,
                        defaultServerName = server?.name ?: ""
                    )
                }
            }
        }
    }

    private fun loadNetworkInfo() {
        viewModelScope.launch {
            // 获取设备 IP 地址
            val ipv4 = getDeviceIpv4()
            val ipv6 = getDeviceIpv6()

            // 获取 DNS 模式
            val dnsModeValue = settingsDataStore.dnsMode.first()
            val dnsModeText = when (dnsModeValue) {
                0 -> "远程代理"
                1 -> "本地直连"
                2 -> "自动模式"
                else -> "远程代理"
            }

            _uiState.update {
                it.copy(
                    deviceIpv4 = ipv4,
                    deviceIpv6 = ipv6,
                    dnsMode = dnsModeText
                )
            }
        }
    }

    private fun getDeviceIpv4(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "---"
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Failed to get IPv4: ${e.message}")
        }
        return "---"
    }

    private fun getDeviceIpv6(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet6Address && !address.isLoopbackAddress) {
                        val hostAddr = address.hostAddress ?: continue
                        // 排除链路本地地址
                        if (!hostAddr.startsWith("fe80")) {
                            return hostAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Failed to get IPv6: ${e.message}")
        }
        return "---"
    }

    private val _connectionLogs = mutableListOf<ConnectionLog>()

    private fun observeVpnState() {
        viewModelScope.launch {
            // 加载日志级别
            val logLevel = settingsDataStore.logLevel.first()
            _uiState.update { it.copy(logLevel = logLevel) }

            // 观察 VPN 控制器的日志流
            launch {
                vpnController.logFlow.collect { (message, level) ->
                    addLog(message, level)
                }
            }

            combine(
                vpnController.vpnState,
                vpnController.connectionStats
            ) { state, stats ->
                val isConnected = state.status == com.sshinjector.domain.model.VpnState.VpnStatus.Connected
                val proxyAddr = if (isConnected) "127.0.0.1:1080" else "---"

                // 根据状态变化添加日志
                addLogForState(state.status.name, state.error)

                UiState(
                    isConnected = isConnected,
                    hasDefaultServer = _uiState.value.hasDefaultServer,
                    defaultServerId = _uiState.value.defaultServerId,
                    defaultServerName = _uiState.value.defaultServerName,
                    currentServer = state.server?.name ?: "未连接",
                    currentServerHost = state.server?.host ?: "",
                    currentServerUser = state.server?.username ?: "",
                    connectionStatus = state.status.name,
                    startTime = state.stats.startTime,
                    errorMessage = state.error,
                    bytesSent = stats.bytesSent,
                    bytesReceived = stats.bytesReceived,
                    localIp = if (isConnected) "10.0.0.1" else null,
                    remoteIp = if (isConnected) state.server?.host else null,
                    proxyAddress = proxyAddr,
                    deviceIpv4 = _uiState.value.deviceIpv4,
                    deviceIpv6 = _uiState.value.deviceIpv6,
                    dnsMode = _uiState.value.dnsMode,
                    connectionLogs = _connectionLogs.toList(),
                    logLevel = _uiState.value.logLevel,
                    diagnostics = _uiState.value.diagnostics
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private fun addLogForState(status: String, error: String?) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        when (status) {
            "Connecting" -> {
                addLogInternal(time, "正在连接 SSH 服务器...", LogLevel.INFO)
                addLogInternal(time, "解析服务器地址...", LogLevel.DEBUG)
            }
            "Authenticating" -> {
                addLogInternal(time, "SSH 连接已建立", LogLevel.SUCCESS)
                addLogInternal(time, "正在使用密钥进行认证...", LogLevel.INFO)
                addLogInternal(time, "发送公钥到服务器...", LogLevel.DEBUG)
            }
            "EstablishingTunnel" -> {
                addLogInternal(time, "SSH 认证成功", LogLevel.SUCCESS)
                addLogInternal(time, "正在建立端口转发隧道...", LogLevel.INFO)
                addLogInternal(time, "设置本地 SOCKS5 代理端口...", LogLevel.DEBUG)
            }
            "Connected" -> {
                addLogInternal(time, "SSH 隧道已建立", LogLevel.SUCCESS)
                addLogInternal(time, "SOCKS5 代理服务启动", LogLevel.SUCCESS)
                addLogInternal(time, "VPN 接口已创建", LogLevel.SUCCESS)
                addLogInternal(time, "代理服务运行中", LogLevel.INFO)
            }
            "Disconnecting" -> {
                addLogInternal(time, "正在断开 SSH 连接...", LogLevel.WARNING)
                addLogInternal(time, "正在清理 VPN 资源...", LogLevel.WARNING)
            }
            "Reconnecting" -> {
                addLogInternal(time, "检测到网络变化，正在重连...", LogLevel.WARNING)
                addLogInternal(time, "正在重新建立 SSH 连接...", LogLevel.INFO)
            }
            "Failed" -> {
                addLogInternal(time, "连接失败: ${error ?: "未知错误"}", LogLevel.ERROR)
                addLogInternal(time, "请检查服务器配置和网络连接", LogLevel.INFO)
            }
        }
        _uiState.update { it.copy(connectionLogs = _connectionLogs.toList()) }
    }

    fun addLog(message: String, level: LogLevel) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        // 新日志插入到列表头部（逆序存储，避免每次显示时排序）
        _connectionLogs.add(0, ConnectionLog(time, message, level))
        // 保持最近 50 条日志
        if (_connectionLogs.size > 50) {
            _connectionLogs.removeAt(_connectionLogs.lastIndex)
        }
        _uiState.update { it.copy(connectionLogs = _connectionLogs.toList()) }
    }

    private fun addLogInternal(time: String, message: String, level: LogLevel) {
        // 新日志插入到列表头部
        _connectionLogs.add(0, ConnectionLog(time, message, level))
        if (_connectionLogs.size > 50) {
            _connectionLogs.removeAt(_connectionLogs.lastIndex)
        }
    }

    fun clearLogs() {
        _connectionLogs.clear()
        _uiState.update { it.copy(connectionLogs = emptyList()) }
    }

    fun setLogLevel(level: Int) {
        viewModelScope.launch {
            settingsDataStore.setLogLevel(level)
            _uiState.update { it.copy(logLevel = level) }
        }
    }

    fun switchDnsMode() {
        viewModelScope.launch {
            val current = settingsDataStore.dnsMode.first()
            val next = (current + 1) % 3  // 3 modes: 0=REMOTE, 1=SYSTEM, 2=WHITELIST
            settingsDataStore.setDnsMode(next)
            if (_uiState.value.isConnected) {
                vpnController.updateDnsMode()
            }
            runDiagnostics()
        }
    }

    fun connectDefaultServerWithDiag() {
        viewModelScope.launch {
            runDiagnostics()
            kotlinx.coroutines.delay(50)
            connectDefaultServer()
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(diagnostics = DnsDiagnostics(isRunning = true)) }

            val dnsMode = settingsDataStore.dnsMode.first()
            val testCount = 5

            // DNS: 异步并行 5 次取平均
            val dnsResults = withContext(kotlinx.coroutines.Dispatchers.IO) {
                (1..testCount).map { testDnsResolution(dnsMode) }
            }
            val dnsSuccessCount = dnsResults.count { it.second }
            val dnsAvgLatency = dnsResults.filter { it.second && it.first != null }
                .mapNotNull { it.first }
                .takeIf { it.isNotEmpty() }
                ?.let { list -> list.sum() / list.size }

            // HTTP: 异步并行 5 次取平均
            val httpResults = withContext(kotlinx.coroutines.Dispatchers.IO) {
                (1..testCount).map { testHttpConnectivity() }
            }
            val httpSuccessCount = httpResults.count { it.second }
            val httpAvgLatency = httpResults.filter { it.second && it.first != null }
                .mapNotNull { it.first }
                .takeIf { it.isNotEmpty() }
                ?.let { list -> list.sum() / list.size }
            val lastHttpCode = httpResults.lastOrNull { it.second }?.third ?: 0

            _uiState.update {
                it.copy(diagnostics = DnsDiagnostics(
                    dnsLatencyMs = dnsAvgLatency,
                    dnsSuccess = dnsSuccessCount > 0,
                    dnsSuccessCount = dnsSuccessCount,
                    httpLatencyMs = httpAvgLatency,
                    httpSuccess = httpSuccessCount > 0,
                    httpSuccessCount = httpSuccessCount,
                    httpStatusCode = lastHttpCode,
                    isRunning = false,
                    lastTestTime = System.currentTimeMillis()
                ))
            }
        }
    }

    private fun testDnsResolution(dnsMode: Int): Pair<Long?, Boolean> {
        return try {
            val start = System.currentTimeMillis()
            android.util.Log.d("MainViewModel", "testDnsResolution: dnsMode=$dnsMode")

            when (dnsMode) {
                0, 2 -> {
                    // REMOTE / WHITELIST 模式: 测试 DoH (dns.alidns.com)
                    android.util.Log.d("MainViewModel", "testDnsResolution: testing DoH dns.alidns.com")
                    val url = java.net.URL("https://dns.alidns.com/dns-query")
                    val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("Content-Type", "application/dns-message")
                    connection.setRequestProperty("Accept", "application/dns-message")

                    val query = byteArrayOf(
                        0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6C, 0x65,
                        0x03, 0x63, 0x6F, 0x6D, 0x00,
                        0x00, 0x01, 0x00, 0x01
                    )
                    connection.outputStream.write(query)
                    connection.outputStream.flush()

                    val responseCode = connection.responseCode
                    val elapsed = System.currentTimeMillis() - start
                    connection.disconnect()
                    Pair(elapsed, responseCode == 200)
                }
                1 -> {
                    // SYSTEM 模式: 测试系统 DNS 服务器 (UDP)
                    android.util.Log.d("MainViewModel", "testDnsResolution: testing UDP DNS 8.8.8.8:53")
                    val socket = java.net.DatagramSocket()
                    socket.soTimeout = 5000
                    val query = byteArrayOf(
                        0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6C, 0x65,
                        0x03, 0x63, 0x6F, 0x6D, 0x00,
                        0x00, 0x01, 0x00, 0x01
                    )
                    val addr = java.net.InetAddress.getByName("8.8.8.8")
                    val packet = java.net.DatagramPacket(query, query.size, addr, 53)
                    socket.send(packet)
                    val buf = ByteArray(512)
                    val respPacket = java.net.DatagramPacket(buf, buf.size)
                    socket.receive(respPacket)
                    socket.close()
                    val elapsed = System.currentTimeMillis() - start
                    val rcode = buf[3].toInt() and 0x0F
                    Pair(elapsed, rcode == 0)
                }
                else -> Pair(null, false)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "DNS test failed: ${e.message}")
            Pair(null, false)
        }
    }

    private fun testHttpConnectivity(): Triple<Long?, Boolean, Int> {
        return try {
            val start = System.currentTimeMillis()
            android.util.Log.d("MainViewModel", "testHttpConnectivity: testing https://www.baidu.com")
            // 使用 HTTPS 测试 HTTP 连通性
            val url = java.net.URL("https://www.baidu.com")
            val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            val elapsed = System.currentTimeMillis() - start
            connection.disconnect()

            // 200 或 301/302 都算成功
            Triple(elapsed, responseCode in listOf(200, 301, 302), responseCode)
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "HTTP test failed: ${e.message}")
            Triple(null, false, 0)
        }
    }

    fun connect(serverId: Long) {
        // 检查 VPN 权限
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            // 需要请求 VPN 权限，返回 intent 让 Activity 处理
            _uiState.update { it.copy(vpnPermissionIntent = prepareIntent) }
            return
        }
        startVpnService(serverId)
    }

    fun onVpnPermissionGranted(serverId: Long) {
        _uiState.update { it.copy(vpnPermissionIntent = null) }
        startVpnService(serverId)
    }

    private fun startVpnService(serverId: Long) {
        val intent = Intent(context, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_CONNECT
            putExtra(SshVpnService.EXTRA_SERVER_ID, serverId)
        }
        context.startForegroundService(intent)
    }

    fun connectDefaultServer() {
        val serverId = _uiState.value.defaultServerId
        if (serverId > 0) {
            connect(serverId)
        }
    }

    fun disconnect() {
        _uiState.update { it.copy(connectionStatus = "Disconnecting") }
        val intent = Intent(context, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
        // 3s 超时强制停止
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (_uiState.value.connectionStatus == "Disconnecting") {
                context.stopService(Intent(context, SshVpnService::class.java))
                vpnController.forceReset()
            }
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
