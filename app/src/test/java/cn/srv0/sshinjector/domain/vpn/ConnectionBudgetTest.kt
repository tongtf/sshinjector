package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全出口共享连接预算 (设计 §10 I-8 / I-18)。
 *
 * 这些用例锁死的是"越用越少直到全拒"的三个前提:
 * 1. 拒绝**不**占用名额 (否则拒绝本身就是泄漏);
 * 2. release 不会把计数减成负数 (否则全局限额静默失效);
 * 3. 并发 acquire 的放行数**恰好**等于上限 (事件环 + SSH IO 线程池并发调用)。
 *
 * 全部等待有界 (10s): 线程用 daemon, 不用无界 `await()`。
 */
class ConnectionBudgetTest {
    @Test
    fun `rejects the acquisition that exceeds the limit`() {
        val budget = ConnectionBudget(3)

        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse("第 4 条必须被拒", budget.tryAcquire())

        assertEquals(3, budget.activeCount)
        assertEquals(3, budget.acquiredCount)
        assertEquals(1, budget.rejectedCount)
    }

    @Test
    fun `rejection does not consume a permit`() {
        val budget = ConnectionBudget(1)
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        assertFalse(budget.tryAcquire())

        assertEquals("拒绝不得累加占用", 1, budget.activeCount)
        assertEquals(2, budget.rejectedCount)

        budget.release()
        assertEquals(0, budget.activeCount)
        assertTrue("归还后必须还能放行", budget.tryAcquire())
    }

    @Test
    fun `release never goes below zero`() {
        val budget = ConnectionBudget(2)
        budget.release()
        budget.release()
        assertEquals("空预算上重复 release 不得变成负数 (否则限额静默失效)", 0, budget.activeCount)

        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
    }

    @Test
    fun `concurrent acquisitions never exceed the limit`() {
        val limit = 100
        val budget = ConnectionBudget(limit)
        val threads = 8
        val attemptsPerThread = 200
        val granted = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                try {
                    start.await()
                    repeat(attemptsPerThread) {
                        if (budget.tryAcquire()) granted.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("并发 acquire 必须有界结束", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("放行数必须恰好等于上限", limit, granted.get())
        assertEquals(limit, budget.activeCount)
        assertEquals(threads * attemptsPerThread - limit, budget.rejectedCount.toInt())
    }

    @Test
    fun `paired acquire and release returns to zero`() {
        val budget = ConnectionBudget(16)
        val threads = 8
        val cycles = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                try {
                    start.await()
                    repeat(cycles) {
                        if (budget.tryAcquire()) budget.release()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("并发配对必须有界结束", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("配对结束后不得残留 permit", 0, budget.activeCount)
    }

    @Test
    fun `default limit matches the legacy connection cap`() {
        assertEquals(
            "P0 必须与改造前 MAX_CONNECTIONS 等价, 否则等于悄悄降低上限",
            512,
            ConnectionBudget.DEFAULT_LIMIT,
        )
    }

    @Test
    fun `holder creates a fresh budget per session and clears on disconnect`() {
        val holder = ConnectionBudgetHolder()
        assertNull("未进入会话时不限额", holder.current)

        val first = holder.startSession()
        assertSame(first, holder.current)
        assertTrue(first.tryAcquire())
        assertEquals(1, first.activeCount)

        // 新会话必须是**新对象**: 复用会把上一会话泄漏的 permit 带进新会话
        val second = holder.startSession()
        assertSame(second, holder.current)
        assertEquals("新会话从零开始", 0, second.activeCount)
        assertNotNull(holder.current)

        holder.clearSession()
        assertNull(holder.current)
    }
}
