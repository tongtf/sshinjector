package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 数据包处理统计，跨模块共享。
 *
 * 热点路径只累加 AtomicLong (无 CAS/无 collector 唤醒), 由 syncToFlows() 节流
 * 发布到 errors (由 PacketProcessor 的统计协程每 100ms 调用)。
 */
class PacketStats {
    private val errorCount = AtomicLong(0)

    val errors = MutableStateFlow(0L)

    fun addError() {
        errorCount.incrementAndGet()
    }

    fun syncToFlows() {
        errors.value = errorCount.get()
    }
}
