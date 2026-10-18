package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TUN 单写者: 把"谁产生了包"和"谁写 TUN"解耦。
 *
 * 旧实现是 `@Synchronized fun writeToTun(data)` —— packetLoop 的 ACK、DNS 投递协程、每条 SSH 回程
 * 线程抢同一把 monitor, TUN 一慢就**跨连接**排队 (head-of-line), 被拖慢的是与那条连接无关的所有人。
 *
 * 这里改成: 调用方只入队 ([write] 满了丢、[writeBlocking] 满了等), 一个守护线程按 FIFO 真写。于是
 * - **顺序由结构保证** (同一时刻只有一个线程在写), 不再依赖调用方之间的互斥协议;
 * - packetLoop 写控制包**永不阻塞**;
 * - 回程数据只阻塞它自己所在的那条连接线程 (本来就是每连接独占一条), 不波及其它连接。
 *
 * **注意**: 本类**不声称** `OutputStream.write(byte[])` 的整包原子性已被证明 —— 那需要真机/驱动
 * 实测 (见 `AGENTS.md` 的 T-20 遗留项)。它只保证"同一时刻只有一个线程在写"这件事不再需要
 * 调用方配合, 因此不再需要一把跨所有调用方的锁。
 *
 * 生命周期: `connect` 时 [attach] 输出流, `disconnect` 时 [close] (先排空再退出)。关闭后不可复用,
 * 必须新建实例。
 *
 * @param capacity 队列容量 (包数)。满时控制包被丢并计数, 数据包阻塞等待 —— 有界, 不会无限堆积。
 * @param joinTimeoutMs [close] 等待写线程退出的上限 (写线程可能正卡在 TUN 写上)。
 * @param onWritten 成功写入回调 (字节数) —— 计数必须在这里做, 保证"只有真写出去才算"。
 * @param onWriteFailed 写入失败回调 (null = 尚未挂载输出流; 否则为写入抛出的异常)。
 * @param onControlDropped 控制包因队列满被丢弃回调 (字节数)。
 */
class TunWriter(
    private val capacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
    private val queueWaitTimeoutMs: Long = DEFAULT_QUEUE_WAIT_TIMEOUT_MS,
    private val onWritten: (Int) -> Unit = {},
    private val onWriteFailed: (Throwable?) -> Unit = {},
    private val onControlDropped: (Int) -> Unit = {},
) : TunSink,
    AutoCloseable {
    private class Pending(
        val data: ByteArray,
        val done: CountDownLatch? = null,
    ) {
        @Volatile var written: Boolean = false
    }

    private val queue = ArrayBlockingQueue<Pending>(capacity)

    /** false = 不再接收新写入 (close 已开始)。写线程据此判断"排空后即可退出"。 */
    private val accepting = AtomicBoolean(true)

    @Volatile private var stream: OutputStream? = null

    private val writer =
        Thread(::drainLoop, THREAD_NAME).apply {
            isDaemon = true
            // 略高于普通线程: 它是所有回程数据的公共出口, 排队越短整体延迟越低
            priority = Thread.NORM_PRIORITY + 1
        }

    init {
        writer.start()
    }

    /** 挂上本次会话的 TUN 输出流。必须在 [close] 之前调用; 未挂载时的写入按失败结算。 */
    fun attach(stream: OutputStream) {
        this.stream = stream
    }

    override fun write(data: ByteArray): Boolean {
        // 关闭后的写入不计数: 会话已结束, 这不是数据面故障 (计进去会污染快照/健康窗口)
        if (!accepting.get()) return false
        val queued = queue.offer(Pending(data))
        if (!queued) {
            onControlDropped(data.size)
            Log.w(TAG, "TUN 写队列已满, 丢弃控制包 ${data.size}B (客户端会重传, 不影响数据面)")
        }
        return queued
    }

    override fun writeBlocking(data: ByteArray): Boolean {
        if (!accepting.get()) return false
        val pending = Pending(data, CountDownLatch(1))
        if (!enqueue(pending, data)) return false
        // 入队后无超时等待: 与旧实现 (阻塞 FileOutputStream.write) 语义一致。释放路径有两条 ——
        // 写线程处理到它, 或 close() 的 failRemaining 按失败结算; 写线程本身绝不会因单次
        // 写失败而退出 (见 drainLoop), 所以不会永久搁浅。
        pending.done?.await()
        return pending.written
    }

    /**
     * 有界等待入队。队列长时间腾不出空间 = 数据面已故障 (写线程卡死或 TUN 完全不消费):
     * 既不能无限等 (回程线程搁浅), 也不能丢 (回程丢了是永久洞), 只能计一次失败返回 false
     * —— 这是唯一可观测且不破坏数据的选择。
     */
    private fun enqueue(
        pending: Pending,
        data: ByteArray,
    ): Boolean {
        val deadline = System.currentTimeMillis() + queueWaitTimeoutMs
        while (accepting.get()) {
            try {
                if (queue.offer(pending, POLL_MS, TimeUnit.MILLISECONDS)) return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (System.currentTimeMillis() > deadline) {
                onWriteFailed(null)
                Log.e(TAG, "TUN 写队列 ${queueWaitTimeoutMs}ms 未腾出空间, 回程数据写入失败 (${data.size}B)")
                return false
            }
        }
        return false // 会话已关闭: 静默返回 (不是数据面故障, 不计数)
    }

    /**
     * 停止接收 + 排空 + 退出写线程。幂等。
     *
     * 排空语义: 写线程只有在"队列已空 **且** 不再接收"时才退出, 因此 close 之前入队的数据
     * (包括与 close 竞争的那几包) 一定会被写出或按失败结算 —— 不会静默滞留。
     */
    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        try {
            writer.join(joinTimeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 无论写线程是否退出, 队列里残留的等待者一律释放 (写线程卡在 TUN 写时这是唯一出路,
        // 否则回程线程会一起搁浅)
        failRemaining()
        if (writer.isAlive) {
            Log.w(TAG, "tun-writer 未在 ${joinTimeoutMs}ms 内退出 (TUN 写疑似卡住); 剩余数据按失败结算")
        }
        stream = null
    }

    private fun drainLoop() {
        while (true) {
            val item =
                try {
                    queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    accepting.set(false)
                    failRemaining()
                    return
                }
            if (item == null) {
                // 队列空 + 不再接收 → 退出。反过来说, close 之前入队的包一定被处理过。
                if (!accepting.get()) return
                continue
            }
            try {
                writeOne(item)
            } catch (t: Throwable) {
                // 回调 (计数/日志) 抛异常也绝不能让写线程死掉 —— 死了就是所有回程数据永久堆积
                item.written = false
                item.done?.countDown()
                Log.e(TAG, "tun-writer 处理异常: ${t::class.simpleName}: ${t.message}", t)
            }
        }
    }

    private fun writeOne(item: Pending) {
        try {
            val out = stream
            if (out == null) {
                onWriteFailed(null)
                return
            }
            out.write(item.data)
            item.written = true
            onWritten(item.data.size)
        } catch (e: Exception) {
            item.written = false
            onWriteFailed(e)
            Log.e(TAG, "TUN 写入失败: ${e::class.simpleName}: ${e.message}", e)
        } finally {
            item.done?.countDown()
        }
    }

    /** 释放所有仍在队列里的等待者 (只在写线程卡死时调用)。 */
    private fun failRemaining() {
        while (true) {
            val item = queue.poll() ?: return
            item.written = false
            item.done?.countDown()
            onWriteFailed(null)
        }
    }

    companion object {
        private const val TAG = "TunWriter"
        private const val THREAD_NAME = "tun-writer"

        /** 队列容量 (包数)。按 MTU 1500 算最多约 384KB 在途; TUN 一慢先丢控制包。 */
        private const val DEFAULT_QUEUE_CAPACITY = 256

        /** close 等待写线程退出的上限。 */
        private const val DEFAULT_JOIN_TIMEOUT_MS = 2000L

        /** 数据写入等待"队列腾出空间"的上限 (超过即判数据面故障)。 */
        private const val DEFAULT_QUEUE_WAIT_TIMEOUT_MS = 10_000L

        /** 写线程空闲轮询间隔 (也是"队列空 + 已关闭"的退出检查粒度)。 */
        private const val POLL_MS = 100L
    }
}
