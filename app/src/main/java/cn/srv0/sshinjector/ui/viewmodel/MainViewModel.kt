package cn.srv0.sshinjector.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Debug
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.vpn.SshVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val vpnController: VpnController,
        private val serverRepository: ServerRepository,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModel() {
        enum class RatioLevel {
            OK,
            WARNING,
            BAD,
        }

        private val _uiState = MutableStateFlow(UiState())
        val uiState = _uiState.asStateFlow()

        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.d("MainViewModel", "Network available: ${network?.networkHandle}")
                    onNetworkChanged()
                }

                override fun onLost(network: Network) {
                    android.util.Log.d("MainViewModel", "Network lost: ${network?.networkHandle}")
                    onNetworkChanged()
                }
            }

        private fun onNetworkChanged() {
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                android.util.Log.d("MainViewModel", "Refreshing network info")
                loadNetworkInfo()
            }
        }

        private fun registerNetworkCallback() {
            try {
                val request =
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
                android.util.Log.d("MainViewModel", "Network callback registered")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to register network callback: ${e.message}")
            }
        }

        data class DnsDiagnostics(
            val dnsLatencyMs: Long? = null,
            val dnsSuccess: Boolean = false,
            val dnsSuccessCount: Int = 0,
            val httpLatencyMs: Long? = null,
            val httpSuccess: Boolean = false,
            val httpSuccessCount: Int = 0,
            val httpStatusCode: Int = 0,
            val isRunning: Boolean = false,
            val lastTestTime: Long = 0,
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
            // 网络信息
            val deviceIpv4: String = "-",
            val deviceIpv6: String = "-",
            val dnsMode: String = "默认",
            val proxyAddress: String = "-",
            val networkType: String = "-",
            val networkDetail: String = "-",
            // VPN 权限
            val vpnPermissionIntent: Intent? = null,
            val pendingConnectServerId: Long? = null,
            // 诊断信息
            val diagnostics: DnsDiagnostics = DnsDiagnostics(),
            // 服务器连接状态: serverId -> status
            val serverConnectionStatus: Map<Long, String?> = emptyMap(),
            // 资源监控
            val cpuUsage: String = "-",
            val javaHeapUsage: String = "-",
            val nativeHeapUsage: String = "-",
            // 会话统计 (进程生命周期累计)
            val bytesUp: Long = 0,
            val bytesDown: Long = 0,
            val connectedDurationMs: Long = 0,
        )

        init {
            observeVpnState()
            loadDefaultServer()
            loadNetworkInfo()
            observeDnsModeChanges()
            registerNetworkCallback()
            startResourceMonitoring()
            observeSessionStats()
        }

        private fun observeDnsModeChanges() {
            viewModelScope.launch {
                settingsDataStore.dnsMode.collect { dnsModeValue ->
                    _uiState.update { it.copy(dnsMode = dnsModeLabel(dnsModeValue, context)) }
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
                            defaultServerName = server?.name ?: "",
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
                val dnsModeText = dnsModeLabel(dnsModeValue, context)
                val networkDisplay = getNetworkDisplay()

                _uiState.update {
                    it.copy(
                        deviceIpv4 = ipv4,
                        deviceIpv6 = ipv6,
                        dnsMode = dnsModeText,
                        networkType = networkDisplay.first,
                        networkDetail = networkDisplay.second,
                    )
                }
            }
        }

        private fun getNetworkDisplay(): Pair<String, String> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return Pair("-", "-")
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return Pair("-", "-")

            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    try {
                        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                        @Suppress("DEPRECATION")
                        val info = wm.connectionInfo
                        val ssid = info.ssid?.replace("\"", "") ?: "-"
                        val speed = info.linkSpeed
                        val freq = info.frequency
                        val standard =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                when (info.wifiStandard) {
                                    1 -> "Wi-Fi 4"
                                    2 -> "Wi-Fi 5"
                                    3 -> "Wi-Fi 6"
                                    4 -> "Wi-Fi 7"
                                    else -> "Wi-Fi"
                                }
                            } else {
                                "Wi-Fi"
                            }
                        val parts = mutableListOf(standard)
                        if (ssid != "-" && !ssid.contains("unknown")) parts.add(0, ssid)
                        if (speed > 0) parts.add("${speed}Mbps")
                        parts.add("${freq}MHz")
                        Pair("Wi-Fi", parts.joinToString(" · "))
                    } catch (_: Exception) {
                        Pair("Wi-Fi", "Wi-Fi")
                    }
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    try {
                        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val networkType =
                            if (
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.READ_PHONE_STATE,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                tm.dataNetworkType
                            } else {
                                TelephonyManager.NETWORK_TYPE_UNKNOWN
                            }
                        val type =
                            when (networkType) {
                                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                                TelephonyManager.NETWORK_TYPE_HSDPA,
                                TelephonyManager.NETWORK_TYPE_UMTS,
                                TelephonyManager.NETWORK_TYPE_EVDO_0,
                                TelephonyManager.NETWORK_TYPE_EVDO_A,
                                -> "3G"
                                TelephonyManager.NETWORK_TYPE_GPRS,
                                TelephonyManager.NETWORK_TYPE_EDGE,
                                -> "2G"
                                else -> "移动网络"
                            }
                        Pair(type, type)
                    } catch (_: Exception) {
                        Pair("-", "-")
                    }
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Pair("VPN", "VPN")
                else -> Pair("-", "-")
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
                    val usable =
                        !networkInterface.isLoopback &&
                            networkInterface.isUp &&
                            !networkInterface.name.startsWith("tun") &&
                            !networkInterface.name.startsWith("vpn")
                    if (!usable) continue
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet6Address && !address.isLoopbackAddress) {
                            val hostAddr = address.hostAddress ?: continue
                            // 取掉 scope id (如 %wlan0)
                            val cleanAddr = hostAddr.substringBefore('%')
                            if (!cleanAddr.startsWith("fe80") && !cleanAddr.startsWith("::")) {
                                return cleanAddr
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
                launch {
                    vpnController.vpnState.collect { state ->
                        val isConnected = state.status == cn.srv0.sshinjector.domain.model.VpnState.VpnStatus.Connected
                        val serverId = state.server?.id ?: 0
                        val status = state.status.name

                        val newServerStatus =
                            if (serverId > 0) {
                                _uiState.value.serverConnectionStatus.toMutableMap().apply {
                                    keys.filter { it != serverId }.forEach { remove(it) }
                                    put(serverId, if (isConnected) "Connected" else status)
                                }
                            } else {
                                emptyMap()
                            }

                        _uiState.update {
                            it.copy(
                                isConnected = isConnected,
                                currentServer = state.server?.name ?: "未连接",
                                currentServerId = serverId,
                                currentServerHost = state.server?.host ?: "",
                                currentServerUser = state.server?.username ?: "",
                                connectionStatus = status,
                                proxyAddress = if (isConnected) "127.0.0.1:1080" else "-",
                                serverConnectionStatus = newServerStatus,
                            )
                        }

                        if (isConnected) {
                            loadNetworkInfo()
                        }
                    }
                }
            }
        }

        private fun observeSessionStats() {
            viewModelScope.launch {
                vpnController.connectionStats.collect { stats ->
                    _uiState.update {
                        it.copy(
                            bytesUp = stats.bytesReceived,
                            bytesDown = stats.bytesSent,
                        )
                    }
                }
            }
            viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val vpn = vpnController.vpnState.value
                    val connected =
                        vpn.status == cn.srv0.sshinjector.domain.model.VpnState.VpnStatus.Connected
                    val durationMs = if (connected) System.currentTimeMillis() - vpn.stats.startTime.time else 0L
                    _uiState.update { it.copy(connectedDurationMs = durationMs) }
                }
            }
        }

        private fun startResourceMonitoring() {
            viewModelScope.launch {
                android.util.Log.d("MainViewModel", "startResourceMonitoring started")
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    withContext(Dispatchers.IO) {
                        val cpu = readProcessCpuUsage()
                        readProcessMemory()
                        android.util.Log.d(
                            "MainViewModel",
                            "Resource monitoring: cpu=$cpu javaHeap=${_uiState.value.javaHeapUsage} " +
                                "nativeHeap=${_uiState.value.nativeHeapUsage}",
                        )
                        _uiState.update { it.copy(cpuUsage = cpu) }
                    }
                }
            }
        }

        private var lastCpuTime = 0L
        private var lastCpuTimeRead = 0L
        private val cpuCoreCount = Runtime.getRuntime().availableProcessors()

        private fun readProcessCpuUsage(): String {
            try {
                val cpuTime = android.os.Process.getElapsedCpuTime()
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastCpuTimeRead > 0) {
                    val processDeltaMs = cpuTime - lastCpuTime
                    val wallDeltaMs = now - lastCpuTimeRead
                    var percent = (processDeltaMs.toFloat() / wallDeltaMs.toFloat()) * 100f / cpuCoreCount
                    if (percent > CPU_PERCENT_CAP) percent = CPU_PERCENT_CAP
                    lastCpuTime = cpuTime
                    lastCpuTimeRead = now
                    return String.format(java.util.Locale.ROOT, "%4.1f%%", percent)
                }
                lastCpuTime = cpuTime
                lastCpuTimeRead = now
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "CPU read failed: ${e.message}")
            }
            return "-"
        }

        private fun readProcessMemory() {
            try {
                val rt = Runtime.getRuntime()
                val javaHeap = rt.totalMemory() - rt.freeMemory()
                val nativeHeap = Debug.getNativeHeapAllocatedSize()
                val javaHeapFormatted = formatMemorySize(javaHeap)
                val nativeHeapFormatted = formatMemorySize(nativeHeap)
                _uiState.update {
                    it.copy(javaHeapUsage = javaHeapFormatted, nativeHeapUsage = nativeHeapFormatted)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Memory read failed: ${e.message}")
                _uiState.update {
                    it.copy(javaHeapUsage = "-", nativeHeapUsage = "-")
                }
            }
        }

        companion object {
            private const val CPU_PERCENT_CAP = 99.9f
            private const val RATIO_LOW = 0.5f
            private const val RATIO_HIGH = 1.0f
            private const val NETWORK_TIMEOUT_MS = 5000

            @JvmStatic
            fun formatMemorySize(bytes: Long): String {
                return when {
                    bytes < 0 -> "-"
                    bytes < 1024L * 1024 * 1024 ->
                        String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
                    else -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
                }
            }

            @JvmStatic
            fun formatBytes(bytes: Long): String {
                return when {
                    bytes < 0 -> "-"
                    bytes < 1024 -> String.format(java.util.Locale.ROOT, "%d B", bytes)
                    bytes < 1024L * 1024 ->
                        String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
                    bytes < 1024L * 1024 * 1024 ->
                        String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
                    else -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
                }
            }

            @JvmStatic
            fun formatDuration(durationMs: Long): String {
                if (durationMs < 0) return "-"
                val totalSeconds = durationMs / 1000
                val days = totalSeconds / 86400
                val hours = (totalSeconds % 86400) / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                return if (days > 0) {
                    String.format(
                        java.util.Locale.ROOT,
                        "%dd %02d:%02d:%02d",
                        days,
                        hours,
                        minutes,
                        seconds,
                    )
                } else {
                    String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
                }
            }

            @JvmStatic
            fun ratioLevel(ratio: Float): RatioLevel {
                return when {
                    ratio < RATIO_LOW -> RatioLevel.OK
                    ratio < RATIO_HIGH -> RatioLevel.WARNING
                    else -> RatioLevel.BAD
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
                        val intent =
                            Intent(context, SshVpnService::class.java).apply {
                                action = SshVpnService.ACTION_REBUILD
                            }
                        context.startService(intent)
                    } catch (_: Exception) {
                    }
                }
                runDiagnostics()
            }
        }

        fun runDiagnostics() {
            viewModelScope.launch {
                _uiState.update { it.copy(diagnostics = DnsDiagnostics(isRunning = true)) }

                val dnsMode = settingsDataStore.dnsMode.first()
                val testCount = 5

                val dnsResults =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        (1..testCount).map { testDnsResolution(dnsMode) }
                    }
                val dnsSuccessCount = dnsResults.count { it.second }
                val dnsAvgLatency =
                    dnsResults.filter { it.second && it.first != null }
                        .mapNotNull { it.first }
                        .takeIf { it.isNotEmpty() }
                        ?.let { list -> list.sum() / list.size }

                val httpResults =
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        (1..testCount).map { testHttpConnectivity() }
                    }
                val httpSuccessCount = httpResults.count { it.second }
                val httpAvgLatency =
                    httpResults.filter { it.second && it.first != null }
                        .mapNotNull { it.first }
                        .takeIf { it.isNotEmpty() }
                        ?.let { list -> list.sum() / list.size }
                val lastHttpCode = httpResults.lastOrNull { it.second }?.third ?: 0

                _uiState.update {
                    it.copy(
                        diagnostics =
                            DnsDiagnostics(
                                dnsLatencyMs = dnsAvgLatency,
                                dnsSuccess = dnsSuccessCount > 0,
                                dnsSuccessCount = dnsSuccessCount,
                                httpLatencyMs = httpAvgLatency,
                                httpSuccess = httpSuccessCount > 0,
                                httpSuccessCount = httpSuccessCount,
                                httpStatusCode = lastHttpCode,
                                isRunning = false,
                                lastTestTime = System.currentTimeMillis(),
                            ),
                    )
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
                        connection.connectTimeout = NETWORK_TIMEOUT_MS
                        connection.readTimeout = NETWORK_TIMEOUT_MS
                        connection.setRequestProperty("Content-Type", "application/dns-message")
                        connection.setRequestProperty("Accept", "application/dns-message")
                        val query =
                            byteArrayOf(
                                0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                                0x00, 0x00, 0x00, 0x00,
                                0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6C, 0x65,
                                0x03, 0x63, 0x6F, 0x6D, 0x00,
                                0x00, 0x01, 0x00, 0x01,
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
                        socket.soTimeout = NETWORK_TIMEOUT_MS
                        val query =
                            byteArrayOf(
                                0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                                0x00, 0x00, 0x00, 0x00,
                                0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6C, 0x65,
                                0x03, 0x63, 0x6F, 0x6D, 0x00,
                                0x00, 0x01, 0x00, 0x01,
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
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
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
            val newServerStatus =
                _uiState.value.serverConnectionStatus.toMutableMap().apply {
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
            val intent =
                Intent(context, SshVpnService::class.java).apply {
                    action = SshVpnService.ACTION_CONNECT
                    putExtra(SshVpnService.EXTRA_SERVER_ID, serverId)
                }
            context.startForegroundService(intent)
        }

        fun disconnect() {
            _uiState.update { it.copy(connectionStatus = "Disconnecting") }
            val intent =
                Intent(context, SshVpnService::class.java).apply {
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

        val allServers =
            serverRepository.allServersFlow.map { servers ->
                servers.sortedWith(
                    compareByDescending<cn.srv0.sshinjector.domain.model.ServerConfig> { it.isActive }.thenBy { it.id },
                )
            }

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

        fun deleteServer(id: Long) {
            viewModelScope.launch {
                serverRepository.deleteServer(id)
            }
        }

        fun refreshNetworkInfo() {
            viewModelScope.launch {
                val ipv4 = getDeviceIpv4()
                val ipv6 = getDeviceIpv6()
                val dnsModeValue = settingsDataStore.dnsMode.first()
                val dnsModeText = dnsModeLabel(dnsModeValue, context)

                _uiState.update {
                    it.copy(
                        deviceIpv4 = ipv4,
                        deviceIpv6 = ipv6,
                        dnsMode = dnsModeText,
                    )
                }
                // 同时触发资源监控和 VPN 状态刷新
                readProcessCpuUsage()
                readProcessMemory()
                val state = vpnController.vpnState.first()
                val isConnected = state.status == cn.srv0.sshinjector.domain.model.VpnState.VpnStatus.Connected
                val serverId = state.server?.id ?: 0
                val status = state.status.name
                val newServerStatus =
                    if (serverId > 0) {
                        _uiState.value.serverConnectionStatus.toMutableMap().apply {
                            keys.filter { it != serverId }.forEach { remove(it) }
                            put(serverId, if (isConnected) "Connected" else status)
                        }
                    } else {
                        emptyMap()
                    }
                _uiState.update {
                    it.copy(
                        isConnected = isConnected,
                        currentServer = state.server?.name ?: "未连接",
                        currentServerId = serverId,
                        currentServerHost = state.server?.host ?: "",
                        currentServerUser = state.server?.username ?: "",
                        connectionStatus = status,
                        proxyAddress = if (isConnected) "127.0.0.1:1080" else "-",
                        serverConnectionStatus = newServerStatus,
                    )
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                android.util.Log.d("MainViewModel", "Network callback unregistered")
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Failed to unregister network callback: ${e.message}")
            }
        }
    }

enum class LogLevel {
    INFO,
    DEBUG,
    SUCCESS,
    ERROR,
    WARNING,
}

internal fun nextDnsMode(current: Int): Int = (current + 1) % 4

internal fun dnsModeLabel(
    mode: Int,
    context: Context? = null,
): String {
    if (context != null) {
        val resId =
            when (mode) {
                0 -> R.string.dashboard_dns_remote
                1 -> R.string.dashboard_dns_direct
                2 -> R.string.dashboard_dns_whitelist
                3 -> R.string.dashboard_dns_domain
                else -> R.string.dashboard_dns_remote
            }
        return context.getString(resId)
    }
    return when (mode) {
        0 -> "远程代理"
        1 -> "本地直连"
        2 -> "白名单模式"
        3 -> "域名分流"
        else -> "远程代理"
    }
}
