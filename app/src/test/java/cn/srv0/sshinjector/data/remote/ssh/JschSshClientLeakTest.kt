package cn.srv0.sshinjector.data.remote.ssh

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.domain.model.ServerConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 审计 §8 资源泄漏回归:
 * - LEAK-1 (T-09): `checkPoolHealth()` 会在**不排空池**的情况下把 `isConnectedFlag` 置 false;
 *   旧 `disconnect()` 以该 flag 早退 → 池失败重连时排空循环永不执行 → 每轮滞留最多 3 条 Session/Scope。
 * - LEAK-2 (T-10): `verifySessionRoundTrip` 异常路径不释放 exec 通道; `scope.async` 超时后不取消探活协程。
 *
 * 依赖 mockito-kotlin (inline mock maker) 构造假 Session/ChannelExec, 不依赖真实 Android/SSH 环境。
 */
class JschSshClientLeakTest {
    private fun newClient(keyManager: SshKeyManager = mock()): JschSshClient =
        JschSshClient(
            keyManager = keyManager,
            knownHostsManager = mock(),
            serverDao = mock<ServerDao>(),
        )

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `disconnect drains pool even when the connected flag was already cleared`() =
        runBlocking {
            val client = newClient()
            val session1: Session = mock()
            val session2: Session = mock()
            client.seedPooledSessionForTest(PooledSession(session = session1, scope = newScope(), healthy = false))
            client.seedPooledSessionForTest(PooledSession(session = session2, scope = newScope(), healthy = false))

            // 复现泄漏场景: 池失败把 flag 置 false, 但池本身没被排空。
            client.checkPoolHealth()

            // 旧实现此处 (flag 已为 false) 直接 return true, 池里的 Session 永不 disconnect。
            client.disconnect()

            verify(session1).disconnect()
            verify(session2).disconnect()
            assertEquals("池必须被排空", 0, client.poolSizeForTest())
        }

    @Test
    fun `connect starts with an empty pool`() =
        runBlocking {
            val keyManager: SshKeyManager = mock()
            val client = newClient(keyManager)
            val staleSession: Session = mock()
            client.seedPooledSessionForTest(PooledSession(session = staleSession, scope = newScope()))

            // 记录第一次进入 createSession 时池的大小: connect 起始的 drainPool() 必须已把残留清空。
            val poolSizeAtSessionCreation = CopyOnWriteArrayList<Int>()
            whenever(keyManager.createJSchIdentity(any(), any())).thenAnswer {
                poolSizeAtSessionCreation.add(client.poolSizeForTest())
                false // 建连失败, 快速走完 connect 的失败路径
            }

            val result =
                client.connect(
                    ServerConfig(name = "t", host = "127.0.0.1", port = 1, username = "u", keyAlias = "k"),
                )

            assertFalse(result.success)
            assertTrue(
                "connect 必须在建连前清空残留池, 实际观测=$poolSizeAtSessionCreation",
                poolSizeAtSessionCreation.isNotEmpty() && poolSizeAtSessionCreation.all { it == 0 },
            )
            verify(staleSession).disconnect()
            assertEquals(0, client.poolSizeForTest())
        }

    @Test
    fun `round-trip disconnects the exec channel on exception`() =
        runBlocking {
            val client = newClient()
            val session: Session = mock()
            val exec: ChannelExec = mock()
            whenever(session.openChannel("exec")).thenReturn(exec)
            doThrow(RuntimeException("channel open failed")).whenever(exec).connect(anyInt())

            val result =
                client.verifySessionRoundTrip(
                    PooledSession(session = session, scope = newScope()),
                )

            // 秒级失败 = 服务器拒绝 exec (非黑洞), 归 UNAVAILABLE; 通道仍必须被释放。
            assertEquals(JschSshClient.ROUND_TRIP_UNAVAILABLE, result)
            verify(exec).disconnect()
        }

    @Test
    fun `round-trip cancels the probe on timeout`() =
        runBlocking {
            val client = newClient()
            // 永不完成的探活协程; 用短超时验证 awaitProbeOrCancel 的取消语义 (真实路径为 10s)。
            val probe =
                async(Dispatchers.IO) {
                    delay(Long.MAX_VALUE)
                    JschSshClient.ROUND_TRIP_OK
                }

            val result = client.awaitProbeOrCancel(probe, timeoutMs = 50L)

            assertEquals(JschSshClient.ROUND_TRIP_FAILED, result)
            assertTrue("超时的探活协程必须被取消", probe.isCancelled)
        }
}
