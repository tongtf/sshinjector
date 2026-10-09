package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 出向背压闸 (Channel + 连接级单槽挂起): 生产者 [enqueue] 双检 trySend,
 * 队列满时把数据挂到单槽 (不丢, 已"接受") 并通过回调暂停本地读向上游背压;
 * 写协程消费一条后调 [resumeIfSpace] 回填单槽并恢复读。
 *
 * 竞态纪律 (曾造成 CI 慢 runner 上的永久死锁, 修复于 2026-09):
 * **"trySend 失败" 与 "单槽发布" 必须原子** —— 若分开, 写协程可能在这个窗口内
 * 把 Channel 抽干并挂起在 (空且未关闭的) Channel 上, 生产者随后 park 在单槽上 → 双方永等。
 * 因此单槽状态只在 [lock] 内读写, 回调也在锁内执行。
 *
 * [lock] 由调用方传入: Socks5Connection 里同一把锁还保护 interestOps 的 RMW
 * (OP_READ 暂停/恢复与 OP_WRITE 增删可能来自不同线程, 拆锁会丢位)。
 * 单实例对应一条连接, 不做跨连接复用。
 */
internal class BackpressureGate(
    capacity: Int,
    private val lock: Any,
    /** 队列满、单槽已发布 (锁内) —— 调用方在此计背压触发数、暂停本地读。 */
    private val onBlocked: () -> Unit,
    /** 单槽回填成功、解除背压 (锁内) —— 调用方在此恢复本地读。 */
    private val onResumed: () -> Unit,
) {
    private val channel = Channel<ByteArray>(capacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND)

    // 单槽状态: 只在 lock 内访问 (双 volatile 仅为防御性可见性, 正确性靠锁)
    @Volatile private var pending: ByteArray? = null

    @Volatile private var full = false

    /** 写协程消费入口: 无数据时挂起 (不占线程)。 */
    val outgoing: ReceiveChannel<ByteArray>
        get() = channel

    /** 单槽当前是否挂起中 (仅测试/诊断用)。 */
    val isBlocked: Boolean
        get() = full

    /**
     * 出向入队: 双检 trySend, 失败时锁内挂单槽 + 触发背压回调。
     * 返回 true = 数据已进 Channel; false = 已挂单槽 (仍不丢)。
     */
    fun enqueue(data: ByteArray): Boolean {
        if (channel.trySend(data).isSuccess) return true
        synchronized(lock) {
            if (channel.trySend(data).isSuccess) return true
            pending = data
            full = true
            onBlocked()
        }
        return false
    }

    /**
     * 写协程腾出空间后: 锁内回填单槽, 成功后解除背压。
     * Channel 仍满时保持挂起, 下次消费后再试 (由调用方循环调用)。
     */
    fun resumeIfSpace() {
        synchronized(lock) {
            if (!full) return
            val p = pending
            if (p != null) {
                if (!channel.trySend(p).isSuccess) return
                pending = null
            }
            full = false
            onResumed()
        }
    }
}
