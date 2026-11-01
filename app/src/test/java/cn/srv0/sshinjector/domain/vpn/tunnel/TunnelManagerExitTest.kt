package cn.srv0.sshinjector.domain.vpn.tunnel

import cn.srv0.sshinjector.data.remote.tunnel.TunnelPluginFactory
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 出口注册表语义 (设计 §8.4 / I-5 / I-7):
 * - 同 id 幂等启动 (不重建池);
 * - `stopAll()` 必须断开**全部**出口并清空;
 * - 断开之后才完成的启动必须**自我回收**, 不得登记 (否则端口/SSH 会话跨会话复活)。
 *
 * 全部等待有界 (5s): 并发用例只挂在假件的 `CompletableDeferred` 上, 不 sleep 赌时序。
 */
class TunnelManagerExitTest {
    private class FakePlugin(
        override val exitId: String,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : TunnelPlugin {
        override val id = "socks5"
        val connects = CopyOnWriteArrayList<String>()
        val disconnects = CopyOnWriteArrayList<String>()
        val connectStarted = CompletableDeferred<Unit>()

        override suspend fun connect(config: TunnelConfig): Result<Unit> {
            connects += exitId
            connectStarted.complete(Unit)
            gate?.await()
            return Result.success(Unit)
        }

        override suspend fun disconnect() {
            disconnects += exitId
        }
    }

    private class FakeFactory(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : TunnelPluginFactory {
        override val protocolId = "socks5"
        val created = CopyOnWriteArrayList<FakePlugin>()
        val firstCreated = CompletableDeferred<FakePlugin>()

        override fun create(
            exitId: String,
            label: String,
        ): TunnelPlugin =
            FakePlugin(exitId, gate).also {
                created += it
                firstCreated.complete(it)
            }
    }

    private fun config() =
        TunnelConfig.Socks5(
            sshHost = "127.0.0.1",
            sshUsername = "u",
            sshKeyAlias = "k",
        )

    @Test
    fun `startExit registers the exit and exposes the primary plugin`() =
        runBlocking {
            val factory = FakeFactory()
            val manager = TunnelManager(mapOf("socks5" to factory))

            val result =
                withTimeout(5_000) {
                    manager.startExit(ExitId.DEFAULT, "socks5", "", "hk.example.com", config())
                }

            assertTrue(result.isSuccess)
            assertNotNull(manager.exit(ExitId.DEFAULT))
            assertEquals("hk.example.com", manager.exit(ExitId.DEFAULT)?.serverHost)
            assertNotNull(manager.getActiveOrFallback())
        }

    @Test
    fun `startExit is idempotent for the same id`() =
        runBlocking {
            val factory = FakeFactory()
            val manager = TunnelManager(mapOf("socks5" to factory))

            withTimeout(5_000) {
                manager.startExit(ExitId.DEFAULT, "socks5", "", "h", config())
                manager.startExit(ExitId.DEFAULT, "socks5", "", "h", config())
            }

            assertEquals("只应创建/连接一个插件", 1, factory.created.size)
            val plugin = factory.created.single()
            assertEquals(1, plugin.connects.size)
        }

    @Test
    fun `stopAll disconnects every exit and clears the registry`() =
        runBlocking {
            val factory = FakeFactory()
            val manager = TunnelManager(mapOf("socks5" to factory))

            withTimeout(5_000) {
                manager.startExit(ExitId.DEFAULT, "socks5", "", "h", config())
                manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
            }
            assertEquals(2, manager.exitsSnapshot().size)

            withTimeout(5_000) { manager.stopAll() }

            assertTrue(manager.exitsSnapshot().isEmpty())
            assertNull(manager.getActiveOrFallback())
            assertEquals(setOf(ExitId.DEFAULT, "hk"), factory.created.map { it.exitId }.toSet())
            assertTrue(factory.created.all { it.disconnects.isNotEmpty() })
        }

    @Test
    fun `exit started across a stopAll is discarded instead of registered`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val factory = FakeFactory(gate)
            val manager = TunnelManager(mapOf("socks5" to factory))

            // connect() 挂在 gate 上 —— 模拟"SSH 建池进行中用户点了断开"
            val starting =
                launch {
                    manager.startExit(ExitId.DEFAULT, "socks5", "", "h", config())
                }
            withTimeout(5_000) { factory.firstCreated.await() }
            val plugin = factory.created.single()
            withTimeout(5_000) { plugin.connectStarted.await() }

            withTimeout(5_000) { manager.stopAll() }
            gate.complete(Unit)
            withTimeout(5_000) { starting.join() }

            assertTrue("代际变了之后完成的启动不得登记", manager.exitsSnapshot().isEmpty())
            assertNull(manager.exit(ExitId.DEFAULT))
            assertTrue("必须自我回收刚建的池", plugin.disconnects.isNotEmpty())
        }
}
