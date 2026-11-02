package cn.srv0.sshinjector.domain.vpn

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 全出口共享的连接预算 (设计 §10 I-8)。
 *
 * 为什么需要它: 每条连接固定 32KB 直接缓冲 + 一个 selector key + 一个超时协程。
 * 多出口后如果每个出口各自 512, 3 条出口就是 1536 条 ≈ 48MB 直接缓冲, 加上同量线程必 OOM。
 * 预算是**会话级**的 (每次连接新建, 见 [ConnectionBudgetHolder]), 不跨会话复用 ——
 * 复用会让上一会话泄漏的 permit 永久吃掉新会话容量, 且断开时无从兜底。
 *
 * 语义 (三条, 缺一条就会"越用越少"直到全拒):
 * 1. [tryAcquire] 在连接对象**构造完成之后**调用 —— 构造抛异常就不会漏 permit;
 * 2. [release] **只在** `Socks5ProxyServer` 的 `onClosed` 里调一次 (与 acquire 配对);
 * 3. 计数是原子的: 事件环与 SSH IO 线程池会并发 acquire/release。
 *
 * 纯逻辑, 无 Android 依赖 (单测 `ConnectionBudgetTest` 锁死配对与拒绝不泄漏)。
 */
class ConnectionBudget(
    val limit: Int,
) {
    init {
        require(limit > 0) { "limit must be positive: $limit" }
    }

    private val active = AtomicInteger(0)
    private val acquiredTotal = AtomicLong(0)
    private val rejectedTotal = AtomicLong(0)

    /** 当前占用 (活跃连接数)。 */
    val activeCount: Int get() = active.get()

    /** 累计放行数 (会话级)。 */
    val acquiredCount: Long get() = acquiredTotal.get()

    /** 累计拒绝数 (会话级); >0 说明预算真的成了瓶颈, 不是"没触发过"。 */
    val rejectedCount: Long get() = rejectedTotal.get()

    /**
     * 尝试占用一个名额。
     * @return true = 已占用 (调用方必须在关闭路径上 [release]); false = 预算已满 (**没有**占用)
     */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= limit) {
                rejectedTotal.incrementAndGet()
                return false
            }
            if (active.compareAndSet(current, current + 1)) {
                acquiredTotal.incrementAndGet()
                return true
            }
        }
    }

    /**
     * 归还一个名额。
     *
     * 不会减到负数: 重复 release 是调用方的 bug (每次 acquire 必须恰好配对一次),
     * 但把计数减成负数会让"预算永远不满" → 全局限额静默失效, 比多拒几条更危险。
     */
    fun release() {
        while (true) {
            val current = active.get()
            if (current <= 0) return
            if (active.compareAndSet(current, current - 1)) return
        }
    }

    companion object {
        /** 默认上限: 与改造前 `Socks5ProxyServer.MAX_CONNECTIONS` 相同 (P0 零行为变化)。 */
        const val DEFAULT_LIMIT = 512
    }
}
