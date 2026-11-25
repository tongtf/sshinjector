package cn.srv0.sshinjector.domain.vpn.routing

/**
 * D-5 (DOMAIN_SPLIT 隧道提升) 的判定, 纯逻辑。
 *
 * 决策 (2026-11-25, 用户拍板, 对齐设计 §6.5 / §8.8):
 * **只判断规则文本里有没有命中该域名的出口规则, 完全不看出口当前是否可用。**
 *
 * 理由: 规则命中但目标出口 Starting/Degraded 时如果回落直连, DNS 会拿到真实 IP, 而真实 IP
 * 在 DOMAIN_SPLIT 下根本没有 TUN 路由 → 连接不进本栈 → 规则彻底静默失效。正确做法是仍然提升
 * 成假 IP 把连接拉进隧道, 由 TCP 阶段的 [cn.srv0.sshinjector.domain.vpn.TcpStateMachine.selectExit]
 * 按 `failoverEnabled` 回退主出口 (或直接断开)。
 *
 * 附带好处: 出口降级/恢复不再改变 DNS 缓存键 (`cacheEntryKey`), 不会出现同一域名在假 IP / 真实 IP
 * 之间来回翻转。
 */
object DomainExitPromotion {
    fun shouldPromote(
        snapshot: RoutingSnapshot,
        domain: String,
    ): Boolean = snapshot.enabled && snapshot.table.hasDomainRule(domain)
}
