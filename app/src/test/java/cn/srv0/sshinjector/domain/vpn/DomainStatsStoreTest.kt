package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [DomainStatsStore] 的聚合语义 + 热路径纪律。
 *
 * 最重要的一条是"淘汰不在热路径": 超限后 [DomainStatsStore.recordClose] 只置标志,
 * [DomainStatsStore.size] 会一直 > MAX_DOMAINS 直到 trim 被显式调用 —— CI 曾因无界自旋挂死,
 * 所以并发验证用 daemon 线程 + 有界 await。
 */
class DomainStatsStoreTest {
    private val windowMs = DomainStatsStore.WINDOW_MS

    @Test
    fun `first recordClose creates entry with counts and timestamps`() {
        val store = DomainStatsStore()
        store.recordClose("example.com", false, 443, 100L, 200L, 7_000L, CloseKind.REMOTE_EOF, 7_000L)

        val s = store.snapshot().single()
        assertEquals("example.com", s.domain)
        assertEquals(1L, s.connCount)
        assertEquals(0L, s.failedConnCount)
        assertEquals(100L, s.upBytes)
        assertEquals(200L, s.downBytes)
        assertEquals(7_000L, s.firstSeenAt)
        assertEquals(7_000L, s.lastSeenAt)
        assertEquals(443, s.lastPort)
        assertNull(s.realIp)
        assertNull(s.realIp2)
    }

    @Test
    fun `second recordClose accumulates but keeps firstSeenAt`() {
        val store = DomainStatsStore()
        store.recordClose("example.com", false, 443, 100L, 200L, 7_000L, CloseKind.REMOTE_EOF, 7_000L)
        store.recordClose("example.com", false, 80, 1L, 2L, 7_500L, CloseKind.CLIENT_CLOSED, 8_000L)

        val s = store.snapshot().single()
        assertEquals(2L, s.connCount)
        assertEquals(101L, s.upBytes)
        assertEquals(202L, s.downBytes)
        assertEquals(7_000L, s.firstSeenAt) // 不变
        assertEquals(8_000L, s.lastSeenAt) // 前进
        assertEquals(80, s.lastPort) // 最近一次的端口
    }

    @Test
    fun `only internal failure increments failed count`() {
        val store = DomainStatsStore()
        store.recordClose("a.com", false, 443, 1L, 1L, 0L, CloseKind.INTERNAL_FAILURE, 1L)
        store.recordClose("a.com", false, 443, 1L, 1L, 0L, CloseKind.CLIENT_CLOSED, 2L)
        store.recordClose("a.com", false, 443, 1L, 1L, 0L, CloseKind.REMOTE_EOF, 3L)

        val s = store.snapshot().single()
        assertEquals(3L, s.connCount)
        assertEquals(1L, s.failedConnCount)
    }

    @Test
    fun `attachRealIp does not create a missing entry`() {
        val store = DomainStatsStore()
        store.attachRealIp("ghost.com", listOf("1.1.1.1"))
        assertEquals(0, store.size())
    }

    @Test
    fun `attachRealIp keeps two slots and exposes the truncation via realIpTotal`() {
        val store = DomainStatsStore()
        store.recordClose("a.com", false, 443, 0L, 0L, 0L, CloseKind.REMOTE_EOF, 0L)

        // 单地址: 只占第一槽, 没有截断 (+0 不显示)
        store.attachRealIp("a.com", listOf("1.1.1.1"))
        val one = store.snapshot().single()
        assertEquals("1.1.1.1", one.realIp)
        assertNull(one.realIp2)
        assertEquals(1, one.realIpTotal)

        // 三地址: 只留前两槽, 但 realIpTotal = 3 → UI 显示 "1.1.1.1 +2"
        store.attachRealIp("a.com", listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"))
        val three = store.snapshot().single()
        assertEquals("1.1.1.1", three.realIp)
        assertEquals("2.2.2.2", three.realIp2)
        assertEquals(3, three.realIpTotal)
    }

    @Test
    fun `attachRealIp replaces the previous pair instead of appending to empty slots`() {
        val store = DomainStatsStore()
        store.recordClose("a.com", false, 443, 0L, 0L, 0L, CloseKind.REMOTE_EOF, 0L)

        store.attachRealIp("a.com", listOf("1.1.1.1", "2.2.2.2"))
        // 第二次解析 (远端视角可能给出不同的 CDN 节点) 必须整对覆盖 —— 追加语义会让第二个地址永远进不来
        store.attachRealIp("a.com", listOf("9.9.9.9", "8.8.8.8"))
        val s = store.snapshot().single()
        assertEquals("9.9.9.9", s.realIp)
        assertEquals("8.8.8.8", s.realIp2)
        assertEquals(2, s.realIpTotal)
    }

    @Test
    fun `attachRealIp ignores an empty result`() {
        val store = DomainStatsStore()
        store.recordClose("a.com", false, 443, 0L, 0L, 0L, CloseKind.REMOTE_EOF, 0L)
        store.attachRealIp("a.com", emptyList())
        val s = store.snapshot().single()
        assertNull(s.realIp)
        assertEquals(0, s.realIpTotal)
    }

    @Test
    fun `snapshot is a copy and does not reflect later writes`() {
        val store = DomainStatsStore()
        store.recordClose("a.com", false, 443, 10L, 20L, 0L, CloseKind.REMOTE_EOF, 1L)
        val before = store.snapshot().single()

        store.recordClose("a.com", false, 80, 5L, 5L, 0L, CloseKind.REMOTE_EOF, 2L)
        val after = store.snapshot().single()

        assertEquals(1L, before.connCount)
        assertEquals(10L, before.upBytes)
        assertEquals(443, before.lastPort)
        assertEquals(2L, after.connCount)
        assertEquals(15L, after.upBytes)
        assertEquals(80, after.lastPort)
    }

    @Test
    fun `clear resets size and trimmed total`() {
        val store = DomainStatsStore()
        val old = 1_000L
        store.recordClose("a.com", false, 443, 0L, 0L, old, CloseKind.REMOTE_EOF, old)
        store.recordClose("b.com", false, 443, 0L, 0L, old, CloseKind.REMOTE_EOF, old)
        // 全部超窗 → 被扫掉, 顺带把 trimmedTotal 抬起来
        assertTrue(store.trimNowForTest(old + windowMs + 1) > 0)
        assertTrue(store.trimmedTotal() > 0L)

        store.clear()
        assertEquals(0, store.size())
        assertEquals(0L, store.trimmedTotal())
    }

    @Test
    fun `overflow trim happens only in trim, never in recordClose`() {
        val store = DomainStatsStore()
        val now = 1_000_000L
        repeat(DomainStatsStore.MAX_DOMAINS + 1) { i ->
            store.recordClose("d$i.com", false, 443, 1L, 1L, now, CloseKind.REMOTE_EOF, now)
        }

        // recordClose 只置标志、不排序: 超限后 size 仍 > MAX (这条锁死"trim 不在热路径")。
        assertTrue(store.size() > DomainStatsStore.MAX_DOMAINS)
        assertEquals(DomainStatsStore.MAX_DOMAINS + 1, store.size())

        val removed = store.trimNowForTest(now)
        assertTrue(removed > 0)
        assertEquals(DomainStatsStore.MAX_DOMAINS * 9 / 10, store.size())
        assertTrue(store.trimmedTotal() > 0L)
    }

    @Test
    fun `maybeTrim drops entries outside the retention window`() {
        val store = DomainStatsStore()
        val old = 1_000L
        val now = old + windowMs + 1
        store.recordClose("stale.com", false, 443, 1L, 1L, old, CloseKind.REMOTE_EOF, old)
        store.recordClose("fresh.com", false, 443, 1L, 1L, now, CloseKind.REMOTE_EOF, now)

        assertEquals(1, store.maybeTrim(now))
        assertEquals(1, store.size())
        assertEquals("fresh.com", store.snapshot().single().domain)
    }

    @Test
    fun `diagnostics format matches`() {
        val store = DomainStatsStore()
        assertEquals("域名统计 0/${DomainStatsStore.MAX_DOMAINS}(淘汰 0)", store.diagnostics())
        store.recordClose("a.com", false, 443, 0L, 0L, 0L, CloseKind.REMOTE_EOF, 0L)
        assertEquals("域名统计 1/${DomainStatsStore.MAX_DOMAINS}(淘汰 0)", store.diagnostics())
    }

    @Test
    fun `concurrent recordClose does not lose updates`() {
        val store = DomainStatsStore()
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                try {
                    start.await()
                    repeat(perThread) {
                        store.recordClose("hot.com", false, 443, 10L, 20L, 0L, CloseKind.REMOTE_EOF, 1L)
                    }
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        start.countDown()
        if (!done.await(30, TimeUnit.SECONDS)) fail("并发 recordClose 未在 30s 内完成")

        val s = store.snapshot().single()
        assertEquals(1600L, s.connCount)
        assertEquals(16_000L, s.upBytes)
        assertEquals(32_000L, s.downBytes)
    }

    @Test
    fun `maybeTrim is a no-op within the trim interval`() {
        val store = DomainStatsStore()
        val t0 = 1_000_000L
        assertEquals(0, store.maybeTrim(t0)) // 首次: 消费本窗口

        repeat(DomainStatsStore.MAX_DOMAINS + 1) { i ->
            store.recordClose("e$i.com", false, 443, 1L, 1L, t0, CloseKind.REMOTE_EOF, t0)
        }
        val oversized = store.size()

        // 窗口内二次调用: 直接返回 0, 且不做任何淘汰 (size 不变)
        assertEquals(0, store.maybeTrim(t0 + DomainStatsStore.TRIM_INTERVAL_MS - 1))
        assertEquals(oversized, store.size())

        // 越过窗口后才真正淘汰
        assertTrue(store.maybeTrim(t0 + DomainStatsStore.TRIM_INTERVAL_MS) > 0)
    }
}
