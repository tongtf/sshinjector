package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.RemoteDomainResolver
import cn.srv0.sshinjector.domain.vpn.RemoteResolveOutcome
import cn.srv0.sshinjector.domain.vpn.SshIoDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「解析真实 IP」的路径裁决 (设计 §7.2): 开关语义 / 远端三态回退 / 池饱和让位。
 *
 * 本地腿用**未注入 protectSocket 的** [DnsInterceptor] (确定性返回空表且不发包), 所以这里断言的是
 * **路径语义**而不是 IP 内容 —— 本地解析本身的行为由 DnsInterceptorTest 覆盖。
 */
class DomainIpResolverTest {
    private val localLeg = DnsInterceptor()

    private class FakeRemote(
        private val outcome: RemoteResolveOutcome,
    ) : RemoteDomainResolver {
        var calls = 0

        override suspend fun resolveIps(
            domain: String,
            timeoutMs: Long,
        ): RemoteResolveOutcome {
            calls++
            return outcome
        }
    }

    /** 池饱和在单测里造不出来 (要真起 128 条线程), 用 [SshIoDispatcher.isSaturated] 这个 open 入口替身。 */
    private class PoolStateFake(
        private val saturated: Boolean,
    ) : SshIoDispatcher() {
        override fun isSaturated(): Boolean = saturated
    }

    private fun resolver(
        remote: RemoteDomainResolver,
        saturated: Boolean = false,
    ) = DomainIpResolver(localLeg, remote, PoolStateFake(saturated))

    @Test
    fun `switch off never touches the remote leg`() =
        runTest {
            val remote = FakeRemote(RemoteResolveOutcome.Ok(listOf("1.1.1.1"), 5, "S1"))
            val result = resolver(remote).resolve("example.com", useRemote = false)
            assertEquals(ResolvePath.LOCAL, result.path)
            assertEquals(0, remote.calls)
        }

    @Test
    fun `switch on returns the remote answer`() =
        runTest {
            val remote = FakeRemote(RemoteResolveOutcome.Ok(listOf("1.1.1.1", "2.2.2.2"), 42, "S1"))
            val result = resolver(remote).resolve("example.com", useRemote = true)
            assertEquals(ResolvePath.REMOTE, result.path)
            assertEquals(listOf("1.1.1.1", "2.2.2.2"), result.ips)
            assertEquals(1, remote.calls)
        }

    @Test
    fun `exec unavailable falls back to the local view`() =
        runTest {
            val remote = FakeRemote(RemoteResolveOutcome.Unavailable)
            val result = resolver(remote).resolve("example.com", useRemote = true)
            assertEquals(ResolvePath.LOCAL_FALLBACK, result.path)
            assertEquals(1, remote.calls)
        }

    @Test
    fun `black hole falls back to the local view`() =
        runTest {
            val remote = FakeRemote(RemoteResolveOutcome.Failed)
            assertEquals(
                ResolvePath.LOCAL_FALLBACK,
                resolver(remote).resolve("example.com", useRemote = true).path,
            )
        }

    @Test
    fun `remote answer without records falls back to the local view`() =
        runTest {
            val remote = FakeRemote(RemoteResolveOutcome.Ok(emptyList(), 1200, "S1"))
            assertEquals(
                ResolvePath.LOCAL_FALLBACK,
                resolver(remote).resolve("example.com", useRemote = true).path,
            )
        }

    @Test
    fun `saturated ssh pool skips the remote leg entirely`() =
        runTest {
            val remote = FakeRemote(RemoteResolveOutcome.Ok(listOf("1.1.1.1"), 5, "S1"))
            val result = resolver(remote, saturated = true).resolve("example.com", useRemote = true)
            assertEquals(ResolvePath.LOCAL_FALLBACK, result.path)
            // 预检在挂起之前: 数据面吃紧时连远端腿都不发 (不是发出去了再取消)
            assertEquals(0, remote.calls)
        }
}
