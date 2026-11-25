package cn.srv0.sshinjector.domain.vpn.tunnel

import cn.srv0.sshinjector.data.remote.tunnel.TunnelPluginFactory
import cn.srv0.sshinjector.domain.vpn.exit.ExitHealth
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import kotlinx.coroutines.CancellationException
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
 * - 同 id 幂等启动 (不重建池); "启动中" 的重复调用必须明确失败, 不能假装成功;
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
    fun `an exit is observable as Starting while its pool connects`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val factory = FakeFactory(gate)
            val manager = TunnelManager(mapOf("socks5" to factory))

            val starting =
                launch {
                    manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
                }
            val plugin = withTimeout(5_000) { factory.firstCreated.await() }
            withTimeout(5_000) { plugin.connectStarted.await() }

            assertEquals(
                "建池期间必须是 Starting: 快照要能区分'起来了'和'还在连'",
                ExitHealth.Starting,
                manager.exit("hk")?.health,
            )
            assertTrue("Starting 也必须出现在出口列表里", manager.exitsSnapshot().any { it.id == "hk" })

            gate.complete(Unit)
            withTimeout(5_000) { starting.join() }
            assertEquals(ExitHealth.Ready, manager.exit("hk")?.health)
        }

    @Test
    fun `a second start while the exit is still starting fails instead of pretending success`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val factory = FakeFactory(gate)
            val manager = TunnelManager(mapOf("socks5" to factory))

            val starting =
                launch {
                    manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
                }
            val plugin = withTimeout(5_000) { factory.firstCreated.await() }
            withTimeout(5_000) { plugin.connectStarted.await() }

            val second = withTimeout(5_000) { manager.startExit("hk", "socks5", "hk", "hk.example.com", config()) }

            assertTrue("启动中的重复调用不能返回 success (调用方会以为出口可用)", second.isFailure)
            assertEquals("不得再创建一个池", 1, factory.created.size)

            gate.complete(Unit)
            withTimeout(5_000) { starting.join() }
        }

    @Test
    fun `stopAll reclaims an exit that is still connecting`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val factory = FakeFactory(gate)
            val manager = TunnelManager(mapOf("socks5" to factory))

            val starting =
                launch {
                    manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
                }
            val plugin = withTimeout(5_000) { factory.firstCreated.await() }
            withTimeout(5_000) { plugin.connectStarted.await() }

            withTimeout(5_000) { manager.stopAll() }

            // 关键: 不释放 gate 就必须已经 disconnect —— 否则这套池要等 connect 返回才有人收,
            // 中途取消时它会一直残留 (这就是"先登记再 connect"要解决的问题)
            assertTrue("正在建池的出口必须被 stopAll 立刻回收", plugin.disconnects.isNotEmpty())
            assertNull(manager.exit("hk"))

            gate.complete(Unit)
            withTimeout(5_000) { starting.join() }
        }

    @Test
    fun `exit counters accumulate connection failures and degradations`() =
        runBlocking {
            val factory = FakeFactory()
            val manager = TunnelManager(mapOf("socks5" to factory))
            withTimeout(5_000) { manager.startExit("hk", "socks5", "hk", "hk.example.com", config()) }

            manager.noteConnection("hk")
            manager.noteHandshakeFailure("hk", "握手 12ms 即失败")
            manager.noteDegraded("hk", "SSH 池无活口")

            val exit = manager.exit("hk")!!
            assertEquals(1L, exit.connTotal)
            assertEquals(1L, exit.failTotal)
            assertEquals(1L, exit.degradeTotal)
            assertEquals("SSH 池无活口", exit.lastError)

            manager.noteRecovered("hk")
            assertEquals("恢复后要清掉最后一次失败原因", "", manager.exit("hk")!!.lastError)

            // 不存在的出口不得抛 (快照/巡检可能在出口已摘除后调用)
            manager.noteConnection("gone")
            manager.noteDegraded("gone", "x")
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

    // ------------------------------------------------------------------ P1-13 / P1-14

    /** 按出口 id 分配闸门: A/B 并发启动用例需要 A 卡住、B 正常返回。 */
    private class PerExitGateFactory(
        private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ) : TunnelPluginFactory {
        override val protocolId = "socks5"
        val created = CopyOnWriteArrayList<FakePlugin>()
        val firstCreated = CompletableDeferred<FakePlugin>()
        val secondCreated = CompletableDeferred<FakePlugin>()

        override fun create(
            exitId: String,
            label: String,
        ): TunnelPlugin =
            FakePlugin(exitId, gates[exitId]).also {
                created += it
                firstCreated.complete(it)
                if (created.size == 2) secondCreated.complete(it)
            }
    }

    /** 模拟"插件连接被取消": 必须向上传播成 CancellationException, 而不是 Result.failure。 */
    private class CancellingPlugin(
        override val exitId: String,
    ) : TunnelPlugin {
        override val id = "socks5"
        val disconnects = CopyOnWriteArrayList<String>()

        override suspend fun connect(config: TunnelConfig): Result<Unit> = throw CancellationException("启动被取消")

        override suspend fun disconnect() {
            disconnects += exitId
        }
    }

    private class CancellingFactory : TunnelPluginFactory {
        override val protocolId = "socks5"
        val plugins = CopyOnWriteArrayList<CancellingPlugin>()

        override fun create(
            exitId: String,
            label: String,
        ): TunnelPlugin = CancellingPlugin(exitId).also { plugins += it }
    }

    @Test
    fun `stopExit during connect releases the entry and late success does not revive it`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val factory = PerExitGateFactory(mapOf("hk" to gate))
            val manager = TunnelManager(mapOf("socks5" to factory))

            // connect() 挂在闸门上 —— 模拟"SSH 建池进行中用户禁用了这个出口"
            var result: Result<Unit>? = null
            val starting =
                launch {
                    result = manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
                }
            val plugin = withTimeout(5_000) { factory.firstCreated.await() }
            val entry = manager.exit("hk")
            assertNotNull(entry)
            withTimeout(5_000) { plugin.connectStarted.await() }

            withTimeout(5_000) { manager.stopExit("hk") }

            // 不等 connect 返回就必须已经回收: 否则这套池要等建连结束才有人看见 (孤儿出口)
            assertNull("stopExit 之后条目必须不存在", manager.exit("hk"))
            assertTrue("必须断开正在建池的插件", plugin.disconnects.isNotEmpty())
            assertEquals("条目被摘掉时健康状态必须是 Stopped", ExitHealth.Stopped, entry?.health)

            gate.complete(Unit)
            withTimeout(5_000) { starting.join() }

            assertNull("late success 不得把条目复活", manager.exit("hk"))
            assertEquals("late success 不得置 Ready", ExitHealth.Stopped, entry?.health)
            assertTrue("late success 也必须补一次 disconnect", plugin.disconnects.isNotEmpty())
            assertTrue(
                "启动结果必须是失败 (调用方不能以为出口可用, 实际: $result)",
                result?.isFailure == true,
            )
        }

    @Test
    fun `stopExit of one exit does not abort another exit that is still starting`() =
        runBlocking {
            // A 卡在建连, B 立即成功 —— 若 stopExit 递增全局代际, B 会被连带判为过期
            val gate = CompletableDeferred<Unit>()
            val factory = PerExitGateFactory(mapOf("hk" to gate))
            val manager = TunnelManager(mapOf("socks5" to factory))

            val startingA =
                launch {
                    manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
                }
            val pluginA = withTimeout(5_000) { factory.firstCreated.await() }
            withTimeout(5_000) { pluginA.connectStarted.await() }

            // B 在 A 仍处于 Starting 时启动, 必须成功 Ready
            val resultB =
                withTimeout(5_000) {
                    manager.startExit("jp", "socks5", "jp", "jp.example.com", config())
                }
            val pluginB = withTimeout(5_000) { factory.secondCreated.await() }
            assertTrue("B 必须正常启动", resultB.isSuccess)
            assertEquals(ExitHealth.Ready, manager.exit("jp")?.health)

            withTimeout(5_000) { manager.stopExit("hk") }
            assertNull(manager.exit("hk"))

            // 禁用 A 不得殃及 B (per-entry 身份复查, 不是全局代际)
            assertEquals("stopExit(A) 不得影响 B", ExitHealth.Ready, manager.exit("jp")?.health)
            assertTrue("B 不得被断开", pluginB.disconnects.isEmpty())

            gate.complete(Unit)
            withTimeout(5_000) { startingA.join() }
            assertEquals(ExitHealth.Ready, manager.exit("jp")?.health)
        }

    @Test
    fun `a cancelled connect propagates instead of being folded into failure`() =
        runBlocking {
            val factory = CancellingFactory()
            val manager = TunnelManager(mapOf("socks5" to factory))

            // runCatching 捕获的必须仍是 CancellationException (发布层要能识别"取消"而不是"连接失败")
            val thrown =
                runCatching {
                    manager.startExit("hk", "socks5", "hk", "hk.example.com", config())
                }.exceptionOrNull()

            assertTrue(
                "取消必须向上传播, 不能被 catch(Exception) 折叠成 Result.failure (实际: $thrown)",
                thrown is CancellationException,
            )
            assertNull(manager.exit("hk"))
            val plugin = factory.plugins.single()
            assertTrue("取消路径也必须回收刚建的池", plugin.disconnects.isNotEmpty())
        }

    /** 可切换 "下一次启动失败/成功" 的工厂: 验证 Failed 条目保留 + 同会话重建。 */
    private class FlakyFactory : TunnelPluginFactory {
        override val protocolId = "socks5"

        @Volatile var failNext = true
        val created = CopyOnWriteArrayList<TunnelPlugin>()
        val disconnects = CopyOnWriteArrayList<String>()

        override fun create(
            exitId: String,
            label: String,
        ): TunnelPlugin =
            object : TunnelPlugin {
                override val id = "socks5"
                override val exitId: String = exitId

                override suspend fun connect(config: TunnelConfig): Result<Unit> =
                    if (failNext) {
                        Result.failure(IllegalStateException("boom"))
                    } else {
                        Result.success(Unit)
                    }

                override suspend fun disconnect() {
                    disconnects += exitId
                }
            }.also { created += it }
    }

    @Test
    fun `failed start is kept as Failed and can be rebuilt in the same session`() =
        runBlocking {
            val factory = FlakyFactory()
            val manager = TunnelManager(mapOf("socks5" to factory))

            val first = withTimeout(5_000) { manager.startExit("hk", "socks5", "hk", "hk.example.com", config()) }
            assertTrue(first.isFailure)

            // 方案 A: 失败出口不再凭空消失, 但仍不可选 (health != Ready)
            val failed = manager.exit("hk")
            assertNotNull(failed)
            assertEquals(ExitHealth.Failed, failed!!.health)
            assertTrue("失败原因要可见", failed.lastError.isNotEmpty())
            assertEquals(1L, failed.failTotal)
            assertTrue("失败也要回收已建的一半资源", factory.disconnects.isNotEmpty())
            assertEquals(
                "Session UI 必须看得到 Failed",
                ExitHealth.Failed,
                manager.exitsState.value
                    .single { it.id == "hk" }
                    .health,
            )

            // 同 id 再启动: 必须真的重建 (旧实现对非 Starting 直接假 success)
            factory.failNext = false
            val second = withTimeout(5_000) { manager.startExit("hk", "socks5", "hk", "hk.example.com", config()) }
            assertTrue(second.isSuccess)
            assertEquals(ExitHealth.Ready, manager.exit("hk")?.health)
            assertEquals("必须创建了第二个插件", 2, factory.created.size)
            // 失败时回收一次 + 重建前摘除 Failed 条目再兜底一次 (幂等)
            assertEquals(2, factory.disconnects.size)
        }
}
