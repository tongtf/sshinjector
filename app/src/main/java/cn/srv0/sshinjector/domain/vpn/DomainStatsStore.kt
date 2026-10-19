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
    /**
     * 最近一次解析拿到的**地址总数** (只留两槽, 这个数让"截断"在 UI 上可见 —— 设计 §7.3 的 `+N`)。
     * 0 = 从未解析过 / 解析无结果。
     */
    val realIpTotal: Int = 0,
    /**
     * 本域名已知的**发起方 uid** (最多 [DomainStatsStore.MAX_APP_UID_SLOTS] 槽, `-1` = 该槽空)。
     *
     * 与 [realIp] 同样的取舍: 只留固定几槽 (不引 `Set` —— 20000 条 × 空 `HashSet` 就是 1.4MB), 溢出
     * 由 [DomainStatsStore.appUidOverflow] 全局计数兜底; 每槽是**普通 Int**, 快照层零分配。
     */
    val appUid1: Int = -1,
    val appUid2: Int = -1,
    val appUid3: Int = -1,
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

            /** 每域名最多保留几个 uid 槽 (溢出计入 [appUidOverflow], 只用于诊断)。 */
            const val MAX_APP_UID_SLOTS: Int = 3

            /** 空 uid 槽的哨兵 (`-1` = `Process.INVALID_UID`, 也是平台"无归属"的返回值)。 */
            const val NO_UID: Int = -1
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
            var realIpTotal: Int = 0

            /**
             * 已知归属 uid 槽 (`null` = 尚未拿到任何一个)。**惰性分配**: 没有归属的条目 (多数)
             * 一个字节都不多花 —— 3 槽 × 4B + 头 ≈ 32B, 20000 条最坏 0.6MB。
             */
            var appUids: IntArray? = null

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

        /** 归属 uid 槽满被忽略的次数 (诊断: 出现非 0 说明 3 槽不够, 但绝不因此扩容)。 */
        private val appUidOverflowCount = AtomicLong(0L)

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
         *
         * **整对覆盖** (而不是往空槽里追加): 一次解析就是一次刷新, 结果要么全换要么不动 ——
         * 追加语义会让第二次解析的第二个地址永远进不来 (两槽早被上一次填满)。
         * 只留前两槽 (不引 `Set`), 但**无论落几槽都记 [DomainStatSnapshot.realIpTotal] = 总数**,
         * 让截断在 UI 上看得见 (设计 §7.3 `+N`)。只锁内读写 (32 位 ABI 防撕裂)。
         */
        fun attachRealIp(
            domain: String,
            ips: List<String>,
        ) {
            if (ips.isEmpty()) return
            val entry = map[domain] ?: return
            synchronized(entry) {
                entry.realIp = ips.getOrNull(0)
                entry.realIp2 = ips.getOrNull(1)
                entry.realIpTotal = ips.size
            }
        }

        /**
         * 归属 uid 标注 (④ 按应用归属): 与 [attachRealIp] 同纪律 —— 只更新**已存在**的条目
         * (不新建, 保持 `connCount ≥ 1` = 真访问过的语义), 去重后写入空槽, 槽满只计数。
         *
         * **不在数据面线程上调用**: 这是反查线程的回调 (`DomainStatsStore` 只保证线程安全与零分配,
         * 不保证快)。锁纪律同 [recordClose] (32 位 ABI 防撕裂)。
         */
        fun attachAppUid(
            domain: String,
            uid: Int,
        ) {
            if (uid < 0) return
            val entry = map[domain] ?: return
            synchronized(entry) {
                val slots =
                    entry.appUids
                        ?: IntArray(MAX_APP_UID_SLOTS) { NO_UID }.also { entry.appUids = it }
                if (slots.contains(uid)) return
                val free = slots.indexOfFirst { it == NO_UID }
                if (free < 0) {
                    appUidOverflowCount.incrementAndGet()
                    return
                }
                slots[free] = uid
            }
        }

        /** 全量快照 (未排序); 每条在条目锁内读取 (32 位防撕裂读)。调用方在 Default 线程排序。 */
        fun snapshot(): List<DomainStatSnapshot> {
            val out = ArrayList<DomainStatSnapshot>(map.size)
            for (entry in map.values) {
                out.add(
                    synchronized(entry) {
                        val uids = entry.appUids
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
                            realIpTotal = entry.realIpTotal,
                            appUid1 = uids?.get(0) ?: NO_UID,
                            appUid2 = uids?.get(1) ?: NO_UID,
                            appUid3 = uids?.get(2) ?: NO_UID,
                        )
                    },
                )
            }
            return out
        }

        fun size(): Int = map.size

        fun trimmedTotal(): Long = trimmedTotalCount.get()

        /** 归属 uid 槽满被忽略的累计次数 (诊断 / 单测)。 */
        fun appUidOverflow(): Long = appUidOverflowCount.get()

        fun clear() {
            map.clear()
            trimmedTotalCount.set(0L)
            appUidOverflowCount.set(0L)
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

        /**
         * 快照资源节用: "域名统计 123/20000(淘汰 0)"。
         *
         * **无归属溢出时字符串与 P0/P1 逐字一致** (真机验收清单 S12 拿它和页面数字对账); 有溢出的
         * 时候才追加一节 —— 那是"3 槽不够"的唯一可见信号。O(1)。
         */
        fun diagnostics(): String {
            val base = "域名统计 ${map.size}/$MAX_DOMAINS(淘汰 ${trimmedTotalCount.get()})"
            val overflow = appUidOverflowCount.get()
            return if (overflow > 0L) "$base · 归属溢出 $overflow" else base
        }

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
