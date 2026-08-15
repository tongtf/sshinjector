package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 数据包处理统计，跨模块共享。
 *
 * 热点路径只累加 AtomicLong (无 CAS/无 collector 唤醒), 由 syncToFlows() 节流
 * 发布到 StateFlow (由 PacketProcessor 的统计协程每 100ms 调用)。
 */
class PacketStats {
    private val packets = AtomicLong(0)
    private val bytes = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    val packetsProcessed = MutableStateFlow(0L)
    val bytesProcessed = MutableStateFlow(0L)
    val errors = MutableStateFlow(0L)

    fun addPacket(byteCount: Long) {
        packets.incrementAndGet()
        bytes.addAndGet(byteCount)
    }

    fun addError() {
        errorCount.incrementAndGet()
    }

    fun syncToFlows() {
        packetsProcessed.value = packets.get()
        bytesProcessed.value = bytes.get()
        errors.value = errorCount.get()
    }
}
