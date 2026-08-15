package cn.srv0.sshinjector.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.model.ConnectionStats
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.usecase.VpnController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import cn.srv0.sshinjector.domain.model.VpnState as DomainVpnState

@AndroidEntryPoint
class SshVpnService : VpnService() {
    @Inject lateinit var vpnController: VpnController

    @Inject lateinit var serverRepository: ServerRepository

    @Inject lateinit var whitelistDao: WhitelistDao

    @Inject lateinit var settingsDataStore: SettingsDataStore

    @Inject lateinit var jschSshClient: cn.srv0.sshinjector.data.remote.ssh.JschSshClient

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunFd: java.io.FileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentServer: ServerConfig? = null
    private var notificationManager: NotificationManager? = null
    private var whitelistObserverJob: kotlinx.coroutines.Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var reconnectJob: Job? = null
    private var isReconnecting = false
    private var lastNetworkId: Long = -1
    private var lastEventWasLost = false
    private val cleanupScope = CoroutineScope(Dispatchers.IO)

    val serviceVpnState = MutableStateFlow<DomainVpnState>(DomainVpnState())
    val serviceConnectionStats = MutableStateFlow<ConnectionStats>(ConnectionStats())
    val lastError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        observeVpnControllerState()
        observeJschConnectionState()
        registerNetworkCallback()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // 必须立即启动前台服务，否则会崩溃
                val notification =
                    buildNotification(
                        ServerConfig(
                            name = "SSHInjector",
                            host = "",
                            username = "",
                            keyAlias = "",
                        ),
                        "连接中...",
                    )
                // 不传递 foregroundServiceType，让系统使用 manifest 中声明的类型
                startForeground(NOTIFICATION_ID, notification)

                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1)
                scope.launch { connect(serverId) }
            }
            ACTION_DISCONNECT -> {
                scope.launch { disconnect() }
            }
            ACTION_REBUILD -> {
                scope.launch { rebuildVpnInterface() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        android.util.Log.d("SshVpnService", "onRevoke called")
        scope.launch { disconnect() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.d("SshVpnService", "onTaskRemoved called")
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        whitelistObserverJob?.cancel()
        reconnectJob?.cancel()
        scope.coroutineContext.cancelChildren()
        // 系统销毁服务时（非用户主动断开）仍持有 VPN/SSH 会话,
        // 用独立 scope 完成清理, 避免会话泄漏与服务重建后状态卡死
        cleanupScope.launch {
            try {
                vpnController.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("SshVpnService", "cleanup on destroy failed", e)
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            tunFd = null
        }
        super.onDestroy()
    }

    private suspend fun connect(serverId: Long) {
        android.util.Log.d("SshVpnService", "Connecting to server $serverId")
        if (vpnController.isVpnRunning()) {
            android.util.Log.w("SshVpnService", "VPN already running")
            return
        }
        serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connecting)

        try {
            val config: ServerConfig =
                serverRepository.getServerById(serverId)
                    ?: throw IllegalArgumentException("Server not found")
            currentServer = config

            val dnsMode = settingsDataStore.dnsMode.first()
            val allowedPackages =
                if (dnsMode == 2) {
                    whitelistDao.getEnabledPackageNames()
                } else {
                    emptyList()
                }

            // 记录连接配置日志

            // 启动前台服务
            startForegroundWithNotification(config)

            // 建立 VPN 接口
            val fd = establishVpnInterface(config, allowedPackages, dnsMode)
            vpnController.setVpnInterface(fd)

            // 设置 VPN 保护函数 (用于 SYSTEM 模式 DNS 绕过)
            vpnController.setProtectFunction { socket ->
                this.protect(socket)
            }

            // 连接 VPN 控制器
            val result = vpnController.connect(config, config.password)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Connection failed")
            }

            serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connected, server = config)
            serverRepository.setActiveServer(serverId)
            updateNotification(config)
            startWhitelistObserver()
        } catch (e: Exception) {
            lastError.value = e.message
            serviceVpnState.value =
                DomainVpnState(
                    status = DomainVpnState.VpnStatus.Failed,
                    error = e.message,
                )
            disconnect()
        }
    }

    private fun establishVpnInterface(
        config: ServerConfig,
        allowedPackages: List<String>,
        dnsMode: Int,
    ): java.io.FileDescriptor {
        val builder = buildVpnBuilder(config, allowedPackages, dnsMode)
        val newVpnInterface = builder.establish()
        // 关闭旧接口 (重建场景), 避免 fd 泄漏
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = newVpnInterface
        tunFd = vpnInterface?.fileDescriptor
        return tunFd ?: throw RuntimeException("Failed to establish VPN interface")
    }

    /**
     * 重建 VPN 接口 (白名单/连接模式热更新)
     * 重新读取白名单并重建 Builder, 仅替换 TUN 接口, SSH 隧道连接保持不断。
     */
    fun rebuildVpnInterface() {
        scope.launch { rebuildVpnInterfaceInternal() }
    }

    private suspend fun rebuildVpnInterfaceInternal() {
        val config =
            currentServer ?: run {
                return
            }
        try {
            val dnsMode = settingsDataStore.dnsMode.first()
            val allowedPackages =
                if (dnsMode == 2) {
                    whitelistDao.getEnabledPackageNames()
                } else {
                    emptyList()
                }
            // 关闭旧 TUN 接口并更新 VpnController 的流 (由 rebuildTunInterface 处理旧流关闭)
            val fd = establishVpnInterface(config, allowedPackages, dnsMode)
            vpnController.rebuildTunInterface(fd)
        } catch (e: Exception) {
            android.util.Log.e("SshVpnService", "rebuildVpnInterfaceInternal failed", e)
        }
    }

    private var whitelistObserverInitial = false
    private var rebuildInProgress = false

    /**
     * 监听白名单变化, VPN 运行中且在 WHITELIST 模式时热更新 VPN 接口。
     */
    private fun startWhitelistObserver() {
        whitelistObserverJob?.cancel()
        whitelistObserverInitial = false
        rebuildInProgress = false
        whitelistObserverJob =
            scope.launch {
                whitelistDao.getEnabled()
                    .map { list -> list.map { it.packageName }.toSet() }
                    .distinctUntilChanged()
                    .collectLatest { packages ->
                        // 跳过首次发射 (连接时已按当前白名单建立接口)
                        if (!whitelistObserverInitial) {
                            whitelistObserverInitial = true
                            return@collectLatest
                        }
                        val mode = settingsDataStore.dnsMode.first()
                        if (mode == 2 && vpnController.isVpnRunning() && !rebuildInProgress) {
                            rebuildInProgress = true
                            try {
                                rebuildVpnInterfaceInternal()
                            } finally {
                                rebuildInProgress = false
                            }
                        }
                    }
            }
    }

    private fun buildVpnBuilder(
        config: ServerConfig,
        allowedPackages: List<String>,
        dnsMode: Int,
    ): Builder {
        val builder =
            Builder()
                .setSession("SSHInjector VPN")
                .addAddress("10.0.0.1", 24)
                .addAddress("fd00::1", 64)
                .addDnsServer("10.0.0.2")
                .setMtu(config.mtu)
                .setBlocking(true)

        // 白名单模式使用 addAllowedApplication 限定允许应用, 与 addDisallowedApplication 互斥,
        // 因此该模式下不排除自身 (自身不在白名单内时自然走直连, 不进 TUN)。
        val isWhitelistMode = dnsMode == 2 && allowedPackages.isNotEmpty()
        if (!isWhitelistMode) {
            builder.addDisallowedApplication(packageName)
        }

        when (dnsMode) {
            0 -> {
                // REMOTE 模式: 全部流量走 VPN 隧道
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
            1 -> {
                // SYSTEM 模式: 不添加路由，所有流量走物理网卡
            }
            2 -> {
                // WHITELIST 模式: 白名单应用走 VPN，其余透传
                // 空名单时 VpnService 未设置 allowed list 会放行全部应用进 TUN,
                // 因此只有白名单非空时才添加全量路由。
                if (allowedPackages.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                }
            }
            3 -> {
                // DOMAIN_SPLIT 模式: 只捕获假 IP 段与 DNS, 真实 IP 流量直接走物理网卡, 避免 TUN 循环
                builder.addRoute("198.18.0.0", 15)
                builder.addRoute("fd00::", 8)
                builder.addRoute("10.0.0.2", 32)
            }
        }

        if (dnsMode == 2 && allowedPackages.isNotEmpty()) {
            for (pkg in allowedPackages) {
                // 自身应用加入白名单会走 TUN 形成回路, 跳过
                if (pkg == packageName) {
                    continue
                }
                try {
                    builder.addAllowedApplication(pkg)
                } catch (ignored: android.content.pm.PackageManager.NameNotFoundException) {
                } catch (ignored: UnsupportedOperationException) {
                }
            }
        }

        return builder
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "SSHInjector VPN 连接状态"
                setShowBadge(false)
            }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(config: ServerConfig) {
        val notification = buildNotification(config, "连接中...")
        // 不传递 foregroundServiceType，让系统使用 manifest 中声明的类型
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(config: ServerConfig) {
        val notification = buildNotification(config, "已连接")
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        config: ServerConfig,
        status: String,
    ): Notification {
        val intent =
            Intent(this, cn.srv0.sshinjector.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        val disconnectIntent =
            Intent(this, SshVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        val disconnectPendingIntent =
            PendingIntent.getService(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SSHInjector")
            .setContentText("${config.name} - $status")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "断开",
                    disconnectPendingIntent,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    private suspend fun disconnect() {
        android.util.Log.d("SshVpnService", "Starting disconnect...")

        // 0. 停止白名单观察者
        whitelistObserverJob?.cancel()
        whitelistObserverJob = null

        // 0. 清除所有服务器的激活状态
        serverRepository.deactivateAllServers()

        // 1. 断开 VPN 控制器 (如果正在运行)
        if (vpnController.isVpnRunning()) {
            android.util.Log.d("SshVpnService", "Disconnecting VPN controller...")
            vpnController.disconnect()
        }

        // 2. 关闭 VPN 接口
        android.util.Log.d("SshVpnService", "Closing VPN interface...")
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        tunFd = null

        // 3. 清理状态
        currentServer = null
        serviceVpnState.value = DomainVpnState()

        // 4. 取消通知
        android.util.Log.d("SshVpnService", "Cancelling notification...")
        notificationManager?.cancel(NOTIFICATION_ID)

        // 5. 停止前台服务
        android.util.Log.d("SshVpnService", "Stopping foreground service...")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        android.util.Log.d("SshVpnService", "Disconnect completed")
    }

    private fun observeVpnControllerState() {
        scope.launch {
            vpnController.vpnState.collect { state ->
                serviceVpnState.value = state
                state.error?.let { lastError.value = it }
            }
        }
        scope.launch {
            vpnController.connectionStats.collect { stats ->
                serviceConnectionStats.value = stats
            }
        }
    }

    /**
     * SSH 会话池全部失败时联动: 置 Failed 后触发整体自动重连 (复用网络切换的 autoReconnect)。
     * 避免 UI 停留在 Connected 而隧道实际已死。
     */
    private fun observeJschConnectionState() {
        scope.launch {
            jschSshClient.connectionState.collect { state ->
                val poolFailed = state == cn.srv0.sshinjector.data.remote.ssh.JschSshClient.ConnectionState.Failed
                val canReconnect = vpnController.isVpnRunning() && !isReconnecting && currentServer != null
                if (poolFailed && canReconnect) {
                    android.util.Log.w("SshVpnService", "SSH session pool failed, auto reconnecting")
                    autoReconnect()
                }
            }
        }
    }

    /**
     * 监听底层物理网络的变化 (如 WiFi ↔ 5G 切换)。
     * 底层网络切换会中断 SSH TCP 连接, 需要自动重连。
     * 使用显式 NetworkRequest 且排除 VPN 网络 (NOT_VPN), 确保监听到物理网络切换
     * 而非 VPN 自身建立的 TUN 网络。
     *
     * 注意: 只监听 onLost/onAvailable (网络真正消失或新网络出现) 来判定切换,
     * 不监听 onCapabilitiesChanged, 因为同一网络的能力变化 (信号/带宽抖动) 会
     * 频繁回调, 导致无谓的反复重连, 严重拖慢网速。
     */
    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(ConnectivityManager::class.java)
            val request =
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("SshVpnService", "Failed to register network callback: ${e.message}")
        }
    }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = handleNetworkEvent(network, isLost = false)

            override fun onLost(network: Network) = handleNetworkEvent(network, isLost = true)
        }

    /**
     * 处理网络事件。去重以 (网络id, 事件类型) 为键:
     * 同一网络的 onAvailable/onLost 是不同事件, 必须都放行;
     * 仅对同一网络的相同类型重复事件去重 (如注册时对已存在网络的 onAvailable)。
     */
    private fun handleNetworkEvent(
        network: Network?,
        isLost: Boolean,
    ) {
        val id =
            try {
                network?.networkHandle ?: -1L
            } catch (_: Exception) {
                -1L
            }
        // 跳过 VPN 自身的 TUN 网络
        val caps =
            try {
                connectivityManager?.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        android.util.Log.d(
            "SshVpnService",
            "Network event: id=$id lost=$isLost last=$lastNetworkId lastLost=$lastEventWasLost " +
                "running=${vpnController.isVpnRunning()}, reconnecting=$isReconnecting",
        )
        // 同一网络 + 同一事件类型的重复事件不触发 (去抖已覆盖时序)
        if (id == lastNetworkId && isLost == lastEventWasLost) return
        lastNetworkId = id
        lastEventWasLost = isLost
        // 仅在 VPN 运行且未在重连时, 网络切换触发去抖重连
        if (!vpnController.isVpnRunning() || isReconnecting) return
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(NETWORK_RECONNECT_DEBOUNCE_MS)
                if (vpnController.isVpnRunning() && !isReconnecting && currentServer != null) {
                    android.util.Log.d("SshVpnService", "Triggering auto reconnect after debounce")
                    autoReconnect()
                }
            }
    }

    /**
     * 网络切换后的自动重连: 重建 VPN 接口并恢复隧道连接。
     */
    private suspend fun autoReconnect() {
        val config = currentServer ?: return
        if (isReconnecting) return
        isReconnecting = true
        android.util.Log.d("SshVpnService", "Network changed, auto reconnecting to ${config.name}")
        serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connecting, server = config)

        try {
            // 1. 关闭旧隧道与接口
            if (vpnController.isVpnRunning()) {
                vpnController.disconnect()
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            tunFd = null

            // 2. 重建接口并重连
            val dnsMode = settingsDataStore.dnsMode.first()
            val allowedPackages =
                if (dnsMode == 2) {
                    whitelistDao.getEnabledPackageNames()
                } else {
                    emptyList()
                }
            val fd = establishVpnInterface(config, allowedPackages, dnsMode)
            vpnController.setVpnInterface(fd)
            vpnController.setProtectFunction { socket -> this.protect(socket) }

            val result = vpnController.connect(config, config.password)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Reconnect failed")
            }
            serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connected, server = config)
            updateNotification(config)
            startWhitelistObserver()
            lastError.value = null
            android.util.Log.d("SshVpnService", "Auto reconnect succeeded to ${config.name}")
        } catch (e: Exception) {
            lastError.value = e.message
            serviceVpnState.value =
                DomainVpnState(
                    status = DomainVpnState.VpnStatus.Failed,
                    error = e.message,
                )
            disconnect()
        } finally {
            isReconnecting = false
        }
    }

    companion object {
        const val ACTION_CONNECT = "cn.srv0.sshinjector.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "cn.srv0.sshinjector.ACTION_DISCONNECT"
        const val ACTION_REBUILD = "cn.srv0.sshinjector.ACTION_REBUILD"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val NETWORK_RECONNECT_DEBOUNCE_MS = 2000L
    }
}
