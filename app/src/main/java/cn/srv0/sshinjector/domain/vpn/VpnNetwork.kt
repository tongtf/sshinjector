package cn.srv0.sshinjector.domain.vpn

import java.net.InetAddress

/**
 * VPN 网络拓扑常量——TUN 虚拟网卡的固定地址与假 IP 段。
 *
 * 单一事实源，供 [VpnController] / SshVpnService / [DnsInterceptor] 共用，
 * 避免拓扑硬编码散落多处（改 VPN 网络时只动这里）。
 * 假 IP 判据也统一走 [isFakeIp] / [isFakeHost]，各处不要各写一份。
 */
object VpnNetwork {
    /** TUN 虚拟网卡固定 IP：VPN DNS 服务器与主机路由均指向此地址（网段 10.0.0.0/24）。 */
    const val TUN_IP = "10.0.0.2"

    /** TUN 网关地址 (on-link DNS 的来源)；TUN 网卡自身为 [TUN_IP]。 */
    const val TUN_GATEWAY = "10.0.0.1"

    /** TUN 网段前缀长度 (10.0.0.0/24)。 */
    const val TUN_PREFIX_LEN = 24

    /** IPv6 网关 (fd00::1/64，假 IPv6 池 fd00::2 起步)。 */
    const val IPV6_GATEWAY = "fd00::1"

    /** IPv6 网段前缀长度。 */
    const val IPV6_PREFIX_LEN = 64

    /** IPv4 假 IP 段 (RFC 2544 benchmarking 198.18.0.0/15)。 */
    const val FAKE_V4_ROUTE = "198.18.0.0"
    const val FAKE_V4_PREFIX_LEN = 15

    /** IPv6 假 IP 段 (fd00::/8)。 */
    const val FAKE_V6_ROUTE = "fd00::"
    const val FAKE_V6_PREFIX_LEN = 8

    /** 假 IP 判据 (字节形): 198.18.0.0/15 IPv4 或 fd00::/8 IPv6 — 唯一实现。 */
    fun isFakeIp(ip: InetAddress): Boolean {
        val bytes = ip.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return b0 == 198 && (b1 == 18 || b1 == 19)
        }
        if (bytes.size == 16) {
            return (bytes[0].toInt() and 0xFF) == 0xFD
        }
        return false
    }

    /** 假 IP 主机名前缀 (与 [isFakeIp] 判据一致)。 */
    private val FAKE_HOST_PREFIXES = listOf("198.18.", "198.19.", "fd00:")

    /** 假 IP 判据 (字符串形, 供 SOCKS 主机名快判) — 唯一实现。 */
    fun isFakeHost(host: String): Boolean = FAKE_HOST_PREFIXES.any(host::startsWith)
}
