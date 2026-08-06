package cn.srv0.sshinjector.domain.vpn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH 阻塞 IO 专用动态线程池。
 *
 * JSch 通道读写是阻塞且不可挂起, 每个活跃连接的回向读稳定占 1 线程, 因此池大小
 * 必须覆盖并发连接上限, 否则连接多时读协程排队导致无响应/降速。若直接跑在
 * Dispatchers.IO 上又会占满其 64 线程上限, 拖垮 DNS/业务协程。
 *
 * 用 ThreadPoolExecutor + SynchronousQueue 实现负载驱动动态伸缩:
 * - 空闲时收缩到 core(16) 线程, 省调度开销
 * - 连接多时增长到连接数(上限 max=128), 覆盖多页面并发
 * - 空闲线程 60s 后回收
 */
@Singleton
class SshIoDispatcher
    @Inject
    constructor() {
        val dispatcher: CoroutineDispatcher = createDispatcher()

        private fun createDispatcher(): CoroutineDispatcher {
            val executor =
                ThreadPoolExecutor(
                    CORE_THREADS,
                    MAX_THREADS,
                    KEEP_ALIVE_SECONDS,
                    TimeUnit.SECONDS,
                    SynchronousQueue(),
                    { r -> Thread(r, "ssh-io").also { it.isDaemon = true } },
                    ThreadPoolExecutor.CallerRunsPolicy(),
                )
            return executor.asCoroutineDispatcher()
        }

        companion object {
            private const val CORE_THREADS = 16
            private const val MAX_THREADS = 128
            private const val KEEP_ALIVE_SECONDS = 60L
        }
    }
