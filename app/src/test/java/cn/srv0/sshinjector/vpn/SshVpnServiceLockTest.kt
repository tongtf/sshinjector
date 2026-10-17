package cn.srv0.sshinjector.vpn

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-06 / T-07 的纯逻辑护栏。
 *
 * 真实 [SshVpnService] 需要 Android runtime, 无法在 JVM 单测里实例化, 所以把两条不变量
 * 抽成本文件的纯函数测试:
 * - [acquireLockBounded]: `withTimeoutOrNull { lock() }` 曾能在"已获得锁但超时边界触发"时
 *   返回 null → 锁被永久占住, 之后所有 connect/disconnect/rebuild 死锁。这里的核心断言是
 *   **返回 false 时一定没有泄漏的持有者**。
 * - [shouldCleanupFailureSession]: Failed 观察者的跳过条件必须与实际 disconnect 在同一临界区,
 *   否则重连重建出的新会话会被观察者拆掉 (TOCTOU)。
 *
 * 纪律: 持锁的"另一协程"用守护线程 + 有界 [CountDownLatch.await] 承载 —— 本项目曾有
 * 无界等待挂死 CI 26 分钟的先例, 任何等待都必须有截止时间。
 */
class SshVpnServiceLockTest {
    @Test
    fun `acquire returns true and caller owns lock when free`() {
        runBlocking {
            val mutex = Mutex()
            val acquired = withTimeout(TEST_TIMEOUT_MS) { acquireLockBounded(mutex, ACQUIRE_WINDOW_MS) }
            assertTrue("free mutex must be acquired inside the window", acquired)
            assertFalse("a second tryLock must fail while the caller holds it", mutex.tryLock())
            mutex.unlock()
            assertTrue("caller's unlock must release the mutex", mutex.tryLock())
            mutex.unlock()
        }
    }

    @Test
    fun `acquire returns false and does not hold the lock on timeout`() {
        runBlocking {
            val mutex = Mutex()
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    runBlocking {
                        mutex.lock()
                        held.countDown()
                        // 有界阻塞: 持锁期间保持占用, 放行后立刻 unlock
                        release.await(HOLDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        mutex.unlock()
                    }
                }
            holder.isDaemon = true
            holder.start()
            try {
                assertTrue("holder must take the mutex", held.await(HOLDER_TIMEOUT_MS, TimeUnit.MILLISECONDS))

                val acquired = withTimeout(TEST_TIMEOUT_MS) { acquireLockBounded(mutex, ACQUIRE_WINDOW_MS) }
                assertFalse("must not claim ownership while another holder has it", acquired)
            } finally {
                release.countDown()
            }
            holder.join(HOLDER_TIMEOUT_MS)
            assertFalse("holder thread must finish (bounded join)", holder.isAlive)
            assertTrue("lock must be free after the timeout — no leaked holder", mutex.tryLock())
            mutex.unlock()
        }
    }

    @Test
    fun `acquire then unlock leaves exactly zero holders`() {
        runBlocking {
            val mutex = Mutex()
            repeat(PAIRING_ITERATIONS) {
                assertTrue(
                    "paired acquire must always succeed on a released mutex",
                    withTimeout(TEST_TIMEOUT_MS) { acquireLockBounded(mutex, ACQUIRE_WINDOW_MS) },
                )
                mutex.unlock()
            }
            // 恰好一个持有者: 第一次 tryLock 成功, 第二次必须失败 (持锁计数回到 0/-1 都会露馅;
            // 多 unlock 一次也会直接抛 IllegalStateException)
            assertTrue("no holder may remain after paired unlock", mutex.tryLock())
            assertFalse("exactly one holder after tryLock", mutex.tryLock())
            mutex.unlock()
        }
    }

    @Test
    fun `zero window still acquires a free lock but never blocks`() {
        runBlocking {
            val mutex = Mutex()
            assertTrue("tryLock on a free mutex succeeds even with a zero window", acquireLockBounded(mutex, 0))
            mutex.unlock()
        }
    }

    @Test
    fun `failure observer skips when reconnect pending or reconnecting`() {
        assertFalse(
            "a queued pool-fail reconnect owns the failure cleanup",
            shouldCleanupFailureSession(poolFailReconnectPending = true, isReconnecting = false, hasLiveSession = true),
        )
        assertFalse(
            "an in-flight reconnect must not have its fresh session torn down",
            shouldCleanupFailureSession(poolFailReconnectPending = false, isReconnecting = true, hasLiveSession = true),
        )
        assertFalse(
            "both flags set: reconnect still owns the cleanup",
            shouldCleanupFailureSession(poolFailReconnectPending = true, isReconnecting = true, hasLiveSession = true),
        )
    }

    @Test
    fun `failure observer cleans only when idle with a live session`() {
        assertTrue(
            "idle + live session must be cleaned up",
            shouldCleanupFailureSession(
                poolFailReconnectPending = false,
                isReconnecting = false,
                hasLiveSession = true,
            ),
        )
        assertFalse(
            "nothing left to clean must be skipped (avoids resetting Failed to Disconnected)",
            shouldCleanupFailureSession(
                poolFailReconnectPending = false,
                isReconnecting = false,
                hasLiveSession = false,
            ),
        )
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 10_000L
        const val HOLDER_TIMEOUT_MS = 10_000L
        const val ACQUIRE_WINDOW_MS = 150L
        const val PAIRING_ITERATIONS = 5
    }
}
