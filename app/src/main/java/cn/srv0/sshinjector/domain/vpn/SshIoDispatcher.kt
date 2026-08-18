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
 * - 懒创建: 空闲时 0 线程
 * - 连接多时增长到连接数(上限 max=128), 覆盖多页面并发
 * - allowCoreThreadTimeOut: 所有空闲线程 60s 后回收(含 core), 完全空闲回到 0
 */
@Singleton
class SshIoDispatcher
    @Inject
    constructor() {
        private val executor: ThreadPoolExecutor = createExecutor()

        val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

        /**
         * 线程池是否已饱和 (活跃线程数达到上限)。
         * 调用方 (如 SOCKS5 eventLoop) 在派发阻塞任务前检查, 避免 CallerRunsPolicy
         * 把阻塞 SSH 操作回执到 eventLoop 线程冻结整个代理。
         */
        fun isSaturated(): Boolean = executor.activeCount >= MAX_THREADS

        private fun createExecutor(): ThreadPoolExecutor {
            val e =
                ThreadPoolExecutor(
                    CORE_THREADS,
                    MAX_THREADS,
                    KEEP_ALIVE_SECONDS,
                    TimeUnit.SECONDS,
                    SynchronousQueue(),
                    { r -> Thread(r, "ssh-io").also { it.isDaemon = true } },
                    ThreadPoolExecutor.CallerRunsPolicy(),
                )
            // core 线程空闲同样回收, 完全空闲时回到 0 线程
            e.allowCoreThreadTimeOut(true)
            return e
        }

        companion object {
            private const val CORE_THREADS = 16
            private const val MAX_THREADS = 128
            private const val KEEP_ALIVE_SECONDS = 60L
        }
    }
