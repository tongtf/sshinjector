package cn.srv0.sshinjector.domain.vpn

/**
 * VPN 网络拓扑常量——TUN 虚拟网卡的固定地址。
 *
 * 单一事实源，供 [VpnController] / SshVpnService / [DnsInterceptor] 共用，
 * 避免 TUN IP 散落硬编码在多处（改 VPN 网络时只动这里）。
 */
object VpnNetwork {
    /** TUN 虚拟网卡固定 IP：VPN DNS 服务器与主机路由均指向此地址（网段 10.0.0.0/24）。 */
    const val TUN_IP = "10.0.0.2"
}
