package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH 阻塞 IO 专用受限调度器。
 *
 * JSch 通道的读写是阻塞的。若全部跑在 Dispatchers.IO 上，并发连接一多就会打满
 * 其 64 线程上限，拖垮整个应用的协程。这里用受限并行度的子调度器把 SSH 阻塞 IO
 * 隔离在固定数量线程内，其余 IO 线程留给轻量计算。
 */
@Singleton
class SshIoDispatcher
    @Inject
    constructor() {
        val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(SSH_IO_PARALLELISM)

        companion object {
            private const val SSH_IO_PARALLELISM = 8
        }
    }
