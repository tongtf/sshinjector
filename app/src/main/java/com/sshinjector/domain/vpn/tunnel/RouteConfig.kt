package com.sshinjector.domain.vpn.tunnel

data class AppTagEntry(
    val packageName: String,
    val tags: Set<String>,
)

data class TagTunnelEntry(
    val tag: String,
    val primaryTunnelId: String,
    val fallbackTunnelId: String? = null,
)

data class RouteConfig(
    val appTags: List<AppTagEntry> = emptyList(),
    val tagTunnels: List<TagTunnelEntry> = emptyList(),
    val defaultTunnelId: String = "socks5",
    val loadBalancing: LoadBalancingConfig? = null,
)

data class LoadBalancingConfig(
    val strategy: Strategy = Strategy.RoundRobin,
    val tunnelIds: List<String>,
) {
    enum class Strategy {
        RoundRobin,
        LeastConn,
        Random,
        Weighted,
    }
}
