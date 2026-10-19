package cn.srv0.sshinjector.domain.vpn

import android.content.Context
import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress

/**
 * ④ 按应用归属的反查器纪律 (不需要真机也不需要真 VPN):
 *
 * - **降级**: 拿不到系统服务 / 平台不给归属时静默返回 null, 绝不抛 (数据面每连接一次调用,
 *   一次抛异常就会顺着 `closeTcpConnection` 冒到 packetLoop);
 * - **有界**: `lookup` 只入队 + 计数, 队列满了丢弃而不是堆积;
 * - **NONE**: 测试替身完全不产生工作。
 *
 * 真机上的"到底能不能拿到 uid"由 `AppUidProbe` (统计页顶栏 `i`) 回答, 这里只锁本地不变量。
 */
class AppUidResolverTest {
    private val src = InetAddress.getByName("10.0.0.2").address
    private val dst = InetAddress.getByName("93.184.216.34").address

    @Test
    fun `resolveOnce returns null when the platform service is unavailable`() {
        // mock Context 的 getSystemService 返回 null → 平台能力缺失的等价形态
        val resolver = ConnectivityAppUidResolver(mock())
        assertNull("无服务时必须静默返回 null", resolver.resolveOnce(src, 41000, dst, 443))
    }

    @Test
    fun `lookup without a platform service never throws and counts the query`() {
        val resolver = ConnectivityAppUidResolver(mock())

        resolver.lookup(src, 41000, dst, 443) { fail("拿不到归属时不得回调") }

        assertTrue("查询必须同步计数: ${resolver.diagnostics()}", resolver.diagnostics().contains("/1 查询"))
        awaitDiagnostics(resolver, "无归属 1")
    }

    @Test
    fun `NONE resolver is a silent no-op`() {
        AppUidResolver.NONE.lookup(src, 41000, dst, 443) { fail("NONE 不得回调") }
        assertEquals("NONE 不产出诊断段", "", AppUidResolver.NONE.diagnostics())
    }

    @Test
    fun `a platform SecurityException is counted and pauses instead of crashing`() {
        val cm = mock<ConnectivityManager>()
        whenever(cm.getConnectionOwnerUid(anyInt(), any(), any()))
            .thenThrow(SecurityException("not the active VpnService"))
        val resolver = ConnectivityAppUidResolver(contextWith(cm))

        resolver.lookup(src, 41000, dst, 443) { fail("被拒时不得回调") }

        awaitDiagnostics(resolver, "被拒 1")
        assertTrue(
            "拒绝必须是\"暂停窗口\"而不是永久停用: ${resolver.diagnostics()}",
            resolver.diagnostics().contains("暂停"),
        )
    }

    @Test
    fun `the deny window reopens so a disconnect cannot kill the feature forever`() {
        // 时钟注入 → 完全确定性 (不 sleep 30s)
        var now = 1_000L
        val window = DisableWindow(windowMs = 30_000L) { now }

        assertEquals("初始未暂停", false, window.isActive())
        assertTrue("窗口内第一次拒绝必须报告 (调用方据此只打一条日志)", window.trip("SecurityException"))
        assertFalse("同窗口内后续拒绝静默", window.trip("SecurityException"))
        assertTrue(window.isActive())

        now += 29_999L
        assertTrue("窗口未到仍然暂停", window.isActive())

        now += 1L
        assertFalse("窗口到期必须自动恢复: 断开瞬间的 SecurityException 不能杀死功能", window.isActive())
        assertEquals(0L, window.remainingMs())
        assertTrue("恢复后再次被拒要能重新报告", window.trip("SecurityException"))
    }

    private fun contextWith(cm: ConnectivityManager): Context {
        val context = mock<Context>()
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)
        return context
    }

    /**
     * 有界等待异步那一半落账 (查询在单线程池里跑, 计数不是同步的)。
     * **必须**有 deadline: 无界自旋曾把 CI 挂死 26 分钟, 见 AGENTS「Tests」。
     */
    private fun awaitDiagnostics(
        resolver: AppUidResolver,
        needle: String,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !resolver.diagnostics().contains(needle)) {
            Thread.sleep(10)
        }
        assertTrue("$needle 必须在 5s 内出现: ${resolver.diagnostics()}", resolver.diagnostics().contains(needle))
    }
}
