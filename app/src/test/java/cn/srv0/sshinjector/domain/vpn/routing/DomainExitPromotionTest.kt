package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.exit.ProxyExit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-5 隧道提升: 只判规则文本, 不看出口可用性 (2026-11-25 决策, 设计 §6.5/§8.8)。
 *
 * 回归点是第 3 条: 出口一个都没起来时**仍然**要提升。旧实现把 `Ready && isHealthy` 写进谓词,
 * 于是 DOMAIN_SPLIT 下规则静默失效 (真实 IP 不进 TUN), 而且出口降级/恢复还会让同一域名的
 * DNS 缓存键来回翻转。
 */
class DomainExitPromotionTest {
    @Test
    fun `disabled snapshot never promotes`() {
        val snapshot =
            RoutingSnapshot(
                enabled = false,
                table = RoutingTable.fromText("example.com -> jz", setOf("jz")),
            )
        assertFalse(DomainExitPromotion.shouldPromote(snapshot, "example.com"))
    }

    @Test
    fun `no matching rule means direct resolution`() {
        val snapshot =
            RoutingSnapshot(
                enabled = true,
                table = RoutingTable.fromText("other.com -> jz", setOf("jz")),
            )
        assertFalse(DomainExitPromotion.shouldPromote(snapshot, "example.com"))
    }

    @Test
    fun `matching rule promotes even when no exit is running`() {
        // 出口已配置但尚未启动 (TunnelManager.exit("jz") == null = Starting/Failed) ——
        // 正是旧实现回落直连、规则静默失效的场景
        val snapshot =
            RoutingSnapshot(
                enabled = true,
                exits = listOf(ProxyExit(id = 1, name = "jz", serverId = 1, enabled = true, priority = 0)),
                table = RoutingTable.fromText("example.com -> jz", setOf("jz")),
            )
        assertTrue(DomainExitPromotion.shouldPromote(snapshot, "example.com"))
    }

    @Test
    fun `rule to an unknown exit is not promoted`() {
        val snapshot =
            RoutingSnapshot(
                enabled = true,
                table = RoutingTable.fromText("example.com -> jz", emptySet()),
            )
        assertFalse(DomainExitPromotion.shouldPromote(snapshot, "example.com"))
    }

    @Test
    fun `subdomain matches and port qualifier is ignored for dns promotion`() {
        val snapshot =
            RoutingSnapshot(
                enabled = true,
                table = RoutingTable.fromText("example.com:443 -> jz", setOf("jz")),
            )
        assertTrue(DomainExitPromotion.shouldPromote(snapshot, "www.example.com"))
    }
}
