package com.sshinjector.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.domain.usecase.ServerRepository
import com.sshinjector.domain.usecase.VpnController
import com.sshinjector.domain.vpn.tunnel.TunnelManager
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
    private val settingsDataStore: SettingsDataStore,
    private val tunnelManager: TunnelManager
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
        val currentServerId: Long = 0,
        val currentServerHost: String = "",
        val currentServerUser: String = "",
        val connectionStatus: String = "断开",
        val errorMessage: String? = null,
        val startTime: Date? = null,
        val connectionDuration: String = "00:00:00",
        // 网络信息
        val deviceIpv4: String = "---",
        val deviceIpv6: String = "---",
        val dnsMode: String = "默认",
        val proxyAddress: String = "---",
        // VPN 权限
        val vpnPermissionIntent: Intent? = null,
        val pendingConnectServerId: Long? = null,
        // 诊断信息
        val diagnostics: DnsDiagnostics = DnsDiagnostics(),
        // 服务器连接状态: serverId -> status
        val serverConnectionStatus: Map<Long, String?> = emptyMap()
    )

    init {
        observeVpnState()
        loadDefaultServer()
        loadNetworkInfo()
        observeDnsModeChanges()
    }

    private fun observeDnsModeChanges() {
        viewModelScope.launch {
            settingsDataStore.dnsMode.collect { dnsModeValue ->
                _uiState.update { it.copy(dnsMode = dnsModeLabel(dnsModeValue)) }
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
            val ipv4 = getDeviceIpv4()
            val ipv6 = getDeviceIpv6()
            val dnsModeValue = settingsDataStore.dnsMode.first()
            val dnsModeText = dnsModeLabel(dnsModeValue)

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

    private fun observeVpnState() {
        viewModelScope.launch {
            // 观察 VPN 控制器状态
            launch {
                vpnController.vpnState.collect { state ->
                    val isConnected = state.status == com.sshinjector.domain.model.VpnState.VpnStatus.Connected
                    val serverId = state.server?.id ?: 0
                    val status = state.status.name

                    // 更新当前连接的服务器状态
                    val newServerStatus = if (serverId > 0) {
                        _uiState.value.serverConnectionStatus.toMutableMap().apply {
                            // 清除其他服务器的状态
                            keys.filter { it != serverId }.forEach { remove(it) }
                            // 设置当前服务器状态
                            put(serverId, if (isConnected) "Connected" else status)
                        }
                    } else {
                        emptyMap()
                    }

                    // 计算连接时长
                    val duration = if (state.stats.startTime != null) {
                        val elapsed = (java.util.Date().time - state.stats.startTime.time) / 1000
                        String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
                    } else {
                        "00:00:00"
                    }

                    _uiState.update {
                        it.copy(
                            isConnected = isConnected,
                            currentServer = state.server?.name ?: "未连接",
                            currentServerId = serverId,
                            currentServerHost = state.server?.host ?: "",
                            currentServerUser = state.server?.username ?: "",
                            connectionStatus = status,
                            startTime = state.stats.startTime,
                            connectionDuration = duration,
                            proxyAddress = if (isConnected) "127.0.0.1:1080" else "---",
                            serverConnectionStatus = newServerStatus
                        )
                    }
                }
            }

            // 定时更新连接时长
            launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val startTime = _uiState.value.startTime
                    if (startTime != null && _uiState.value.isConnected) {
                        val elapsed = (java.util.Date().time - startTime.time) / 1000
                        val duration = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
                        _uiState.update { it.copy(connectionDuration = duration) }
                    }
                }
            }
        }
    }

    fun switchDnsMode() {
        viewModelScope.launch {
            val current = settingsDataStore.dnsMode.first()
            val next = nextDnsMode(current)
            settingsDataStore.setDnsMode(next)
            if (_uiState.value.isConnected) {
                vpnController.updateDnsMode()
                try {
                    val intent = Intent(context, SshVpnService::class.java).apply {
                        action = SshVpnService.ACTION_REBUILD
                    }
                    context.startService(intent)
                } catch (_: Exception) {}
            }
            runDiagnostics()
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(diagnostics = DnsDiagnostics(isRunning = true)) }

            val dnsMode = settingsDataStore.dnsMode.first()
            val testCount = 5

            val dnsResults = withContext(kotlinx.coroutines.Dispatchers.IO) {
                (1..testCount).map { testDnsResolution(dnsMode) }
            }
            val dnsSuccessCount = dnsResults.count { it.second }
            val dnsAvgLatency = dnsResults.filter { it.second && it.first != null }
                .mapNotNull { it.first }
                .takeIf { it.isNotEmpty() }
                ?.let { list -> list.sum() / list.size }

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
            when (dnsMode) {
                0, 2 -> {
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
            Pair(null, false)
        }
    }

    private fun testHttpConnectivity(): Triple<Long?, Boolean, Int> {
        return try {
            val start = System.currentTimeMillis()
            val url = java.net.URL("https://www.baidu.com")
            val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            val responseCode = connection.responseCode
            val elapsed = System.currentTimeMillis() - start
            connection.disconnect()
            Triple(elapsed, responseCode in listOf(200, 301, 302), responseCode)
        } catch (e: Exception) {
            Triple(null, false, 0)
        }
    }

    fun connect(serverId: Long) {
        // 更新服务器连接状态为 Connecting
        val newServerStatus = _uiState.value.serverConnectionStatus.toMutableMap().apply {
            put(serverId, "Connecting")
        }
        _uiState.update { it.copy(serverConnectionStatus = newServerStatus) }

        // 检查 VPN 权限
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            _uiState.update { it.copy(vpnPermissionIntent = prepareIntent, pendingConnectServerId = serverId) }
            return
        }
        startVpnService(serverId)
    }

    fun onVpnPermissionGranted(serverId: Long) {
        _uiState.update { it.copy(vpnPermissionIntent = null, pendingConnectServerId = null) }
        startVpnService(serverId)
    }

    private fun startVpnService(serverId: Long) {
        val intent = Intent(context, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_CONNECT
            putExtra(SshVpnService.EXTRA_SERVER_ID, serverId)
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        _uiState.update { it.copy(connectionStatus = "Disconnecting") }
        val intent = Intent(context, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (_uiState.value.connectionStatus == "Disconnecting") {
                context.stopService(Intent(context, SshVpnService::class.java))
                vpnController.forceReset()
            }
        }
    }

    val allServers = serverRepository.allServersFlow

    fun toggleDefaultServer(id: Long) {
        viewModelScope.launch {
            val server = serverRepository.getServerById(id)
            if (server?.isActive == true) {
                serverRepository.deactivateAllServers()
            } else {
                serverRepository.setActiveServer(id)
            }
        }
    }
}

enum class LogLevel {
    INFO,
    DEBUG,
    SUCCESS,
    ERROR,
    WARNING
}

internal fun nextDnsMode(current: Int): Int = (current + 1) % 4

internal fun dnsModeLabel(mode: Int): String = when (mode) {
    0 -> "远程代理"
    1 -> "本地直连"
    2 -> "白名单模式"
    3 -> "域名分流"
    else -> "远程代理"
}
