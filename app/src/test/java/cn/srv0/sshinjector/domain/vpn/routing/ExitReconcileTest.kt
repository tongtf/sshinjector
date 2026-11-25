package cn.srv0.sshinjector.domain.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务器删除会级联删出口; 运行中的出口必须按"期望集合"回收。 */
class ExitReconcileTest {
    @Test
    fun `running exit missing from desired set is stale`() {
        assertEquals(listOf("hk"), staleExitIds(listOf("hk", "bj"), setOf("bj")))
    }

    @Test
    fun `all running exits still desired means nothing to stop`() {
        assertTrue(staleExitIds(listOf("hk", "bj"), setOf("hk", "bj", "jz")).isEmpty())
    }

    @Test
    fun `empty desired set stops everything`() {
        assertEquals(setOf("hk", "bj"), staleExitIds(listOf("hk", "bj"), emptySet()).toSet())
    }
}
