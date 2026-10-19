package cn.srv0.sshinjector.domain.vpn

import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 连接关闭原因 (由 TcpStateMachine 的 `why` 映射; 只用于 failedConnCount 归类)。 */
enum class CloseKind {
    CLIENT_CLOSED,
    REMOTE_EOF,
    INTERNAL_FAILURE,
}

/** 不可变快照 (UI / 导出只读这个, 不读内部可变对象)。 */
data class DomainStatSnapshot(
    val domain: String,
    val isIpLiteral: Boolean,
    val connCount: Long,
    val failedConnCount: Long,
    val upBytes: Long,
    val downBytes: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastPort: Int,
    val realIp: String?,
    val realIp2: String?,
)

/**
 * 域名访问统计的内存聚合器 (会话级; 纯 JVM 可测: 不碰 Android / 日志 / 线程)。
 *
 * 采集点是 `TcpStateMachine.closeTcpConnection` (每连接一次, 非逐包), 热路径纪律:
 *  [recordClose] 命中已有条目时**零分配** (先查表, 未命中才建条目 + `putIfAbsent`; 不用
 *  `computeIfAbsent`, 那个 lambda 会捕获参数、每次调用都新建一个对象); 超限只置一次
 *  `@Volatile` 标志, 排序/淘汰**绝不**在调用线程上跑 (一次 20000 条排序 + 一个 20000 元素
 *  临时列表 = packetLoop 全连接停顿), 只由 [maybeTrim] 在低频巡检里做。
 *
 * 条目字段全是普通 `var`, 只在 `synchronized(entry)` 内读写 —— 产物含 32 位 ABI
 * (armeabi-v7a), 裸 `long` 读写在 32 位下非原子 (会撕出"下行 9.2EB"这类脏值), 多线程
 * `x += n` 还会丢更新。
 */
@Singleton
class DomainStatsStore
    @Inject
    constructor() {
        companion object {
            const val MAX_DOMAINS: Int = 20000
            const val TRIM_INTERVAL_MS: Long = 30_000
            const val WINDOW_MS: Long = 7L * 24 * 3600 * 1000
        }

        /** 单个域名的可变聚合条目。普通 class (非 data class): 覆盖 11 个字段的 equals/toString 没有意义。 */
        private class Entry(
            val domain: String,
            val isIpLiteral: Boolean,
        ) {
            var connCount: Long = 0L
            var failedConnCount: Long = 0L
            var upBytes: Long = 0L
            var downBytes: Long = 0L
            var firstSeenAt: Long = 0L
            var lastSeenAt: Long = 0L
            var lastPort: Int = 0
            var realIp: String? = null
            var realIp2: String? = null

            /** 只在持有本对象监视器 (`synchronized(this)`) 时调用。 */
            @Suppress("LongParameterList") // 热路径累加器: 包成 config 对象会在每次连接关闭时多一次分配
            fun accumulate(
                dstPort: Int,
                addUpBytes: Long,
                addDownBytes: Long,
                startedAtMs: Long,
                kind: CloseKind,
                nowMs: Long,
            ) {
                if (connCount == 0L) firstSeenAt = startedAtMs
                connCount++
                if (kind == CloseKind.INTERNAL_FAILURE) failedConnCount++
                upBytes += addUpBytes
                downBytes += addDownBytes
                lastSeenAt = nowMs
                lastPort = dstPort
            }
        }

        private val map = ConcurrentHashMap<String, Entry>()

        /** 超限时只置这个标志 (一次 volatile 写), 排序/淘汰留给 [maybeTrim]。 */
        @Volatile
        private var trimPending: Boolean = false

        /** 淘汰累计 (含超窗清理), 供快照资源节读; [clear] 归零。 */
        private val trimmedTotalCount = AtomicLong(0L)

        /** [maybeTrim] 的间隔门控状态, 只在 [gate] 内访问。 */
        private val gate = Any()
        private var nextTrimAllowedAtMs: Long = 0L

        /** 热路径: 每次 TCP 连接结束调一次。禁止分配 / 禁止 IO / 禁止日志。 */
        @Suppress("LongParameterList") // §1.1 冻结的 8 参采集接口; 拆成 config 对象会在热路径多一次分配
        fun recordClose(
            domain: String,
            isIpLiteral: Boolean,
            dstPort: Int,
            upBytes: Long,
            downBytes: Long,
            startedAtMs: Long,
            kind: CloseKind,
            nowMs: Long,
        ) {
            // 命中是常态: 先查, 命中即就地累加并 return (零分配)。
            val existing = map[domain]
            if (existing != null) {
                synchronized(existing) { existing.accumulate(dstPort, upBytes, downBytes, startedAtMs, kind, nowMs) }
                return
            }
            val fresh = Entry(domain, isIpLiteral)
            synchronized(fresh) { fresh.accumulate(dstPort, upBytes, downBytes, startedAtMs, kind, nowMs) }
            val winner = map.putIfAbsent(domain, fresh)
            if (winner != null) {
                // 并发下别的线程先插入了: 累加到它身上, fresh 丢弃。
                synchronized(winner) { winner.accumulate(dstPort, upBytes, downBytes, startedAtMs, kind, nowMs) }
            } else if (map.size > MAX_DOMAINS) {
                trimPending = true
            }
        }

        /**
         * 真实 IP 标注: 只更新**已存在**的条目 (不存在时什么都不做, 不新建 —— 保持
         * `connCount ≥ 1` = 真访问过的语义, 也避免把"解析了但没连"的域名灌进表)。
         * 去重; `realIp` 空则填 `realIp`, 否则填 `realIp2`, 再多**丢弃** (不引 Set)。
         */
        fun attachRealIp(
            domain: String,
            ip: String,
        ) {
            val entry = map[domain] ?: return
            synchronized(entry) {
                // 去重 + 只留两个槽: realIp 空则填 realIp, 否则 realIp2 空则填 realIp2, 再多的丢弃 (不引 Set)。
                val first = entry.realIp
                val second = entry.realIp2
                if (first == null) {
                    entry.realIp = ip
                } else if (first != ip && second == null) {
                    entry.realIp2 = ip
                }
            }
        }

        /** 全量快照 (未排序); 每条在条目锁内读取 (32 位防撕裂读)。调用方在 Default 线程排序。 */
        fun snapshot(): List<DomainStatSnapshot> {
            val out = ArrayList<DomainStatSnapshot>(map.size)
            for (entry in map.values) {
                out.add(
                    synchronized(entry) {
                        DomainStatSnapshot(
                            domain = entry.domain,
                            isIpLiteral = entry.isIpLiteral,
                            connCount = entry.connCount,
                            failedConnCount = entry.failedConnCount,
                            upBytes = entry.upBytes,
                            downBytes = entry.downBytes,
                            firstSeenAt = entry.firstSeenAt,
                            lastSeenAt = entry.lastSeenAt,
                            lastPort = entry.lastPort,
                            realIp = entry.realIp,
                            realIp2 = entry.realIp2,
                        )
                    },
                )
            }
            return out
        }

        fun size(): Int = map.size

        fun trimmedTotal(): Long = trimmedTotalCount.get()

        fun clear() {
            map.clear()
            trimmedTotalCount.set(0L)
            trimPending = false
            synchronized(gate) { nextTrimAllowedAtMs = 0L }
        }

        /** 低频: 只在 PacketProcessor 的 100ms 巡检里调, 内部按 [TRIM_INTERVAL_MS] 门控。返回本次淘汰数。 */
        fun maybeTrim(nowMs: Long): Int {
            synchronized(gate) {
                if (nowMs < nextTrimAllowedAtMs) return 0
                nextTrimAllowedAtMs = nowMs + TRIM_INTERVAL_MS
            }
            return sweepAndTrim(nowMs)
        }

        /** 快照资源节用: "域名统计 123/20000(淘汰 0)"。O(1)。 */
        fun diagnostics(): String = "域名统计 ${map.size}/$MAX_DOMAINS(淘汰 ${trimmedTotalCount.get()})"

        /** 测试钩子: 绕过 [TRIM_INTERVAL_MS] 门控立刻淘汰一次 (生产代码不要调)。 */
        internal fun trimNowForTest(nowMs: Long = System.currentTimeMillis()): Int = sweepAndTrim(nowMs)

        /**
         * 真正的淘汰: 先清超窗条目 (`lastSeenAt < nowMs - WINDOW_MS`), 再在超限时按 `lastSeenAt`
         * 升序淘汰到容量的 90% (淘汰 5% 会被立刻填满, 形成周期性尖峰)。只在低频巡检 / 测试钩子里执行
         * —— 这里有一次 O(n log n) 排序 + 临时列表, 绝不能落到热路径。
         */
        private fun sweepAndTrim(nowMs: Long): Int {
            val expiredBefore = nowMs - WINDOW_MS
            var removed = 0
            for (entry in map.values) {
                val lastSeen = synchronized(entry) { entry.lastSeenAt }
                if (lastSeen < expiredBefore && map.remove(entry.domain, entry)) removed++
            }
            if (trimPending || map.size > MAX_DOMAINS) {
                val target = MAX_DOMAINS * 9 / 10
                val overflow = map.size - target
                if (overflow > 0) {
                    val victims = map.values.sortedBy { synchronized(it) { it.lastSeenAt } }.take(overflow)
                    for (entry in victims) {
                        if (map.remove(entry.domain, entry)) removed++
                    }
                }
            }
            trimPending = false
            if (removed > 0) trimmedTotalCount.addAndGet(removed.toLong())
            return removed
        }
    }
