package cn.srv0.sshinjector.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

private const val LOCK_POLL_INTERVAL_MS = 20L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * 会话锁的限时获取 (T-06): 在 [windowMs] 窗口内轮询 [Mutex.tryLock]。
 *
 * **返回 true = 调用方持有该锁 (必须自行 unlock); false = 一定未持有** ——
 * 获取与持有严格同源, 不存在"拿到锁却没被告知"的中间态。
 *
 * 旧写法 `withTimeoutOrNull { mutex.lock() }` 在 `lock()` 已成功、超时恰好在该边界触发时
 * 会返回 null: 调用方走"无锁分支"把锁永久占住 → 之后所有 connect/disconnect/rebuild 死锁。
 * tryLock 轮询期间只 [delay] 挂起, **不会持锁挂起**。
 */
internal suspend fun acquireLockBounded(
    mutex: Mutex,
    windowMs: Long,
): Boolean {
    val deadline = System.nanoTime() + windowMs.coerceAtLeast(0L) * NANOS_PER_MILLI
    while (true) {
        if (mutex.tryLock()) return true
        if (System.nanoTime() >= deadline) return false
        delay(LOCK_POLL_INTERVAL_MS)
    }
}

/**
 * Failed 观察者是否应执行断开清理 (T-07 的纯判定部分)。三条跳过条件:
 * 1. [poolFailReconnectPending] 池失败已有重连排队 — 该失败的收尾由重连负责;
 * 2. [isReconnecting] 重连进行中 — 失败观察与重连的触发顺序不确定, 此时清理会拆掉刚重建的会话;
 * 3. [hasLiveSession] 为 false — 已无可清理资源 (connect 自己的 catch 已清理过,
 *    再跑一遍会把 Failed 状态复位成 Disconnected)。
 *
 * 判定与执行必须在同一 connectMutex 临界区内完成 (调用方 [SshVpnService.disconnectIfIdleOnFailure])。
 */
internal fun shouldCleanupFailureSession(
    poolFailReconnectPending: Boolean,
    isReconnecting: Boolean,
    hasLiveSession: Boolean,
): Boolean = !poolFailReconnectPending && !isReconnecting && hasLiveSession
