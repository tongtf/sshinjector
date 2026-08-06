package cn.srv0.sshinjector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 验证 Socks5ProxyServer 出向背压(Channel + 连接级单槽挂起)在并发下不丢数据、不乱序。
 * 模拟: eventLoop trySend → 满则挂到单槽并暂停生产(suspendLocalRead) → 写协程消费后回填。
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
                        val p = pending.get()
                        if (p != null && channel.trySend(p).isSuccess) pending.set(null)
                    }
                }

            // 生产者: 满时数据已接受(挂到单槽), 暂停生产(等回填)再继续, 模拟 suspendLocalRead
            var i = 0
            while (i < produced) {
                val data =
                    ByteArray(2).also {
                        it[0] = (i ushr 8).toByte()
                        it[1] = (i and 0xFF).toByte()
                    }
                if (channel.trySend(data).isSuccess) {
                    i++
                } else {
                    // 满: 数据挂到连接级单槽(不丢, 已接受), 暂停生产直到写协程回填
                    pending.set(data)
                    i++
                    while (pending.get() != null) yield()
                }
            }
            channel.close()
            writer.join()

            assertEquals("data loss accepted=$produced consumed=$consumed", produced, consumed.get())
            assertEquals("last value", produced - 1, lastSeen.get())
        }
}
