package cn.srv0.sshinjector.domain.vpn.tunnel

import android.util.Log
import cn.srv0.sshinjector.data.remote.tunnel.TunnelPluginFactory
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.exit.ExitHealth
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 出口 (exit) 注册表: 一个出口 = 一个插件实例 = 一个 `Socks5ProxyServer` + 一个 `JschSshClient`。
 *
 * 键是**出口 id** (`default` / 用户命名), 不是协议名 —— 旧实现按协议名分键,
 * 第二个 socks5 实例会把第一个覆盖掉且不 `disconnect()` (端口/会话泄漏, 附录 C-4)。
 */
@Singleton
class TunnelManager
    @Inject
    constructor(
        private val factories: Map<String, @JvmSuppressWildcards TunnelPluginFactory>,
    ) {
        companion object {
            private const val TAG = "TunnelManager"
        }

        /** 一个已启动的出口。 */
        data class ActiveExit(
            val id: String,
            /** 展示名 (日志前缀); 主出口为空串。 */
            val label: String,
            /** 服务器地址, 只进快照/UI (`hk(hk2.example.com)`); 别塞进 label。 */
            val serverHost: String,
            val plugin: TunnelPlugin,
            val startedAt: Long,
            @Volatile var health: ExitHealth = ExitHealth.Ready,
            @Volatile var degradedSince: Long = 0,
            @Volatile var lastProbeOkAt: Long = 0,
            @Volatile var connTotal: Long = 0,
            @Volatile var failTotal: Long = 0,
            @Volatile var degradeTotal: Long = 0,
            @Volatile var lastError: String = "",
            // 活跃连接数**不在这里记账**: 单一来源是 `plugin.activeConnections`
            // (= `Socks5ProxyServer.connections.size`), 软上限直接读它 (I-19)。
            // 另记一份 = 两处增减不同步 → 软上限要么永不触发要么永久触发。
        )

        /**
         * 出口运行态**不可变快照** (UI / 只读消费方专用)。
         *
         * 与 [ActiveExit] 的差别只有一条: **不含 `plugin` 引用**。UI 拿不到插件就不会顺手去读
         * 插件内部状态 (连接数/健康), 那正是 chip 显示"永远 Ready / 连接数恒 0"的根源 (P0-2)。
         * 所有字段在发布时取值并冻结; 每次 [publishExits] 都是新实例, `StateFlow` 按值比较
         * 才会真的发射 (复用旧实例 = 变化永远看不到)。
         */
        data class ExitSnapshot(
            val id: String,
            /** 展示名 (日志前缀); 主出口为空串。 */
            val label: String,
            val serverHost: String,
            val health: ExitHealth,
            /** 发布瞬间的活跃连接数 (单一来源 = `plugin.activeConnections`)。 */
            val activeConnections: Int,
            val connTotal: Long,
            val failTotal: Long,
            val degradeTotal: Long,
            val lastError: String,
            val startedAt: Long,
        )

        private val exits = ConcurrentHashMap<String, ActiveExit>()

        /** 主出口的插件 (兼容既有 `getActiveOrFallback` 语义)。 */
        private val activePlugin = MutableStateFlow<TunnelPlugin?>(null)

        /**
         * 会话内出口运行态 (UI chip / 排障): 每次启停或健康变化后重新发布。
         * 只发布**不可变**快照列表, UI 不接触 map/plugin 内部。
         *
         * 发布时机**刻意不含** `noteConnection`/`noteHandshakeFailure` (每次连接一次 →
         * 高频分配, 见方案 R2): 连接数/失败数在两次发布之间是冻结值, 下一次启停/健康变化
         * 会用最新值重算整份快照。
         */
        private val mutableExitsState = MutableStateFlow<List<ExitSnapshot>>(emptyList())
        val exitsState: StateFlow<List<ExitSnapshot>> = mutableExitsState.asStateFlow()

        private fun publishExits() {
            // 每次 map 出新实例 (不可变副本): 只发布 ActiveExit 的**值**, 不发布可变对象本身
            mutableExitsState.value = exits.values.map { it.toSnapshot() }
        }

        /**
         * 立即重新发布出口快照。
         *
         * 供**绕过** [noteDegraded]/[noteRecovered] 直接改 `ActiveExit.health` 的路径调用
         * (健康巡检先改状态再记账): 不补这一下, chip / 统计页会停在上一帧。
         */
        fun publishExitsNow() = publishExits()

        private fun ActiveExit.toSnapshot(): ExitSnapshot =
            ExitSnapshot(
                id = id,
                label = label,
                serverHost = serverHost,
                health = health,
                // 连接数不在此记账: 单一来源是插件的连接表, 发布时取一次瞬时值
                activeConnections = plugin.activeConnections,
                connTotal = connTotal,
                failTotal = failTotal,
                degradeTotal = degradeTotal,
                lastError = lastError,
                startedAt = startedAt,
            )

        /**
         * 会话代际: `stopAll()` 自增。`startExit` 在每个挂起点之后复查 ——
         * 断开之后才完成的启动必须立刻回收, 否则会出现"断开后端口/会话复活" (I-5/I-7)。
         */
        @Volatile private var sessionGeneration = 0L

        init {
            Log.d(TAG, "Registered tunnel factories: ${factories.keys}")
        }

        /**
         * 启动一个出口。
         *
         * - 幂等: 同 id 已在运行直接返回 success (重复启动不重建池);
         * - 失败**不**移除旧的可用实例 (设计 §8.4);
         * - 每个挂起点之后复查 [sessionGeneration], 断开后完成的启动立即 disconnect 并失败。
         *
         * **不持锁跨 `connect()`**: `connect()` 是阻塞式 SSH 建池 (可达数秒), 持锁会让
         * `forceReset()` 的 `stopAll()` 卡在它后面 (用户点断开却要等 10s)。
         * 启动/停止的串行化由 `VpnController.connectMutex` 保证; 绕过它的 `forceReset`
         * 由 [sessionGeneration] 兜底 (启动完成时发现代际变了就自我回收)。
         *
         * @param label 只进日志前缀; 主出口必须传空串 (关闭多出口时日志逐字不变)
         * @param serverHost 只进快照/UI
         */
        suspend fun startExit(
            id: String,
            protocolId: String,
            label: String,
            serverHost: String,
            config: TunnelConfig,
        ): Result<Unit> {
            exits[id]?.let { existing ->
                when (existing.health) {
                    // 已经**在启动中**: 不能回 "success"(调用方会以为出口可用), 也不能再起一个池
                    ExitHealth.Starting -> return Result.failure(IllegalStateException("Exit $id is still starting"))
                    // Failed 是本会话的终态, 但**允许重建**: 不先摘除的话会静默假成功, 出口永远不恢复
                    ExitHealth.Failed -> {
                        exits.remove(id, existing)
                        runCatching { existing.plugin.disconnect() }
                        publishExits()
                    }
                    // Ready/Degraded/Stopped(已 remove): 已在运行, 幂等成功
                    else -> return Result.success(Unit)
                }
            }

            val factory =
                factories[protocolId]
                    ?: return Result.failure(IllegalArgumentException("Unknown tunnel: $protocolId"))

            val gen = sessionGeneration
            Log.d(TAG, "Starting plugin: $protocolId (exit=$id)")
            val plugin = factory.create(id, label)
            // **先登记 (health=Starting) 再 connect**: 否则 `stopAll()`/`stopExit()` 看不到
            // "正在建池"的出口, 中途断开时那套 SSH 会话池会残留到进程结束 (sessionGeneration
            // 只兜住"启动完成之后"的那一半)。Starting 也让快照能区分"起来了"和"还在连"。
            val entry =
                ActiveExit(
                    id = id,
                    label = label,
                    serverHost = serverHost,
                    plugin = plugin,
                    startedAt = System.currentTimeMillis(),
                    health = ExitHealth.Starting,
                )
            exits[id] = entry
            publishExits()

            return try {
                val result = plugin.connect(config)
                when {
                    gen != sessionGeneration -> {
                        // 断开已经发生 (stopAll 可能已经把它摘掉并 disconnect 过了)
                        exits.remove(id, entry)
                        runCatching { plugin.disconnect() }
                        Log.d(TAG, "Discarding exit $id started after session change")
                        Result.failure(IllegalStateException("Session changed while starting exit $id"))
                    }
                    exits[id] !== entry -> {
                        // 启动期间被 `stopExit(id)` 摘掉 (用户禁用/降级回收): 立刻回收刚建好的池。
                        // **不能**递增全局 `sessionGeneration` 来覆盖这一情形 —— 那会把其它正在
                        // 启动的出口一并判成过期 ("禁用 A, B 也启动失败", P1-13)。
                        exits.remove(id, entry)
                        runCatching { plugin.disconnect() }
                        Log.d(TAG, "Discarding exit $id stopped while starting")
                        Result.failure(IllegalStateException("Exit $id stopped while starting"))
                    }
                    result.isSuccess -> {
                        entry.health = ExitHealth.Ready
                        entry.lastError = ""
                        activePlugin.value = exits[ExitId.DEFAULT]?.plugin
                        publishExits()
                        Result.success(Unit)
                    }
                    else -> {
                        // 保留 Failed 条目 (方案 A): 启动失败出口不再从快照/UI 凭空消失;
                        // 不参与选路 (selectExit 只认 Ready), 本会话不自动复活, 下次 connect 重建。
                        entry.health = ExitHealth.Failed
                        entry.lastError = result.exceptionOrNull()?.message ?: "启动失败"
                        entry.failTotal++
                        runCatching { plugin.disconnect() }
                        publishExits()
                        Log.e(TAG, "Failed to start $protocolId: ${result.exceptionOrNull()?.message}")
                        if (id == ExitId.DEFAULT) {
                            // 主出口失败 = 整个会话不可用, 进全局归因
                            VpnController.appLogThrottled(
                                "隧道插件启动失败 · $protocolId — ${result.exceptionOrNull()?.message}",
                                level = LogLevel.ERROR,
                                throttleKey = "隧道插件启动失败",
                            )
                        }
                        // 附加出口: 由调用方按出口名打一条 WARNING (E-3: 不进全局故障归因),
                        // 这里只留 logcat, 避免同一失败产生两条可见日志。
                        result
                    }
                }
            } catch (e: CancellationException) {
                // 启动被取消 (连接中途断开): 必须自己把池收掉, 否则没人再看得见它
                exits.remove(id, entry)
                runCatching { plugin.disconnect() }
                publishExits()
                throw e
            } catch (e: Exception) {
                exits.remove(id, entry)
                runCatching { plugin.disconnect() }
                publishExits()
                throw e
            }
        }

        /**
         * 停止并移除单个出口 (断开/禁用/降级后回收)。不存在的 id 是 no-op。
         *
         * **只动自己的条目**: 不递增 [sessionGeneration] —— 那是 `stopAll()` 的全局语义,
         * 用在这里会让其它正在启动的出口全部作废 (P1-13)。启动中的出口由 `startExit`
         * 的 `exits[id] === entry` 身份复查兜住 (late success 自我回收)。
         */
        suspend fun stopExit(id: String) {
            val exit = exits.remove(id) ?: return
            exit.health = ExitHealth.Stopped
            runCatching { exit.plugin.disconnect() }
            activePlugin.value = exits[ExitId.DEFAULT]?.plugin
            publishExits()
        }

        fun exit(id: String): ActiveExit? = exits[id]

        /** 该出口又接了一条新连接 (快照 `累计` 口径); 出口不存在时静默忽略。 */
        @Synchronized
        fun noteConnection(exitId: String) {
            exits[exitId]?.let { it.connTotal++ }
        }

        /** 握手阶段失败 (§9.5 快速重试的判据来源); 出口不存在时静默忽略。 */
        @Synchronized
        fun noteHandshakeFailure(
            exitId: String,
            detail: String,
        ) {
            // 同样不发布 (失败可能成批出现); 失败计数随下一次健康节拍进 UI
            exits[exitId]?.let {
                it.failTotal++
                it.lastError = detail
            }
        }

        /** 健康巡检判定降级。 */
        @Synchronized
        fun noteDegraded(
            exitId: String,
            reason: String,
        ) {
            exits[exitId]?.let {
                it.degradeTotal++
                it.lastError = reason
                publishExits()
            }
        }

        /** 健康巡检判定恢复 (清掉最后一次失败原因)。 */
        @Synchronized
        fun noteRecovered(exitId: String) {
            exits[exitId]?.let {
                it.lastError = ""
                publishExits()
            }
        }

        /**
         * 可变条目列表 (含 `plugin`): **只给诊断/健康巡检用** (要读插件端口、探测、改 health)。
         * UI 层一律消费 [exitsState] 的 [ExitSnapshot], 不要拿这条路径去渲染。
         */
        fun exitsSnapshot(): List<ActiveExit> = exits.values.toList()

        /** 当前主出口的插件; null = 隧道未就绪/已拆除 (插件不再有"未连接实例"兜底)。 */
        fun getActiveOrFallback(): TunnelPlugin? = activePlugin.value

        /**
         * 停止全部出口。**必须先自增代际**再 disconnect: 反了的话, 正在 `connect()` 里的
         * 附加出口会在断开之后登记成功 → 端口/SSH 会话跨会话泄漏。
         */
        suspend fun stopAll() {
            sessionGeneration++
            val snapshot = exits.values.toList()
            exits.clear()
            activePlugin.value = null
            snapshot.forEach { exit ->
                exit.health = ExitHealth.Stopped
                runCatching { exit.plugin.disconnect() }
            }
            publishExits()
        }
    }
