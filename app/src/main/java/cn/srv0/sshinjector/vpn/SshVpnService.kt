package cn.srv0.sshinjector.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.domain.model.ConnectStage
import cn.srv0.sshinjector.domain.model.ConnectionStats
import cn.srv0.sshinjector.domain.model.HealthStep
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.ConnectivityProber
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_DOMAIN_SPLIT
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_REMOTE
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_SYSTEM
import cn.srv0.sshinjector.domain.vpn.DNS_MODE_WHITELIST
import cn.srv0.sshinjector.domain.vpn.HealthTracker
import cn.srv0.sshinjector.domain.vpn.VpnNetwork
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.ui.StatusDisplay
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import cn.srv0.sshinjector.domain.model.VpnState as DomainVpnState

@AndroidEntryPoint
class SshVpnService : VpnService() {
    @Inject lateinit var vpnController: VpnController

    @Inject lateinit var serverRepository: ServerRepository

    @Inject lateinit var settingsDataStore: SettingsDataStore

    @Inject lateinit var jschSshClient: cn.srv0.sshinjector.data.remote.ssh.JschSshClient

    @Inject lateinit var tunnelManager: TunnelManager

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private var tunFd: java.io.FileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentServer: ServerConfig? = null
    private var notificationManager: NotificationManager? = null
    private var whitelistObserverJob: kotlinx.coroutines.Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var reconnectJob: Job? = null

    // 重连互斥标志: compareAndSet 原子占位 (旧版 @Volatile check-then-set 有竞态,
    // 池失败/网络切换/解锁三个触发源可同时通过检查), 读取仍走 isReconnecting 属性
    private val reconnecting = AtomicBoolean(false)
    private val isReconnecting: Boolean get() = reconnecting.get()

    // 池失败触发的重连已排队/进行中: 该失败的收尾由重连负责, vpnState=Failed 的
    // 观察者据此跳过 disconnect — 否则两个协程竞态, disconnect 拆掉刚重建的会话
    @Volatile private var poolFailReconnectPending = false

    @Volatile private var lastNetworkId: Long = -1

    // F11: 上次观测到的默认网络 id — onLost 时 activeNetwork 可能已切换,
    // 用它兜住"默认网络自己丢失"的事件
    @Volatile private var lastActiveNetworkId: Long = -1

    @Volatile private var lastEventWasLost = false

    @Volatile private var lastPoolFailReconnectAt = 0L

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
        registerUnlockReconnectReceiver()
    }

    /**
     * 监听系统解锁广播:用户通过生物识别/锁屏密码鉴权后,若 SSH 连接已断
     * (hasUnhealthySession),立即触发一次重连。
     * 解决『锁屏期 Keystore 拒签导致重连失败』后的即时恢复 —— 无需等待 keepAlive 周期，
     * 也绕过 isConnectedFlag 在假连接状态下不会置 false、UI 一直显示已连接的问题。
     */
    private val unlockReconnectReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action != Intent.ACTION_USER_PRESENT) return
                try {
                    // 解锁后先补一次端到端探测 (锁屏期隧道可能已死但未被 keepAlive 发现)
                    triggerHealthProbeNow()
                    if (jschSshClient.hasUnhealthySession()) {
                        Log.d("SshVpnService", "ACTION_USER_PRESENT: ssh unhealthy, reconnecting")
                        scope.launch { autoReconnect() }
                    }
                } catch (e: Exception) {
                    Log.e("SshVpnService", "ACTION_USER_PRESENT handler failed", e)
                    VpnController.appLogThrottled(
                        "解锁重连处理失败 — ${e::class.simpleName}:${e.message}",
                        level = LogLevel.WARNING,
                        throttleKey = "解锁重连处理失败",
                    )
                }
            }
        }

    private fun registerUnlockReconnectReceiver() {
        try {
            registerReceiver(unlockReconnectReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (e: Exception) {
            Log.e("SshVpnService", "Failed to register ACTION_USER_PRESENT receiver", e)
            VpnController.appLogThrottled(
                "解锁重连接收器注册失败 — ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "解锁接收器注册失败",
            )
        }
    }

    // ---- 连通性健康监测 (spec: 2026-09-30-connectivity-health) ----

    private var healthJob: Job? = null
    private val healthTracker = HealthTracker()

    /** 上次上报的故障步骤; 变化时写入应用内日志 (每 15s 评估, 不刷屏)。 */
    private var lastReportedStep: HealthStep? = null

    /**
     * 连接刚建立 → 未验证 (UI 显示"网络验证中"): 立即探测 + 15s 周期循环。
     * 探测失败只降级显示 (spec D3), 自动重连仍走解锁/网络切换/keepAlive 既有钩子。
     */
    private fun startHealthMonitor(config: ServerConfig) {
        stopHealthMonitor()
        healthTracker.reset()
        lastReportedStep = null
        vpnController.reportHealth(verified = false, failedStep = null)
        healthJob =
            scope.launch {
                while (true) {
                    runHealthProbe(config)
                    delay(HEALTH_PROBE_INTERVAL_MS)
                }
            }
    }

    private fun stopHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
    }

    /** 事件触发 (网络切换/解锁): 立即补一次探测, 不打断周期循环、不重置计数。 */
    private fun triggerHealthProbeNow() {
        val config = currentServer ?: return
        if (healthJob?.isActive != true) return
        scope.launch { runHealthProbe(config) }
    }

    private suspend fun runHealthProbe(config: ServerConfig) {
        // 分阶段健康 (TUN/DNS/转发): 探测走 loopback + 域名型 CONNECT, 探不到 TUN 路径, 二者互补
        val stageStep = vpnController.evaluateStageHealth()
        try {
            val endpoint = settingsDataStore.probeUrl.first() ?: ConnectivityProber.DEFAULT_ENDPOINT
            val prober =
                ConnectivityProber(
                    socksPort = config.socksPort,
                    socksAuth = tunnelManager.getActiveOrFallback().socksAuth,
                    endpointUrl = endpoint,
                )
            val result = prober.probe()
            // SSH 池已知不健康是直接事实: 首败即降级并归因到 SSH, 不必等探测连败凑次数
            val sshOk = jschSshClient.isConnected() && !jschSshClient.hasUnhealthySession()
            when (result) {
                is ConnectivityProber.Result.Ok -> {
                    healthTracker.onSuccess()
                    // 出口 IP: 探测成功说明通道可用, 未取回时顺带回显取一次 (会话级, 成功后不再发请求)
                    if (vpnController.vpnState.value.exitIp == null) {
                        prober.fetchExitIp()?.let { ip ->
                            vpnController.reportExitIp(ip)
                            Log.d("SshVpnService", "exit ip: $ip")
                        }
                    }
                    Log.d("SshVpnService", "health probe ok (verified)")
                }
                is ConnectivityProber.Result.Failed -> {
                    val step = if (!sshOk) HealthStep.SSH else result.step
                    healthTracker.onFailure(step, immediate = !sshOk)
                    Log.w("SshVpnService", "health probe failed: step=$step reason=${result.reason}")
                    VpnController.appLogThrottled(
                        "网络探测失败 · ${getString(step.labelRes)} — reason: ${result.reason}",
                        level = LogLevel.WARNING,
                        throttleKey = "网络探测失败",
                    )
                }
            }
            reportMergedHealth(stageStep)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 探测自身异常 (如读配置失败) 不影响连接, 只记日志; 分阶段归因仍上报
            Log.e("SshVpnService", "health probe error: ${e.message}")
            VpnController.appLogThrottled(
                "健康探测自身异常 — ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "健康探测自身异常",
            )
            if (stageStep != null) reportMergedHealth(stageStep)
        }
    }

    /**
     * 合并上报: 分阶段故障优先于探测归因 (两者都无则健康)。
     * verified 只有在探测通过且分阶段无故障时才为 true — 状态卡以此区分
     * 「已连接 / 网络验证中 / 连接异常 · <步骤>」。步骤变化时同步写应用内日志
     * (logcat 之外用户唯一可见的诊断面), 便于无 adb 现场归因。
     */
    private fun reportMergedHealth(stageStep: HealthStep?) {
        val failedStep = stageStep ?: healthTracker.failedStep
        val verified = healthTracker.verified && stageStep == null
        if (failedStep != lastReportedStep) {
            if (failedStep == null) {
                vpnController.addLog("网络健康已恢复", LogLevel.SUCCESS)
            } else {
                val source = if (stageStep != null) "分阶段" else "端到端探测"
                vpnController.addLog(
                    "连接异常 · ${getString(failedStep.labelRes)} (来源: $source)",
                    LogLevel.WARNING,
                )
            }
            lastReportedStep = failedStep
        }
        vpnController.reportHealth(verified, failedStep)
    }

    /** 连接期失败 → 步骤归因 (spec D4): 按异常消息映射, 与运行期探测共用 HealthStep。 */
    private fun mapConnectFailureToStep(message: String?): HealthStep {
        val m = message ?: return HealthStep.SSH
        return when {
            m.contains("Auth fail", ignoreCase = true) -> HealthStep.AUTH
            m.contains("establish", ignoreCase = true) ||
                m.contains("interface", ignoreCase = true) ||
                m.contains("tun", ignoreCase = true) -> HealthStep.TUN
            m.contains("proxy", ignoreCase = true) ||
                m.contains("socks", ignoreCase = true) ||
                m.contains("in use", ignoreCase = true) ->
                HealthStep.PROXY
            m.contains("dns", ignoreCase = true) -> HealthStep.DNS
            else -> HealthStep.SSH
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            null -> {
                // F12-f: 系统回收后 START_STICKY 重启传 null intent —— 无 action 不做事,
                // 立即 stopSelf 防止空转重启 (也避免未 startForeground 的 5s 超时)
                stopSelf()
                return START_NOT_STICKY
            }
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
                        connectingLabel(),
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
        Log.d("SshVpnService", "onRevoke called")
        // 系统回收 VPN (非用户意图): 保留 lastServerId, 重启后仍可续连
        scope.launch { disconnect(userInitiated = false) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("SshVpnService", "onTaskRemoved called")
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        try {
            unregisterReceiver(unlockReconnectReceiver)
        } catch (_: Exception) {
        }
        whitelistObserverJob?.cancel()
        reconnectJob?.cancel()
        scope.cancel()
        // 系统销毁服务时（非用户主动断开）仍持有 VPN/SSH 会话,
        // 用独立 scope 完成清理, 避免会话泄漏与服务重建后状态卡死
        cleanupScope.launch {
            try {
                vpnController.disconnect()
            } catch (e: Exception) {
                Log.e("SshVpnService", "cleanup on destroy failed", e)
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            tunFd = null
            cleanupScope.cancel()
        }
        super.onDestroy()
    }

    // F12-g: 所有会话变更入口 (connect/disconnect/autoReconnect/rebuild) 串行化。
    // 死锁纪律: 已持锁的路径只能调 *Internal 变体 (connect 的 catch → disconnectInternal,
    // autoReconnect 的 catch → disconnectInternal) — Mutex 不可重入, 锁内再拿锁必死锁。
    private val connectMutex = Mutex()

    private suspend fun connect(serverId: Long) {
        connectMutex.withLock {
            Log.d("SshVpnService", "Connecting to server $serverId")
            if (vpnController.isVpnRunning()) {
                Log.w("SshVpnService", "VPN already running")
                return
            }
            serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connecting)

            try {
                // 配置加载段: getServerById/merge/白名单查询都在此阶段 (通知由 currentServer 门保护, 状态卡直接显示)
                vpnController.reportConnectStage(ConnectStage.LOAD)
                val config: ServerConfig =
                    serverRepository.getServerById(serverId)
                        ?: throw IllegalArgumentException("Server not found")
                // 合并全局设置 (mtu/keepAlive/enableIPv6 全局优先) — 初次连接也必须用合并后的配置,
                // 否则全局覆盖只在重建/重连路径生效 (S5 的 IPv6 路由依赖它)
                val merged = mergeGlobalSettings(config)
                currentServer = merged

                val (dnsMode, allowedPackages) = whitelistSelection()
                // 供 VpnController 决定 DNS 传输策略 (空名单 → 不劫持 DNS, 见 dnsTransportFor)
                vpnController.setWhitelistPackages(allowedPackages, whitelistEnabled = dnsMode == DNS_MODE_WHITELIST)

                // 启动前台服务
                startForegroundWithNotification(merged)

                // 建立 VPN 接口 (先报阶段: 状态卡/通知从 TUN 建立起显示细分流程)
                vpnController.reportConnectStage(ConnectStage.TUN)
                val fd = establishVpnInterface(merged, allowedPackages, dnsMode)
                vpnController.setVpnInterface(fd)

                // 设置 VPN 保护函数 (用于 SYSTEM 模式 DNS 绕过)
                vpnController.setProtectFunction { socket ->
                    this.protect(socket)
                }
                // F1: TCP 用户态直连的 protect (必须在 connect 前注入, 否则直连流量回环进 TUN)
                vpnController.setProtectTcpFunction { socket -> this.protect(socket) }
                // 连接 VPN 控制器
                val result = vpnController.connect(merged, merged.password)
                if (result.isFailure) {
                    throw result.exceptionOrNull() ?: Exception("Connection failed")
                }

                serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connected, server = merged)
                serverRepository.setActiveServer(serverId)
                // 记录最后连接的服务器, 供开机自启 (BootReceiver) 使用
                settingsDataStore.setLastServerId(serverId)
                startHealthMonitor(merged)
                updateNotification(merged, StatusDisplay.build(serviceVpnState.value, this::getString))
                startWhitelistObserver()
            } catch (e: Exception) {
                lastError.value = e.message
                serviceVpnState.value =
                    DomainVpnState(
                        status = DomainVpnState.VpnStatus.Failed,
                        error = e.message,
                    )
                disconnectInternal(userInitiated = false)
                // 断开清理不抹健康字段 (controller 侧保留), 失败归因在断开后补写, 供 UI 显示"连接失败 · <步骤>"
                vpnController.reportHealth(verified = false, failedStep = mapConnectFailureToStep(e.message))
            }
        }
    }

    /**
     * 合并全局网络设置 (全局优先, 未设置回退 per-server 字段)。
     * mtu/keepAlive 以 0/null 表示未设置, enableIPv6 以 null 表示未设置。
     */
    private suspend fun mergeGlobalSettings(config: ServerConfig): ServerConfig {
        val gMtu = settingsDataStore.mtu.first()
        val gKeepAlive = settingsDataStore.keepAlive.first()
        val gIpv6 = settingsDataStore.enableIPv6.first()
        return config.copy(
            mtu = gMtu ?: config.mtu,
            keepAliveInterval = gKeepAlive ?: config.keepAliveInterval,
            enableIPv6 = gIpv6 ?: config.enableIPv6,
        )
    }

    /**
     * 当前 dnsMode + 启用白名单应用 (WHITELIST 模式外恒为空)。
     * connect / rebuild / autoReconnect 三处共用; 调用方自行决定与 establish 的先后顺序
     * (rebuild 必须先 establish 再 setWhitelistPackages, 见 rebuildVpnInterfaceInternal 注释)。
     */
    private suspend fun whitelistSelection(): Pair<Int, List<String>> {
        val dnsMode = settingsDataStore.dnsMode.first()
        val packages =
            if (dnsMode == DNS_MODE_WHITELIST) {
                serverRepository.getEnabledPackageNames()
            } else {
                emptyList()
            }
        return dnsMode to packages
    }

    private fun establishVpnInterface(
        config: ServerConfig,
        allowedPackages: List<String>,
        dnsMode: Int,
    ): java.io.FileDescriptor {
        val builder = buildVpnBuilder(config, allowedPackages, dnsMode)
        // F12-i: 先判 establish 结果再换 — 失败时旧 TUN 必须保留,
        // 否则 VpnController 的 inputStream 指向已关闭 fd → packetLoop 忙循环
        val newVpnInterface =
            builder.establish() ?: throw RuntimeException("Failed to establish VPN interface")
        val newFd =
            newVpnInterface.fileDescriptor
                ?: run {
                    try {
                        newVpnInterface.close()
                    } catch (_: Exception) {
                    }
                    throw RuntimeException("Failed to establish VPN interface")
                }
        // 成功才关闭旧接口 (重建场景), 避免 fd 泄漏
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = newVpnInterface
        tunFd = newFd
        return newFd
    }

    /**
     * 重建 VPN 接口 (白名单/连接模式热更新)
     * 重新读取白名单并重建 Builder, 仅替换 TUN 接口, SSH 隧道连接保持不断。
     */
    fun rebuildVpnInterface() {
        scope.launch {
            connectMutex.withLock { rebuildVpnInterfaceInternal() }
        }
    }

    private suspend fun rebuildVpnInterfaceInternal() {
        val config =
            currentServer ?: run {
                return
            }
        try {
            val (dnsMode, allowedPackages) = whitelistSelection()
            // 先建新接口、成功后再套用 DNS 策略: 顺序反过来时若 establish 抛异常,
            // 策略已切到新状态而 TUN 还是旧的 —— 空名单翻成非空的瞬间假 IP 没有路由,
            // 正是 DnsTransportPolicy 注释里那个 "DNS_PROBE_FINISHED_NO_INTERNET" 黑洞。
            // establishVpnInterface 只依赖入参 (allowedPackages/dnsMode), 不读 controller 状态。
            val fd = establishVpnInterface(config, allowedPackages, dnsMode)
            // 白名单增删会翻转"空↔非空" → DNS 策略跟着变 (空名单不得劫持 DNS), 与路由变更同步重算
            vpnController.setWhitelistPackages(allowedPackages, whitelistEnabled = dnsMode == DNS_MODE_WHITELIST)
            vpnController.updateDnsMode()
            // 关闭旧 TUN 接口并更新 VpnController 的流 (由 rebuildTunInterface 处理旧流关闭)
            vpnController.rebuildTunInterface(fd)
        } catch (e: Exception) {
            Log.e("SshVpnService", "rebuildVpnInterfaceInternal failed", e)
            // 只写 logcat 等于没报: 设备上没有 adb, 半应用状态必须进应用内日志
            VpnController.appLogThrottled(
                "VPN 接口重建失败 · ${e::class.simpleName}: ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "VPN 接口重建失败",
            )
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
                serverRepository.enabledWhitelistFlow
                    .map { list -> list.map { it.packageName }.toSet() }
                    .distinctUntilChanged()
                    .collectLatest { packages ->
                        // 跳过首次发射 (连接时已按当前白名单建立接口)
                        if (!whitelistObserverInitial) {
                            whitelistObserverInitial = true
                            return@collectLatest
                        }
                        val mode = settingsDataStore.dnsMode.first()
                        if (mode == DNS_MODE_WHITELIST && vpnController.isVpnRunning() && !rebuildInProgress) {
                            rebuildInProgress = true
                            try {
                                // 与 connect/disconnect/autoReconnect 串行: 热重建 TUN
                                // 不能与断开/重连并发 (会把新 fd 装进已拆掉的会话)
                                connectMutex.withLock { rebuildVpnInterfaceInternal() }
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
                .addAddress(VpnNetwork.TUN_GATEWAY, VpnNetwork.TUN_PREFIX_LEN)
                .addDnsServer(VpnNetwork.TUN_IP)
                .setMtu(config.mtu)
                .setBlocking(true)

        if (config.enableIPv6) {
            builder.addAddress(VpnNetwork.IPV6_GATEWAY, VpnNetwork.IPV6_PREFIX_LEN)
        }

        // 白名单模式使用 addAllowedApplication 限定允许应用, 与 addDisallowedApplication 互斥,
        // 因此该模式下不排除自身 (自身不在白名单内时自然走直连, 不进 TUN)。
        val isWhitelistMode = dnsMode == DNS_MODE_WHITELIST && allowedPackages.isNotEmpty()
        if (!isWhitelistMode) {
            builder.addDisallowedApplication(packageName)
        }

        Log.d("SshVpnService", "buildVpnBuilder: dnsMode=$dnsMode allowedPackages=${allowedPackages.size}")
        when (dnsMode) {
            DNS_MODE_REMOTE -> {
                // REMOTE 模式: 全部流量走 VPN 隧道
                builder.addRoute("0.0.0.0", 0)
                // S5: IPv6 关闭也捕获 ::/0 后在 TUN 内丢弃 —— 关闭 = 不用 IPv6, 而非逃逸物理网卡
                builder.addRoute("::", 0)
            }
            DNS_MODE_SYSTEM -> {
                // SYSTEM 模式: 不添加路由，所有流量走物理网卡
            }
            DNS_MODE_WHITELIST -> {
                // WHITELIST 模式: 白名单应用走 VPN，其余透传
                // 空名单时 VpnService 未设置 allowed list 会放行全部应用进 TUN,
                // 因此只有白名单非空时才添加全量路由。
                if (allowedPackages.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                }
            }
            DNS_MODE_DOMAIN_SPLIT -> {
                // DOMAIN_SPLIT 模式: 只捕获假 IP 段与 DNS, 真实 IP 流量直接走物理网卡, 避免 TUN 循环
                builder.addRoute(VpnNetwork.FAKE_V4_ROUTE, VpnNetwork.FAKE_V4_PREFIX_LEN)
                if (config.enableIPv6) {
                    builder.addRoute(VpnNetwork.FAKE_V6_ROUTE, VpnNetwork.FAKE_V6_PREFIX_LEN)
                } else {
                    // S5: 关闭 IPv6 → 捕获全部 v6 后丢弃 (无 fd00 假 IP 时真实 v6 不得逃逸)
                    builder.addRoute("::", 0)
                }
                builder.addRoute(VpnNetwork.TUN_IP, 32)
            }
        }

        if (dnsMode == DNS_MODE_WHITELIST && allowedPackages.isNotEmpty()) {
            for (pkg in allowedPackages) {
                // 自身应用加入白名单会走 TUN 形成回路, 跳过
                if (pkg == packageName) {
                    continue
                }
                try {
                    builder.addAllowedApplication(pkg)
                } catch (ignored: android.content.pm.PackageManager.NameNotFoundException) {
                    VpnController.appLogThrottled(
                        "白名单应用添加失败 · $pkg — 未安装或对 VPN 不可见, 该应用将直连",
                        level = LogLevel.WARNING,
                        throttleKey = "白名单应用添加失败",
                    )
                } catch (ignored: UnsupportedOperationException) {
                    VpnController.appLogThrottled(
                        "白名单应用添加失败 · $pkg — 设备不支持, 该应用将直连",
                        level = LogLevel.WARNING,
                        throttleKey = "白名单应用添加失败",
                    )
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
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(config: ServerConfig) {
        val notification = buildNotification(config, connectingLabel())
        // 不传递 foregroundServiceType，让系统使用 manifest 中声明的类型
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 通知栏文案一律经 [StatusDisplay] (与状态卡同源): 这里曾各写各的 `status_connecting`/
     * `status_verifying`/`status_connected`, 连接阶段一变 (LOAD/TUN/TUNNEL/DNS/ROUTES)
     * 通知栏就停在"正在连接"与状态卡对不上。[connectingLabel] 只用于**尚无任何状态**的
     * 前台启动瞬间 (此时没有 state 可渲染), 语义 = StatusDisplay 的 Connecting 分支。
     */
    private fun connectingLabel(): String =
        StatusDisplay.build(
            DomainVpnState(status = DomainVpnState.VpnStatus.Connecting),
            this::getString,
        )

    private fun updateNotification(
        config: ServerConfig,
        status: String,
    ) {
        val notification = buildNotification(config, status)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /** 状态变化 → 通知栏文案跟随 (连接阶段 / 已连接 / 网络验证中 / 连接异常 · <步骤>)。 */
    private fun updateHealthNotification(state: DomainVpnState) {
        // 门基于状态而非 isVpnRunning: TUN 阶段 (reportConnectStage) 发生在 isRunning=true 之前
        if (state.status == DomainVpnState.VpnStatus.Disconnected ||
            state.status == DomainVpnState.VpnStatus.Failed ||
            state.status == DomainVpnState.VpnStatus.Disconnecting
        ) {
            return
        }
        val config = currentServer ?: return
        val label = StatusDisplay.build(state, this::getString)
        updateNotification(config, label)
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

        return Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("SSHInjector")
            .setContentText("${config.name} - $status")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        getString(R.string.notification_action_disconnect),
                        disconnectPendingIntent,
                    ).build(),
            ).setOngoing(true)
            .build()
    }

    /** 会话断开入口 (事件源: ACTION_DISCONNECT/onRevoke/onTaskRemoved/失败观察者): 持锁串行。 */
    private suspend fun disconnect(userInitiated: Boolean = true) {
        connectMutex.withLock { disconnectInternal(userInitiated) }
    }

    /**
     * 断开实现。**只允许已持 connectMutex 的路径调用** (connect/autoReconnect 的 catch,
     * 或 disconnect 持锁包装器内部) — 自己再拿锁会自死锁 (Mutex 不可重入)。
     *
     * @param userInitiated true = 用户主动断开 (ACTION_DISCONNECT/onTaskRemoved):
     *   清除激活状态与 lastServerId, 开机自启不再触发。false = 连接失败/系统回收等
     *   非用户意图的清理, 保留 lastServerId 供重启后 BootReceiver 续连。
     */
    private suspend fun disconnectInternal(userInitiated: Boolean = true) {
        Log.d("SshVpnService", "Starting disconnect... userInitiated=$userInitiated")

        // 0. 停止白名单观察者与健康监测; 用户主动断开时清除健康归因
        //    (失败路径的归因由调用方在 disconnect 返回后补写, 不在此抹掉)
        whitelistObserverJob?.cancel()
        whitelistObserverJob = null
        stopHealthMonitor()

        if (userInitiated) {
            vpnController.reportHealth(verified = false, failedStep = null)
            // 0. 清除所有服务器的激活状态
            serverRepository.deactivateAllServers()
            // 用户主动断开: 清除最后连接记录, 开机自启不再触发
            settingsDataStore.setLastServerId(0)
        }

        // 1. 断开 VPN 控制器 (如果正在运行)
        if (vpnController.isVpnRunning()) {
            Log.d("SshVpnService", "Disconnecting VPN controller...")
            vpnController.disconnect()
        }

        // 2. 关闭 VPN 接口
        Log.d("SshVpnService", "Closing VPN interface...")
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
        Log.d("SshVpnService", "Cancelling notification...")
        notificationManager?.cancel(NOTIFICATION_ID)

        // 5. 停止前台服务
        Log.d("SshVpnService", "Stopping foreground service...")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d("SshVpnService", "Disconnect completed")
    }

    /**
     * Failed 观察者是否应执行断开清理。三个跳过条件:
     * 1. 池失败已有重连排队 — 该失败的收尾由重连负责;
     * 2. 重连进行中 — 失败观察与重连的触发顺序不确定, 此时 disconnect 会拆掉刚重建的会话;
     * 3. 已无可清理资源 — connect 自己的 catch 已清理过, 再跑一遍会把 Failed 状态复位成 Disconnected。
     */
    private fun shouldCleanupOnFailure(): Boolean {
        if (poolFailReconnectPending || isReconnecting) return false
        return vpnController.isVpnRunning() || vpnInterface != null
    }

    private fun observeVpnControllerState() {
        scope.launch {
            vpnController.vpnState.collect { state ->
                // collect 体里任何一处抛异常都会取消本协程 → 状态镜像与 Failed 兜底**永久失联**
                // (UI 卡在最后一帧、连接失败不再自动清理)。单帧失败只记日志, 下一帧继续收。
                try {
                    serviceVpnState.value = state
                    state.error?.let { lastError.value = it }
                    updateHealthNotification(state)
                    // F12-i 兜底: packetLoop 连续读失败已退出 (状态 Failed) → 完整清理资源;
                    // disconnect(false) 保留 lastServerId 供重连。幂等, 与 connect catch 不冲突。
                    if (state.status == DomainVpnState.VpnStatus.Failed && shouldCleanupOnFailure()) {
                        disconnect(userInitiated = false)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportObserverFailure("vpnState", e)
                }
            }
        }
        scope.launch {
            vpnController.connectionStats.collect { stats ->
                try {
                    serviceConnectionStats.value = stats
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportObserverFailure("connectionStats", e)
                }
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
                val now = System.currentTimeMillis()
                // SSH 池失败重连带退避窗口: 服务器持续不可达时避免无限快速重连风暴
                val backoffElapsed = now - lastPoolFailReconnectAt >= POOL_FAIL_RECONNECT_BACKOFF_MS
                try {
                    if (poolFailed && canReconnect && backoffElapsed) {
                        lastPoolFailReconnectAt = now
                        Log.w("SshVpnService", "SSH session pool failed, auto reconnecting")
                        // 标记本次失败的收尾由重连负责 + 捕获配置: vpnState=Failed 的观察者
                        // (与本观察者触发顺序不确定) 据此跳过 disconnect, 不拆刚重建的会话
                        poolFailReconnectPending = true
                        autoReconnect(currentServer)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 这里一抛, 以后 SSH 池再失败就没有任何东西去触发重连了
                    reportObserverFailure("jschConnectionState", e)
                }
            }
        }
    }

    /**
     * 观察协程单帧失败的统一出口: 记 logcat + 应用内日志, **只吞不抛**。
     * 抛出会取消整条 collector —— 状态镜像/池失败重连从此再也不触发, 且没有任何可见迹象。
     * [CancellationException] 不走这里 (由调用方 rethrow, 否则连协程取消都会被吞)。
     */
    private fun reportObserverFailure(
        source: String,
        e: Exception,
    ) {
        Log.e("SshVpnService", "$source observer failed: ${e.message}", e)
        VpnController.appLogThrottled(
            "状态观察异常 · $source — ${e::class.simpleName}: ${e.message} (循环继续)",
            level = LogLevel.WARNING,
            throttleKey = "状态观察异常",
        )
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
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            // F11: 注册时对已存在的网络会立即回调 onAvailable — 预置去重键与默认网络 id,
            // 抑制首个自触发事件
            val initialId = currentActiveNetworkId()
            lastActiveNetworkId = initialId
            lastNetworkId = initialId
            lastEventWasLost = false
        } catch (e: Exception) {
            Log.e("SshVpnService", "Failed to register network callback: ${e.message}")
            VpnController.appLogThrottled(
                "网络切换监听注册失败 — 网络切换将不会触发重连 (${e.message})",
                level = LogLevel.WARNING,
                throttleKey = "网络回调注册失败",
            )
        }
    }

    private fun currentActiveNetworkId(): Long =
        try {
            connectivityManager?.activeNetwork?.networkHandle ?: -1L
        } catch (_: Exception) {
            -1L
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
        Log.d(
            "SshVpnService",
            "Network event: id=$id lost=$isLost last=$lastNetworkId lastLost=$lastEventWasLost " +
                "running=${vpnController.isVpnRunning()}, reconnecting=$isReconnecting",
        )
        // F11: 仅默认网络的事件才触发重连 — WiFi+蜂窝并存时, 副网络上下线不应全量重连。
        // onLost 时 activeNetwork 可能已切换到新默认, 用 lastActiveNetworkId 兜住旧默认的丢失。
        val activeId = currentActiveNetworkId()
        if (id != activeId && id != lastActiveNetworkId) return
        if (activeId != -1L) lastActiveNetworkId = activeId
        // 同一网络 + 同一事件类型的重复事件不触发 (去抖已覆盖时序)
        if (id == lastNetworkId && isLost == lastEventWasLost) return
        lastNetworkId = id
        lastEventWasLost = isLost
        // 网络事件 → 立即补一次健康探测 (切换瞬间的可用性; 下面的重连成功后会重启监测循环)
        if (vpnController.isVpnRunning()) triggerHealthProbeNow()
        // 仅在 VPN 运行且未在重连时, 网络切换触发去抖重连
        if (!vpnController.isVpnRunning() || isReconnecting) return
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(NETWORK_RECONNECT_DEBOUNCE_MS)
                if (vpnController.isVpnRunning() && !isReconnecting && currentServer != null) {
                    Log.d("SshVpnService", "Triggering auto reconnect after debounce")
                    autoReconnect()
                }
            }
    }

    /**
     * 自动重连 (网络切换去抖 / SSH 池失败 / 解锁): 断旧 + 重建是一个原子临界区,
     * 与 connect/disconnect 共用 connectMutex 串行。调用发生在锁外、函数内部自己拿锁,
     * 所以 catch 里只能调 disconnectInternal (锁内再拿锁自死锁)。
     *
     * @param config 检测时点捕获的会话配置 — 池失败场景下 vpnState=Failed 的观察者可能
     *   先行 disconnect 把 currentServer 置空, 显式传入仍能完成重建。
     */
    private suspend fun autoReconnect(config: ServerConfig? = null) {
        if (!reconnecting.compareAndSet(false, true)) return // 已有重连在排队/进行, 复用它
        try {
            connectMutex.withLock {
                // 用户主动断开 (lastServerId 清零) → 不再重建; 失败/系统清理路径保留该值,
                // 因此连接失败重试与开机续连不受影响
                if ((settingsDataStore.lastServerId.first() ?: 0L) == 0L) {
                    Log.d("SshVpnService", "Auto reconnect skipped: user disconnected")
                    return@withLock
                }
                val base = config ?: currentServer ?: return@withLock
                // 重连时重新合并全局设置: 连接后改过全局 MTU/keepAlive/IPv6 的, 下一次重连即生效
                val cfg = mergeGlobalSettings(base)
                currentServer = cfg
                Log.d("SshVpnService", "Auto reconnecting to ${cfg.name}")
                // 拆旧/建新窗口内探测打的是已经关掉的本地代理, 结果只会把 healthTracker
                // 打成 PROXY 败; 隧道未就绪时 packetLoopActive=false 又会把 tunVerdict
                // 记成连续失败窗口 → 降级状态被带进新会话。新会话 startHealthMonitor 重开。
                // 失败分支走 disconnectInternal (本身会 stop), 幂等。
                stopHealthMonitor()
                serviceVpnState.value =
                    DomainVpnState(status = DomainVpnState.VpnStatus.Connecting, server = cfg)

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
                    val (dnsMode, allowedPackages) = whitelistSelection()
                    vpnController.reportConnectStage(ConnectStage.TUN)
                    vpnController.setWhitelistPackages(
                        allowedPackages,
                        whitelistEnabled = dnsMode == DNS_MODE_WHITELIST,
                    )
                    val fd = establishVpnInterface(cfg, allowedPackages, dnsMode)
                    vpnController.setVpnInterface(fd)
                    vpnController.setProtectFunction { socket -> this.protect(socket) }
                    vpnController.setProtectTcpFunction { socket -> this.protect(socket) }
                    val result = vpnController.connect(cfg, cfg.password)
                    if (result.isFailure) {
                        throw result.exceptionOrNull() ?: Exception("Reconnect failed")
                    }
                    serviceVpnState.value = DomainVpnState(status = DomainVpnState.VpnStatus.Connected, server = cfg)
                    startHealthMonitor(cfg)
                    updateNotification(cfg, StatusDisplay.build(serviceVpnState.value, this::getString))
                    startWhitelistObserver()
                    lastError.value = null
                    Log.d("SshVpnService", "Auto reconnect succeeded to ${cfg.name}")
                } catch (e: Exception) {
                    lastError.value = e.message
                    serviceVpnState.value =
                        DomainVpnState(
                            status = DomainVpnState.VpnStatus.Failed,
                            error = e.message,
                        )
                    disconnectInternal(userInitiated = false)
                    vpnController.reportHealth(verified = false, failedStep = mapConnectFailureToStep(e.message))
                }
            }
        } finally {
            reconnecting.set(false)
            poolFailReconnectPending = false
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
        private const val POOL_FAIL_RECONNECT_BACKOFF_MS = 10_000L
        private const val HEALTH_PROBE_INTERVAL_MS = 15_000L
    }
}
