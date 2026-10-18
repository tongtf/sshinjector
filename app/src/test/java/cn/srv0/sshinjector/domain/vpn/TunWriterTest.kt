package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TunWriter] 的单写者语义: FIFO 顺序、满队列时"控制包丢 / 数据包等"、写失败计数、
 * close 排空并释放等待者。
 *
 * 全部用假 OutputStream 在纯 JVM 上跑 (无需设备)。所有等待**有界**并显式 fail ——
 * AGENTS.md 记录过无界 `await` 把 CI 挂死 26 分钟的前例。
 */
class TunWriterTest {
    /** 记录写入内容, 可选: 进入 write 时置位 [entered], 然后等 [gate] 放开。 */
    private class GateSink(
        private val entered: CountDownLatch? = null,
        private val gate: CountDownLatch? = null,
        private val failEveryWrite: Boolean = false,
    ) : OutputStream() {
        val written: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val bytes = ByteArrayOutputStream()

        override fun write(b: Int): Unit = throw AssertionError("TunWriter 必须以整个 byte[] 写入, 不应调用单字节 write")

        override fun write(b: ByteArray) {
            entered?.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            if (failEveryWrite) throw IOException("boom")
            bytes.write(b)
            written += String(b, Charsets.UTF_8)
        }
    }

    private fun awaitTrue(
        what: String,
        deadlineMs: Long = 5000,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(2)
        }
        fail("超时等待: $what")
    }

    private fun runAsync(block: () -> Unit): CountDownLatch {
        val done = CountDownLatch(1)
        Thread {
            block()
            done.countDown()
        }.apply { isDaemon = true }.start()
        return done
    }

    // ---------------------------------------------------------------- 顺序

    @Test
    fun `writes reach the stream in enqueue order`() {
        val sink = GateSink()
        val writer = TunWriter(onWritten = {})
        writer.attach(sink)
        for (i in 1..50) assertTrue(writer.write("c$i".toByteArray()))
        for (i in 1..50) assertTrue(writer.writeBlocking("d$i".toByteArray()))
        writer.close()

        val expected = (1..50).joinToString("") { "c$it" } + (1..50).joinToString("") { "d$it" }
        assertEquals(expected, sink.bytes.toString("UTF-8"))
    }

    // ------------------------------------------------- 满队列: 控制丢 / 数据等

    @Test
    fun `a full queue drops control packets but blocks data writes`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val dropped = AtomicInteger()
        val sink = GateSink(entered, gate)
        val writer = TunWriter(capacity = 2, onWritten = {}, onControlDropped = { dropped.addAndGet(it) })
        writer.attach(sink)

        // 第 1 包被写线程取走并卡在 gate 上 → 队列空出 2 个位置
        assertTrue(writer.write("a".toByteArray()))
        assertTrue("写线程未取走第 1 包", entered.await(5, TimeUnit.SECONDS))

        assertTrue(writer.write("b".toByteArray()))
        assertTrue(writer.write("c".toByteArray()))
        assertFalse("队列满时必须丢弃控制包", writer.write("d".toByteArray()))
        assertEquals(1, dropped.get())

        var dataResult = false
        val dataDone =
            runAsync {
                dataResult = writer.writeBlocking("e".toByteArray())
            }
        // 队列仍满 → 数据写入必须一直等 (既不返回也不丢 —— 丢了就是回程永久洞)
        assertFalse("队列满时数据写入不得提前返回", dataDone.await(300, TimeUnit.MILLISECONDS))

        gate.countDown()
        assertTrue("放开写线程后数据写入应完成", dataDone.await(5, TimeUnit.SECONDS))
        assertTrue(dataResult)

        writer.close()
        assertEquals(listOf("a", "b", "c", "e"), sink.written.toList())
    }

    @Test
    fun `a data write that cannot enqueue within the budget is counted as failure`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val failures = AtomicInteger()
        val writer =
            TunWriter(
                capacity = 1,
                queueWaitTimeoutMs = 200,
                onWriteFailed = { failures.incrementAndGet() },
            )
        writer.attach(GateSink(entered, gate))

        // 写线程卡住 → 队列满且永不腾出空间
        assertTrue(writer.write("a".toByteArray()))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(writer.write("b".toByteArray()))

        assertFalse("队列长时间满 → 回程数据写入必须失败返回 (不能无限等)", writer.writeBlocking("c".toByteArray()))
        assertTrue("必须计数为失败 (可观测), 而不是静默丢弃", failures.get() >= 1)
        gate.countDown()
        writer.close()
    }

    // ---------------------------------------------------------------- 失败

    @Test
    fun `a stream failure is reported and fails the blocking write`() {
        val failures = AtomicInteger()
        val writtenBytes = AtomicInteger()
        val writer =
            TunWriter(
                onWritten = { writtenBytes.addAndGet(it) },
                onWriteFailed = { failures.incrementAndGet() },
            )
        writer.attach(
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("boom")

                override fun write(b: ByteArray) = throw IOException("boom")
            },
        )

        assertFalse(writer.writeBlocking(ByteArray(10)))
        assertEquals(1, failures.get())
        assertEquals("写失败不得计入成功字节", 0, writtenBytes.get())
        writer.close()
    }

    @Test
    fun `writes without an attached stream count as failures`() {
        val failures = AtomicInteger()
        val writer = TunWriter(onWriteFailed = { failures.incrementAndGet() })

        // 控制包仍然入队成功 (不能阻塞 packetLoop), 只是写的时候算失败
        assertTrue(writer.write(ByteArray(4)))
        // 数据写入返回真实结果: 没写出去就是 false
        assertFalse(writer.writeBlocking(ByteArray(4)))
        awaitTrue("未挂载流时的写入必须计失败") { failures.get() >= 2 }
        writer.close()
    }

    // ---------------------------------------------------------------- 生命周期

    @Test
    fun `close drains what was already queued and exits the write thread`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val sink = GateSink(entered, gate)
        val writer = TunWriter(capacity = 8, joinTimeoutMs = 2000, onWritten = {})
        writer.attach(sink)

        assertTrue(writer.write("x".toByteArray()))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(writer.write("y".toByteArray()))
        assertTrue(writer.write("z".toByteArray()))
        gate.countDown()

        writer.close()
        assertEquals("close 必须排空已入队的数据再退出", "xyz", sink.bytes.toString("UTF-8"))
    }

    @Test
    fun `close releases a data write whose writer thread is stuck`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val failures = AtomicInteger()
        val writer =
            TunWriter(
                capacity = 4,
                joinTimeoutMs = 200,
                onWriteFailed = { failures.incrementAndGet() },
            )
        writer.attach(GateSink(entered, gate))

        assertTrue(writer.write("a".toByteArray())) // 被写线程取走并卡在 gate
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        var result = true
        val done = runAsync { result = writer.writeBlocking("b".toByteArray()) }
        // 容量 4 → 立即入队成功; 给异步线程一点时间落到"等待写入"状态
        Thread.sleep(100)
        assertFalse("写线程卡死时数据写入应保持等待", done.await(200, TimeUnit.MILLISECONDS))

        writer.close() // 写线程卡死 → join 超时 → 必须释放等待者
        assertTrue("close 必须释放仍在等待的回程写入", done.await(5, TimeUnit.SECONDS))
        assertFalse("写线程卡死时该写入按失败结算", result)
        assertTrue(failures.get() >= 1)
        gate.countDown()
    }

    @Test
    fun `writes after close are rejected without being counted as data plane failures`() {
        val failures = AtomicInteger()
        val dropped = AtomicInteger()
        val writer =
            TunWriter(
                onWriteFailed = { failures.incrementAndGet() },
                onControlDropped = { dropped.addAndGet(it) },
            )
        writer.attach(GateSink())
        writer.close()

        assertFalse(writer.write(ByteArray(4)))
        assertFalse(writer.writeBlocking(ByteArray(4)))
        assertEquals("会话已结束的写入不是数据面故障, 不得计数", 0, failures.get())
        assertEquals(0, dropped.get())
        writer.close() // 幂等
    }
}
