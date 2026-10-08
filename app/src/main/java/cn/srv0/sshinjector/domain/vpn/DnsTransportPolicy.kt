package cn.srv0.sshinjector.domain.vpn

/**
 * DNS 模式 → 传输策略 (纯逻辑, 可单测; 供 VpnController.connect / updateDnsMode 共用)。
 *
 * 关键分歧在白名单模式 (dnsMode=2):
 * - **名单非空**: 名单内应用经 addAllowedApplication 进 TUN 并加 0.0.0.0/0 路由, 假 IP 有路由可用 → WHITELIST (假 IP 走隧道);
 * - **名单为空**: VpnService 不设 allowed list (全部应用进 TUN) 且不加任何路由 —— 但 addAddress("10.0.0.1/24")
 *   使 DNS 服务器 10.0.0.2 成为 on-link, 所有应用的 DNS 查询仍会进 TUN。若此时按白名单模式回假 IP
 *   (198.18.x.x), 这些假 IP 没有路由 → 落到物理网关不可达 → **直连访问被破坏** (表现为
 *   DNS_PROBE_FINISHED_NO_INTERNET / 连接超时)。因此空名单必须退化为 SYSTEM: DNS 经保护 socket
 *   透明转发、返回真实 IP, 流量按"无路由"走物理网卡 —— 即用户预期的"全部不走 VPN, 直连不受影响"。
 */
internal fun dnsTransportFor(
    dnsMode: Int,
    whitelistEnabledPackages: List<String>,
): DnsInterceptor.DnsTransport =
    when (dnsMode) {
        0 -> DnsInterceptor.DnsTransport.REMOTE // 全部走隧道
        1 -> DnsInterceptor.DnsTransport.SYSTEM // 系统默认, 完全透传
        2 ->
            if (whitelistEnabledPackages.isEmpty()) {
                DnsInterceptor.DnsTransport.SYSTEM
            } else {
                DnsInterceptor.DnsTransport.WHITELIST
            }
        3 -> DnsInterceptor.DnsTransport.DOMAIN_SPLIT // 域名分流
        else -> DnsInterceptor.DnsTransport.REMOTE
    }
