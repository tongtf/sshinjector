package com.sshinjector.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.sshinjector.R
import com.sshinjector.data.local.dao.WhitelistDao
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.domain.model.ServerConfig
import com.sshinjector.domain.model.VpnState as DomainVpnState
import com.sshinjector.domain.model.ConnectionStats
import com.sshinjector.domain.usecase.ServerRepository
import com.sshinjector.domain.usecase.VpnController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SshVpnService : VpnService() {

    @Inject lateinit var vpnController: VpnController
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var whitelistDao: WhitelistDao
    @Inject lateinit var settingsDataStore: SettingsDataStore

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunFd: java.io.FileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentServer: ServerConfig? = null
    private var notificationManager: NotificationManager? = null
    private var whitelistObserverJob: kotlinx.coroutines.Job? = null

    val serviceVpnState = MutableStateFlow<DomainVpnState>(DomainVpnState())
    val serviceConnectionStats = MutableStateFlow<ConnectionStats>(ConnectionStats())
    val lastError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        observeVpnControllerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // 必须立即启动前台服务，否则会崩溃
                val notification = buildNotification(
                    ServerConfig(
                        name = "SSHInjector",
                        host = "",
                        username = "",
                        keyAlias = ""
                    ),
                    "连接中..."
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
        scope.coroutineContext.cancelChildren()
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
            val config: ServerConfig = serverRepository.getServerById(serverId)
                ?: throw IllegalArgumentException("Server not found")
            currentServer = config

            val dnsMode = settingsDataStore.dnsMode.first()
            val allowedPackages = if (dnsMode == 2) {
                whitelistDao.getEnabledPackageNames()
            } else emptyList()

            val dnsModeText = when (dnsMode) {
                0 -> "远程代理"
                1 -> "本地直连"
                2 -> "自动模式"
                3 -> "域名分流"
                else -> "远程代理"
            }

            // 记录连接配置日志
            vpnController.addLog("目标服务器: ${config.host}:${config.port}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            vpnController.addLog("认证方式: ${if (!config.password.isNullOrEmpty()) "密码" else "密钥"}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
            vpnController.addLog("连接模式: $dnsModeText", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            vpnController.addLog("MTU: ${config.mtu} | KeepAlive: ${config.keepAliveInterval}s", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

            // 启动前台服务
            startForegroundWithNotification(config)
            vpnController.addLog("前台服务已启动", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

            // 建立 VPN 接口
            vpnController.addLog("正在创建 VPN 接口...", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            val fd = establishVpnInterface(config, allowedPackages, dnsMode)
            vpnController.addLog("VPN 接口已创建", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.SUCCESS)
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
            vpnController.addLog("连接失败: ${e.message}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
            lastError.value = e.message
            serviceVpnState.value = DomainVpnState(
                status = DomainVpnState.VpnStatus.Failed,
                error = e.message
            )
            disconnect()
        }
    }

    private fun establishVpnInterface(config: ServerConfig, allowedPackages: List<String>, dnsMode: Int): java.io.FileDescriptor {
        val builder = buildVpnBuilder(config, allowedPackages, dnsMode)
        val newVpnInterface = builder.establish()
        // 关闭旧接口 (重建场景), 避免 fd 泄漏
        try { vpnInterface?.close() } catch (_: Exception) {}
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
        val config = currentServer ?: run {
            vpnController.addLog("重建 VPN 接口失败: 无当前服务器", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
            return
        }
        try {
            val dnsMode = settingsDataStore.dnsMode.first()
            val allowedPackages = if (dnsMode == 2) {
                whitelistDao.getEnabledPackageNames()
            } else emptyList()
            vpnController.addLog("重建 VPN 接口 (模式: $dnsMode, 白名单: ${allowedPackages.size} 个应用)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            // 关闭旧 TUN 接口并更新 VpnController 的流 (由 rebuildTunInterface 处理旧流关闭)
            val fd = establishVpnInterface(config, allowedPackages, dnsMode)
            vpnController.rebuildTunInterface(fd)
            vpnController.addLog("VPN 接口已重建", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.SUCCESS)
        } catch (e: Exception) {
            vpnController.addLog("重建 VPN 接口失败: ${e.message}", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.ERROR)
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
        whitelistObserverJob = scope.launch {
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
                        vpnController.addLog("检测到白名单变化 (${packages.size} 个应用), 热更新 VPN 接口", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
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

    private fun buildVpnBuilder(config: ServerConfig, allowedPackages: List<String>, dnsMode: Int): Builder {
        val builder = Builder()
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

        vpnController.addLog("IPv4 地址: 10.0.0.1/24", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
        vpnController.addLog("IPv6 地址: fd00::1/64", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)

        when (dnsMode) {
            0 -> {
                // REMOTE 模式: 全部流量走 VPN 隧道
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                vpnController.addLog("DNS 服务器: 10.0.0.2 (REMOTE)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                vpnController.addLog("IPv4 路由: 0.0.0.0/0 (全部流量走 VPN)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
                vpnController.addLog("IPv6 路由: ::/0 (全部流量走 VPN)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            }
            1 -> {
                // SYSTEM 模式: 不添加路由，所有流量走物理网卡
                vpnController.addLog("SYSTEM 模式: 不添加路由，全部流量透传物理网卡", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            }
            2 -> {
                // WHITELIST 模式: 白名单应用走 VPN，其余透传
                // 空名单时 VpnService 未设置 allowed list 会放行全部应用进 TUN,
                // 因此只有白名单非空时才添加全量路由。
                if (allowedPackages.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                    vpnController.addLog("DNS 服务器: 10.0.0.2 (WHITELIST)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                    vpnController.addLog("IPv4 路由: 0.0.0.0/0 (白名单应用走 VPN)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
                    vpnController.addLog("IPv6 路由: ::/0 (白名单应用走 VPN)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
                } else {
                    vpnController.addLog("白名单模式: 未选择应用，全部流量透传物理网卡", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                }
            }
            3 -> {
                // DOMAIN_SPLIT 模式: 只捕获假 IP 段与 DNS, 真实 IP 流量直接走物理网卡, 避免 TUN 循环
                builder.addRoute("198.18.0.0", 15)
                builder.addRoute("fd00::", 8)
                builder.addRoute("10.0.0.2", 32)
                vpnController.addLog("DNS 服务器: 10.0.0.2 (DOMAIN_SPLIT)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                vpnController.addLog("IPv4 路由: 198.18.0.0/15 (假 IP 走隧道)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
                vpnController.addLog("IPv6 路由: fd00::/8 (假 IP 走隧道)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
                vpnController.addLog("真实 IP 流量走物理网卡直连", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
            }
        }

        if (isWhitelistMode) {
            vpnController.addLog("白名单模式: 仅允许 ${allowedPackages.size} 个应用进入 TUN", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
        } else {
            vpnController.addLog("排除应用: $packageName (自身)", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
        }

        if (dnsMode == 2 && allowedPackages.isNotEmpty()) {
            for (pkg in allowedPackages) {
                // 自身应用加入白名单会走 TUN 形成回路, 跳过
                if (pkg == packageName) {
                    vpnController.addLog("跳过自身应用加入白名单: $pkg", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.DEBUG)
                    continue
                }
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    vpnController.addLog("白名单应用不存在: $pkg", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                } catch (e: UnsupportedOperationException) {
                    vpnController.addLog("白名单应用冲突: $pkg (${e.message})", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.WARNING)
                }
            }
            vpnController.addLog("白名单路由已应用: ${allowedPackages.size} 个应用", com.sshinjector.ui.viewmodel.MainViewModel.LogLevel.INFO)
        }

        return builder
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Service",
            NotificationManager.IMPORTANCE_LOW
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

    private fun buildNotification(config: ServerConfig, status: String): Notification {
        val intent = Intent(this, com.sshinjector.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, SshVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SSHInjector")
            .setContentText("${config.name} - $status")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(
                null, "断开", disconnectPendingIntent
            ).build())
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
        try { vpnInterface?.close() } catch (_: Exception) {}
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

    companion object {
        const val ACTION_CONNECT = "com.sshinjector.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.sshinjector.ACTION_DISCONNECT"
        const val ACTION_REBUILD = "com.sshinjector.ACTION_REBUILD"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}