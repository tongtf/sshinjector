package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.SshIoDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 验证 ssh-io 动态线程池在并发阻塞任务(模拟每个连接的回向阻塞读)下能增长覆盖,
 * 不会因固定小池而排队导致无响应。
 */
class SshIoDispatcherTest {
    @Test
    fun `pool grows to cover 40 concurrent blocking readers`() =
        runBlocking {
            val dispatcher = SshIoDispatcher().dispatcher
            val tasks = 40
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(tasks)

            val jobs =
                (0 until tasks).map {
                    launch(dispatcher) {
                        startLatch.await() // 模拟回向阻塞读
                        doneLatch.countDown()
                    }
                }
            startLatch.countDown()
            assertTrue("40 concurrent blocking tasks not done in 10s", doneLatch.await(10, TimeUnit.SECONDS))
            jobs.forEach { it.join() }
        }
}
