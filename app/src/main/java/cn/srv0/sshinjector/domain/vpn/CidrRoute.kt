package cn.srv0.sshinjector.domain.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

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
    /**
     * 预编译的网段字节。`InetAddress.address` 每次调用都会克隆一个 ByteArray —— 数据面热路径
     * 会对**每条**排除路由取一次, 排除规则有 N 条就是 N 次分配/包。构造时取一次即可。
     * 放在类体内 (而非构造参数) 以免影响 data class 的 equals/hashCode 语义。
     */
    val networkBytes: ByteArray = network.address

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
            val netBytes = route.networkBytes
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

        /**
         * [matches] 的**零分配**版本: 直接用报文缓冲区里 [ipOffset] 处的地址字节做前缀匹配。
         *
         * 数据面热路径专用 —— 旧写法要为每个包构造 `InetAddress` (等价于 `extractDstIp`),
         * 再对每条路由克隆 `InetAddress.address`。语义与 [matches] 逐位一致, 由
         * `CidrRouteTest` 的等价性矩阵锁定; 缓冲区不足时返回 false (调用方已按 IP 头定界)。
         *
         * **[ipSize] 必须传**: [matches] 靠"两边字节长度不等"拒绝跨族匹配, 而这里没有
         * `InetAddress` 可比较 —— 漏掉长度检查会让 `0.0.0.0/0` (prefixLen=0 → 全字节循环 0 次)
         * 直接命中任意 IPv6 包, 把 IPv6 流量误判成"排除路由直连"。
         *
         * @param buffer 报文缓冲区 (只读绝对下标, 不动 position/limit)
         * @param ipOffset 待匹配地址在 buffer 里的**绝对**起始下标
         * @param ipSize 待匹配地址的字节数 (IPv4 = 4, IPv6 = 16)
         */
        fun matchesRaw(
            buffer: ByteBuffer,
            ipOffset: Int,
            ipSize: Int,
            route: CidrRoute,
        ): Boolean {
            val netBytes = route.networkBytes
            if (ipSize != netBytes.size) return false
            if (ipOffset < 0 || ipOffset + netBytes.size > buffer.limit()) return false

            val prefixLen = route.prefixLength
            val fullBytes = prefixLen / 8
            val remainingBits = prefixLen % 8

            for (i in 0 until fullBytes) {
                if (buffer.get(ipOffset + i) != netBytes[i]) return false
            }
            if (remainingBits > 0) {
                val mask = (0xFF shl (8 - remainingBits))
                if ((buffer.get(ipOffset + fullBytes).toInt() and mask) != (netBytes[fullBytes].toInt() and mask)) {
                    return false
                }
            }
            return true
        }
    }
}
