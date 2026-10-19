package cn.srv0.sshinjector.domain.vpn

import android.content.Context
import android.net.ConnectivityManager
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppUidResolver] 的平台实现: 反射调 `ConnectivityManager.getConnectionOwnerUid`。
 *
 * 为什么是反射而不是直接调: `compileSdk` 里有这个方法 (公开 API), 但**运行设备**可能因平台裁剪/
 * 隐藏 API 限制而没有 —— 反射让"缺失"退化成"功能静默停用"而不是 `NoSuchMethodError` 崩溃。
 *
 * 线程纪律 (数据面每连接一次): [lookup] 只做原子计数 + `execute` 入队; 查询在**单线程**池里跑,
 * 队列**有界** (满了丢弃并计数 —— 绝不堆积、绝不阻塞 packetLoop), 拿不到归属只是 `misses` 一次。
 * 只有 `SecurityException` (非活动 VpnService / 平台实现差异) 才**暂停一个
 * [DISABLE_WINDOW_MS] 窗口**并在窗口内只打一条 WARNING —— 那是"功能暂时不可用", 每条连接都记
 * 一条会把 `故障归因` 刷爆; 而**不能永久停用**的原因见 [DisableWindow]。
 */
@Singleton
class ConnectivityAppUidResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AppUidResolver {
        /** 该签名在编译期 SDK 里是否存在 (缺了就整条链路静默停用)。 */
        private val method: Method? =
            runCatching {
                ConnectivityManager::class.java.getMethod(
                    "getConnectionOwnerUid",
                    Integer.TYPE,
                    InetSocketAddress::class.java,
                    InetSocketAddress::class.java,
                )
            }.getOrNull()

        /**
         * 被拒后的暂停窗口 (见 [DisableWindow]: 断开瞬间必然 `SecurityException`, 永久停用会把
         * 功能杀死到下次重启 —— 那次"修正"本身就是个新问题)。
         */
        private val disableWindow = DisableWindow(DISABLE_WINDOW_MS) { System.currentTimeMillis() }

        /** 惰性取一次系统服务 (构造期不碰系统服务: 单测用 mock Context 构造也不该抛)。 */
        @Volatile private var cachedConnectivity: ConnectivityManager? = null

        private val queries = AtomicLong(0L)
        private val hits = AtomicLong(0L)
        private val misses = AtomicLong(0L)
        private val dropped = AtomicLong(0L)
        private val denied = AtomicLong(0L)
        private val pending = AtomicInteger(0)

        private val executor =
            ThreadPoolExecutor(
                1,
                1,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(QUEUE_CAPACITY),
                { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } },
                ThreadPoolExecutor.DiscardPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }

        override fun lookup(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
            onResolved: (Int) -> Unit,
        ) {
            if (method == null || disableWindow.isActive()) return
            // 在途上限**远小于**队列容量, 因此 execute 不会触发拒绝 (DiscardPolicy 只是兜底)。
            if (pending.get() >= MAX_PENDING) {
                dropped.incrementAndGet()
                return
            }
            pending.incrementAndGet()
            queries.incrementAndGet()
            try {
                executor.execute { resolveAndReport(srcIp, srcPort, dstIp, dstPort, onResolved) }
            } catch (_: RejectedExecutionException) {
                pending.decrementAndGet()
                dropped.incrementAndGet()
            }
        }

        private fun resolveAndReport(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
            onResolved: (Int) -> Unit,
        ) {
            try {
                val uid = resolveOnce(srcIp, srcPort, dstIp, dstPort)
                if (uid != null) {
                    hits.incrementAndGet()
                    onResolved(uid)
                } else {
                    misses.incrementAndGet()
                }
            } catch (e: SecurityException) {
                misses.incrementAndGet()
                denied.incrementAndGet()
                disable(e.message ?: "SecurityException")
            } catch (_: Exception) {
                // 单条失败 (地址非法 / 反射异常 / 平台内部错) 不改变整体可用性
                misses.incrementAndGet()
            } finally {
                pending.decrementAndGet()
            }
        }

        /**
         * 同步查一次 (供单测直调): 拿到 ≥ 0 的 uid 返回它, 其余 (含 -1 = 无归属) 返回 null。
         *
         * `SecurityException` 由 [InvocationTargetException] 解包后**原样上抛**, 由 [resolveAndReport]
         * 归类为"停用" —— 那是唯一需要改变整体行为的异常。
         */
        internal fun resolveOnce(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
        ): Int? {
            val m = method ?: return null
            val connectivity = connectivity() ?: return null
            val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
            val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            val uid =
                try {
                    m.invoke(connectivity, IPPROTO_TCP, local, remote) as? Int
                } catch (e: InvocationTargetException) {
                    throw (e.targetException ?: e)
                }
            return uid?.takeIf { it >= 0 }
        }

        private fun connectivity(): ConnectivityManager? {
            cachedConnectivity?.let { return it }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cachedConnectivity = cm
            return cm
        }

        private fun disable(reason: String) {
            if (!disableWindow.trip(reason)) return
            // 一次窗口只打一条; `appLogThrottled` 再压一层 (跨窗口的重复拒绝也平滑掉)
            VpnController.appLogThrottled(
                "UID 归属反查被平台拒绝 · 连接归属 — $reason (暂停 ${DISABLE_WINDOW_MS / 1000}s 后自动重试; 域名统计临时不标注应用)",
                windowMs = DISABLE_WINDOW_MS,
                level = LogLevel.WARNING,
                throttleKey = "UID 归属反查被拒",
            )
        }

        override fun diagnostics(): String {
            val deniedCount = denied.get()
            return when {
                method == null -> "UID 归属 不可用(无 API)"
                disableWindow.isActive() ->
                    "UID 归属 暂停 ${disableWindow.remainingMs() / 1000}s(${disableWindow.reason ?: "-"}) · 被拒 $deniedCount"
                else ->
                    "UID 归属 命中 ${hits.get()}/${queries.get()} 查询 · 无归属 ${misses.get()}" +
                        (if (deniedCount > 0L) " · 被拒 $deniedCount" else "") +
                        (if (dropped.get() > 0L) " · 队列丢弃 ${dropped.get()}" else "")
            }
        }

        private companion object {
            /** `IPPROTO_TCP` (android.system.OsConstants); 6 在 MagicNumber 白名单内。 */
            const val IPPROTO_TCP = 6

            /** 在途查询上限 (≪ 队列容量, 保证 `execute` 不触发拒绝)。 */
            const val MAX_PENDING = 64
            const val QUEUE_CAPACITY = 128
            const val KEEP_ALIVE_SECONDS = 30L

            /** 被平台拒绝后的暂停窗口 (断开瞬间的 `SecurityException` 不能永久杀死功能)。 */
            const val DISABLE_WINDOW_MS = 30_000L
            const val THREAD_NAME = "app-uid-resolve"
        }
    }

/**
 * 「被平台拒绝 → 暂停一个窗口 → 自动重试」状态机 (纯逻辑, 时钟由外部注入 → 确定性可测)。
 *
 * 为什么必须窗口化: 断开 VPN 的瞬间 `TcpStateMachine.reset()` 会逐条 `closeTcpConnection` 并触发
 * 反查, 而此刻调用方**已经不是活动 VpnService** → 必然 `SecurityException`。若因此**永久**停用,
 * 一次正常断开就把功能杀死到下次进程启动 (重连也不恢复) —— 那是比"多打几条日志"严重得多的回归。
 */
internal class DisableWindow(
    private val windowMs: Long,
    private val nowMs: () -> Long,
) {
    private var untilMs = 0L

    /** 最近一次拒绝原因 (窗口内有效)。 */
    @Volatile var reason: String? = null
        private set

    fun isActive(): Boolean = nowMs() < untilMs

    /** 剩余暂停时长 (毫秒, 已过期 = 0)。 */
    fun remainingMs(): Long = (untilMs - nowMs()).coerceAtLeast(0L)

    /**
     * 登记一次拒绝。
     *
     * @return `true` = **本窗口内第一次** (调用方据此只打一条日志), 窗口内后续拒绝返回 `false`。
     */
    fun trip(reason: String): Boolean {
        val now = nowMs()
        if (now < untilMs) return false
        this.reason = reason
        untilMs = now + windowMs
        return true
    }
}
