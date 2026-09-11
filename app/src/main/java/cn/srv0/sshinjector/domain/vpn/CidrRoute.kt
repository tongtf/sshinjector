package cn.srv0.sshinjector.domain.vpn

import java.net.InetAddress

/**
 * CIDR 网段路由：表示一个 IP 网络前缀，用于判断目标 IP 是否落在该网段内（bypass 决策）。
 *
 * 纯值对象 + 纯函数，无 Android / 协程依赖，可独立单测。从 [cn.srv0.sshinjector.domain.usecase.VpnController]
 * 抽出，使 CIDR 匹配算法脱离 VPN 控制器这一 God Object 并可被直接测试。
 */
data class CidrRoute(
    val network: InetAddress,
    val prefixLength: Int,
) {
    companion object {
        /**
         * 解析 "a.b.c.d/prefix"（IPv4）或 "2400::/prefix"（IPv6）。
         * 格式非法或 prefix 越界（IPv4 > 32 / IPv6 > 128）返回 null。
         */
        fun parse(cidr: String): CidrRoute? {
            return try {
                val parts = cidr.split("/")
                if (parts.size != 2) return null
                val ip = InetAddress.getByName(parts[0])
                val prefix = parts[1].toIntOrNull() ?: return null
                val maxPrefix = if (ip.address.size == 4) 32 else 128
                if (prefix !in 0..maxPrefix) return null
                CidrRoute(ip, prefix)
            } catch (_: Exception) {
                null
            }
        }

        /** 判断 [ip] 是否落在 [route] 网段内；IPv4/IPv6 跨族（字节长度不同）返回 false。 */
        fun matches(
            ip: InetAddress,
            route: CidrRoute,
        ): Boolean {
            val ipBytes = ip.address
            val netBytes = route.network.address
            if (ipBytes.size != netBytes.size) return false

            val prefixLen = route.prefixLength
            val fullBytes = prefixLen / 8
            val remainingBits = prefixLen % 8

            for (i in 0 until fullBytes) {
                if (ipBytes[i] != netBytes[i]) return false
            }
            if (remainingBits > 0) {
                val mask = (0xFF shl (8 - remainingBits))
                if ((ipBytes[fullBytes].toInt() and mask) != (netBytes[fullBytes].toInt() and mask)) {
                    return false
                }
            }
            return true
        }
    }
}
