package cn.srv0.sshinjector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 验证 Socks5ProxyServer 出向背压(Channel + 连接级单槽挂起)在并发下不丢数据、不乱序。
 * 模拟: eventLoop trySend → 满则挂到单槽并暂停生产(suspendLocalRead) → 写协程消费后回填。
 *
 * handoff 竞态说明: "trySend 失败" 与 "pending.set(data)" 必须原子(同一把锁),
 * 否则 writer 可能在这个窗口内把 buffer 抽干并挂起在空 channel 上,
 * 生产者随后 park 在 pending != null → 永久死锁 (CI 慢 runner 上可复现)。
 * 生产代码 Socks5ProxyServer 的 backpressureLock 正是覆盖 [trySend + slot 发布] 这一段。
 */
class Socks5BackpressureTest {
    @Test
    fun `channel outgoing backpressure preserves order and no data loss`() =
        runBlocking {
            val channel =
                Channel<ByteArray>(
                    capacity = 4,
                    onBufferOverflow = BufferOverflow.SUSPEND,
                )
            val pending = AtomicReference<ByteArray?>(null)
            // 模拟生产代码 backpressureLock: 保护 [trySend 失败 → slot 发布] 与 writer 的 [接收 → 回填检查]
            val handoffLock = Any()
            val produced = 10_000
            val consumed = AtomicInteger(0)
            val lastSeen = AtomicInteger(-1)

            // 写协程: for 挂起接收, 消费后回填挂起块 (resumeLocalReadIfSpace)
            val writer =
                launch(Dispatchers.Default) {
                    for (data in channel) {
                        val v = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        assertTrue("reordered v=$v last=${lastSeen.get()}", v > lastSeen.get())
                        lastSeen.set(v)
                        consumed.incrementAndGet()
                        synchronized(handoffLock) {
                            val p = pending.get()
                            if (p != null && channel.trySend(p).isSuccess) pending.set(null)
                        }
                    }
                }

            // 生产者: 满时数据挂到连接级单槽(不丢, 已接受), 暂停生产(等回填)再继续, 模拟 suspendLocalRead
            var i = 0
            while (i < produced) {
                val data =
                    ByteArray(2).also {
                        it[0] = (i ushr 8).toByte()
                        it[1] = (i and 0xFF).toByte()
                    }
                var accepted = false
                synchronized(handoffLock) {
                    if (channel.trySend(data).isSuccess) {
                        accepted = true
                    } else {
                        // 满: 数据挂到连接级单槽(不丢, 已接受)。与 trySend 同锁发布,
                        // 保证 writer 的下一轮 [接收→检查] 必然看到它, 不会抽干后挂起。
                        pending.set(data)
                    }
                }
                i++
                if (!accepted) {
                    val refillDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (pending.get() != null) {
                        // 有界等待: 若写协程异常终止, 30s 后转为明确失败而非永久挂起 (CI 曾 26min 超时)
                        if (System.nanoTime() > refillDeadline) {
                            fail("backpressure deadlock: pending slot never refilled (writer coroutine died?)")
                        }
                        yield()
                    }
                }
            }
            channel.close()
            writer.join()

            assertEquals("data loss accepted=$produced consumed=${consumed.get()}", produced, consumed.get())
            assertEquals("last value", produced - 1, lastSeen.get())
        }
}
