package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.BackpressureGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证生产类 [BackpressureGate] (出向 Channel + 连接级单槽挂起) 在并发下不丢数据、不乱序。
 *
 * 2026-09 前该测试测的是自建模拟实现 (Channel+AtomicReference+锁), 生产的
 * backpressureLock/enqueueData/resumeLocalReadIfSpace 零覆盖 —— 而这里正是历史死锁
 * 事故点。抽取 BackpressureGate 后, 测试与生产共用同一份 [trySend+单槽发布] 原子逻辑。
 *
 * handoff 竞态说明: "trySend 失败" 与 "单槽发布" 必须原子(同一把锁),
 * 否则 writer 可能在这个窗口内把 Channel 抽干并挂起在空 Channel 上,
 * 生产者随后 park 在单槽上 → 永久死锁 (CI 慢 runner 上可复现)。
 */
class Socks5BackpressureTest {
    @Test
    fun `channel outgoing backpressure preserves order and no data loss`() =
        runBlocking {
            val lock = Any()
            val blockedEvents = AtomicInteger(0)
            val resumedEvents = AtomicInteger(0)
            // 小容量让单槽/暂停路径必然被打到
            val gate =
                BackpressureGate(
                    capacity = 4,
                    lock = lock,
                    onBlocked = { blockedEvents.incrementAndGet() },
                    onResumed = { resumedEvents.incrementAndGet() },
                )
            val produced = 10_000
            val consumed = AtomicInteger(0)
            val lastSeen = AtomicInteger(-1)

            // 写协程: 挂起接收, 消费后回填单槽 (对应生产 startSshWriteLoop → resumeIfSpace)
            val writer =
                launch(Dispatchers.Default) {
                    for (data in gate.outgoing) {
                        val v = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        assertTrue("reordered v=$v last=${lastSeen.get()}", v > lastSeen.get())
                        lastSeen.set(v)
                        consumed.incrementAndGet()
                        gate.resumeIfSpace()
                    }
                }

            // 生产者: 满时数据挂到连接级单槽(不丢, 已接受), 等回填后再继续
            var i = 0
            while (i < produced) {
                val data =
                    ByteArray(2).also {
                        it[0] = (i ushr 8).toByte()
                        it[1] = (i and 0xFF).toByte()
                    }
                val accepted = gate.enqueue(data)
                i++
                if (!accepted) {
                    val refillDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (gate.isBlocked) {
                        // 有界等待: 若写协程异常终止, 30s 后转为明确失败而非永久挂起 (CI 曾 26min 超时)
                        if (System.nanoTime() > refillDeadline) {
                            fail("backpressure deadlock: pending slot never refilled (writer coroutine died?)")
                        }
                        yield()
                    }
                }
            }
            // 不 close: 与生产一致 (channel 生命周期随协程作用域结束)
            writer.cancel()
            writer.join()

            assertEquals("data loss accepted=$produced consumed=${consumed.get()}", produced, consumed.get())
            assertEquals("last value", produced - 1, lastSeen.get())
            // 生产上这个计数是「背压触发 N 次」诊断的来源, 闸必须真的触发过
            assertTrue("blocked path never taken", blockedEvents.get() > 0)
        }

    @Test
    fun `single slot refills in order and stays blocked until consumer drains`() {
        val blocked = AtomicInteger(0)
        val resumed = AtomicInteger(0)
        val gate =
            BackpressureGate(
                capacity = 1,
                lock = Any(),
                onBlocked = { blocked.incrementAndGet() },
                onResumed = { resumed.incrementAndGet() },
            )

        fun payload(v: Int) = byteArrayOf(v.toByte())

        // channel 容量 1: 第一条进队, 第二条挂单槽并触发一次 onBlocked
        assertTrue(gate.enqueue(payload(1)))
        assertFalse(gate.enqueue(payload(2)))
        assertEquals(1, blocked.get())
        assertTrue(gate.isBlocked)

        // 未消费时回填: Channel 仍满 → 单槽保留, 维持挂起 (不能丢也不能覆盖)
        gate.resumeIfSpace()
        assertTrue(gate.isBlocked)
        assertEquals(0, resumed.get())

        // 消费一条 → 回填成功 → 解除挂起, 消费顺序仍是 1 → 2
        val first = gate.outgoing.tryReceive().getOrNull()
        assertEquals(1, first!![0].toInt())
        gate.resumeIfSpace()
        assertFalse(gate.isBlocked)
        assertEquals(1, resumed.get())
        val second = gate.outgoing.tryReceive().getOrNull()
        assertEquals(2, second!![0].toInt())
    }
}
