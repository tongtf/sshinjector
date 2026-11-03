package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * 路由快照构造 (设计 §8.10 / §10 I-20)。
 *
 * 核心是"关闭 = 逐字节零行为": `enabled=false` 时**不解析规则文本** —— 既不加解析成本,
 * 也不能因为规则里有语法错而凭空产生告警 (关闭状态下用户根本看不到这些配置)。
 */
class RoutingSnapshotTest {
    private val ip = InetAddress.getByName("93.184.216.34")

    private fun exit(
        name: String,
        enabled: Boolean = true,
        priority: Int = 100,
    ) = ProxyExit(id = name.hashCode().toLong(), name = name, serverId = 1, enabled = enabled, priority = priority)

    @Test
    fun `disabled snapshot is the canonical constant and parses nothing`() {
        val snapshot =
            RoutingSnapshot.build(
                enabled = false,
                failoverEnabled = false,
                rulesText = "garbage -> [[[",
                enabledExits = listOf(exit("hk")),
            )

        assertSame("关闭态必须是同一个规范对象, 保证对象图与改造前一致", RoutingSnapshot.DISABLED, snapshot)
        assertFalse(snapshot.enabled)
        assertTrue(snapshot.table.isEmpty)
        assertTrue("关闭态不暴露任何出口", snapshot.exits.isEmpty())
    }

    @Test
    fun `enabled snapshot parses rules and binds known exits`() {
        val snapshot =
            RoutingSnapshot.build(
                enabled = true,
                failoverEnabled = true,
                rulesText = "example.com -> hk",
                enabledExits = listOf(exit("hk")),
            )

        assertEquals("hk", snapshot.table.select(RouteTarget(domain = "example.com", ip = ip, port = 443)))
        assertEquals(setOf("hk"), snapshot.knownExitNames)
        assertTrue(snapshot.failoverEnabled)
    }

    @Test
    fun `rules referencing a non started exit stay unresolved`() {
        val snapshot =
            RoutingSnapshot.build(
                enabled = true,
                failoverEnabled = true,
                rulesText = "example.com -> jp",
                enabledExits = listOf(exit("hk")),
            )

        assertNull("未启动的出口不能接到连接", snapshot.table.select(RouteTarget(domain = "example.com", ip = ip, port = 443)))
        assertEquals(1, snapshot.table.unresolvedRules.size)
    }

    @Test
    fun `failover flag survives into the snapshot`() {
        val snapshot =
            RoutingSnapshot.build(
                enabled = true,
                failoverEnabled = false,
                rulesText = "",
                enabledExits = emptyList(),
            )

        assertFalse("failover=false 时命中降级出口要 RST, 由客户端重试", snapshot.failoverEnabled)
    }
}
