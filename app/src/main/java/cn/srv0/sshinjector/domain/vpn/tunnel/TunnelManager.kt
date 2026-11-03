package cn.srv0.sshinjector.domain.vpn.tunnel

import android.util.Log
import cn.srv0.sshinjector.data.remote.tunnel.TunnelPluginFactory
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.exit.ExitHealth
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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

        private val exits = ConcurrentHashMap<String, ActiveExit>()

        /** 主出口的插件 (兼容既有 `getActiveOrFallback` 语义)。 */
        private val activePlugin = MutableStateFlow<TunnelPlugin?>(null)

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
                // 已经**在启动中**: 不能回 "success"(调用方会以为出口可用), 也不能再起一个池
                return if (existing.health == ExitHealth.Starting) {
                    Result.failure(IllegalStateException("Exit $id is still starting"))
                } else {
                    Result.success(Unit)
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
                    result.isSuccess -> {
                        entry.health = ExitHealth.Ready
                        activePlugin.value = exits[ExitId.DEFAULT]?.plugin
                        Result.success(Unit)
                    }
                    else -> {
                        exits.remove(id, entry)
                        runCatching { plugin.disconnect() }
                        Log.e(TAG, "Failed to start $protocolId: ${result.exceptionOrNull()?.message}")
                        VpnController.appLogThrottled(
                            "隧道插件启动失败 · $protocolId — ${result.exceptionOrNull()?.message}",
                            level = LogLevel.ERROR,
                            throttleKey = "隧道插件启动失败",
                        )
                        result
                    }
                }
            } catch (e: CancellationException) {
                // 启动被取消 (连接中途断开): 必须自己把池收掉, 否则没人再看得见它
                exits.remove(id, entry)
                runCatching { plugin.disconnect() }
                throw e
            } catch (e: Exception) {
                exits.remove(id, entry)
                runCatching { plugin.disconnect() }
                throw e
            }
        }

        /** 停止并移除单个出口 (断开/禁用/降级后回收)。不存在的 id 是 no-op。 */
        suspend fun stopExit(id: String) {
            val exit = exits.remove(id) ?: return
            exit.health = ExitHealth.Stopped
            runCatching { exit.plugin.disconnect() }
            activePlugin.value = exits[ExitId.DEFAULT]?.plugin
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
            }
        }

        /** 健康巡检判定恢复 (清掉最后一次失败原因)。 */
        @Synchronized
        fun noteRecovered(exitId: String) {
            exits[exitId]?.let { it.lastError = "" }
        }

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
        }
    }
