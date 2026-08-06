package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.BoundedBackpressureQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedBackpressureQueueTest {
    private fun block(seed: Int): ByteArray = byteArrayOf(seed.toByte())

    @Test
    fun `offer returns true until capacity is exhausted`() {
        val q = BoundedBackpressureQueue(3)
        assertTrue(q.offer(block(1)))
        assertTrue(q.offer(block(2)))
        assertTrue(q.offer(block(3)))
        // 队列满 → 挂起, 仍算接受
        assertTrue(q.offer(block(4)))
    }

    @Test
    fun `offer rejects when suspended slot also full`() {
        val q = BoundedBackpressureQueue(2)
        assertTrue(q.offer(block(1)))
        assertTrue(q.offer(block(2))) // 满
        assertTrue(q.offer(block(3))) // 挂起
        // 挂起槽已占满 → 拒绝
        assertFalse(q.offer(block(4)))
    }

    @Test
    fun `suspended blocks drain in FIFO order after space frees`() {
        val q = BoundedBackpressureQueue(2)
        q.offer(block(1))
        q.offer(block(2)) // 满
        q.offer(block(3)) // 挂起: 3
        assertFalse(q.offer(block(4))) // 挂起槽满, 拒绝

        // 消费一个 → 腾出空间 → 回填挂起块
        assertEquals(1, q.poll(0, TimeUnit.MILLISECONDS)!![0].toInt())
        q.drainSuspended()
        // 顺序保持: 2, 3
        assertEquals(2, q.poll(0, TimeUnit.MILLISECONDS)!![0].toInt())
        assertEquals(3, q.poll(0, TimeUnit.MILLISECONDS)!![0].toInt())
        assertNull(q.poll(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `suspended blocks preserve global FIFO across multiple drains`() {
        val q = BoundedBackpressureQueue(2)
        q.offer(block(1))
        q.offer(block(2)) // 满
        q.offer(block(3)) // 挂起
        q.offer(block(4)) // 挂起槽满, 拒绝
        q.offer(block(5)) // 拒绝

        val out = mutableListOf<Int>()
        while (true) {
            val data = q.poll(0, TimeUnit.MILLISECONDS) ?: break
            out.add(data[0].toInt())
            q.drainSuspended()
        }
        assertEquals(listOf(1, 2, 3), out)
    }

    @Test
    fun `concurrent producer and consumer never lose duplicate or reorder data`() {
        val q = BoundedBackpressureQueue(4)
        val produced = 10_000
        val seen = ConcurrentHashMap.newKeySet<Int>()
        val consumed = AtomicInteger(0)
        val lastSeen = AtomicInteger(-1)
        val producerDone = AtomicBoolean(false)

        val consumer =
            Thread {
                var idle = 0
                while (true) {
                    var data = q.poll(10, TimeUnit.MILLISECONDS)
                    if (data == null) {
                        // 队列空时先把挂起块回填再重试, 避免漏消费
                        q.drainSuspended()
                        data = q.poll(0, TimeUnit.MILLISECONDS)
                    }
                    if (data == null) {
                        // 生产者未完成时不计入 idle; 完成后连续超时才退出(队列必然已清空)
                        if (producerDone.get() && ++idle >= 20) break
                    } else {
                        idle = 0
                        val value = data[0].toInt()
                        assertTrue("reordered or duplicated value=$value", value > lastSeen.get())
                        lastSeen.set(value)
                        seen.add(value)
                        consumed.incrementAndGet()
                    }
                }
            }
        consumer.start()

        var accepted = 0
        for (i in 0 until produced) {
            if (q.offer(block(i))) accepted++
        }
        producerDone.set(true)
        consumer.join(10_000)

        // 所有被接受的块都被消费, 且无重复
        assertEquals("accepted=$accepted consumed=${consumed.get()} seen=${seen.size}", accepted, consumed.get())
        assertEquals("accepted=$accepted consumed=${consumed.get()} seen=${seen.size}", accepted, seen.size)
    }
}
