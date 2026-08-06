package cn.srv0.sshinjector.domain.vpn

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 有界背压队列：单生产者(事件循环/packetLoop)入队、单消费者协程出队。
 *
 * - 队列满时挂起当前块(不丢弃、不乱序)，由 drainSuspended 在腾空后按序回填
 * - 挂起槽也已占满时 offer 返回 false，调用方以此暂停读取(OP_READ)或暂停 ACK 背压
 * - 多线程安全：offer/drainSuspended 共享一把锁，poll 走线程安全队列
 */
class BoundedBackpressureQueue(private val capacity: Int) {
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    private val lock = Any()
    private val suspended = ArrayDeque<ByteArray>()

    /**
     * 尝试入队。先排空挂起块(保持 FIFO)，再入队当前块。
     * @return true 表示当前块已进入管线(队列或挂起槽); false 表示挂起槽已满, 应暂停生产
     */
    fun offer(block: ByteArray): Boolean {
        synchronized(lock) {
            while (suspended.isNotEmpty()) {
                val pending = suspended.removeFirst()
                if (!queue.offer(pending)) {
                    suspended.addFirst(pending)
                    return false
                }
            }
            if (queue.offer(block)) {
                return true
            }
            suspended.addLast(block)
            return true
        }
    }

    /**
     * 消费者出队, 与 poll 同源; 超时返回 null。
     */
    fun poll(
        timeout: Long,
        unit: TimeUnit,
    ): ByteArray? {
        return try {
            queue.poll(timeout, unit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * 队列腾出空间后调用: 把挂起块按序回填, 保证有序不丢。
     */
    fun drainSuspended() {
        synchronized(lock) {
            while (suspended.isNotEmpty()) {
                val pending = suspended.removeFirst()
                if (!queue.offer(pending)) {
                    suspended.addFirst(pending)
                    break
                }
            }
        }
    }

    fun remainingCapacity(): Int = queue.remainingCapacity()
}
