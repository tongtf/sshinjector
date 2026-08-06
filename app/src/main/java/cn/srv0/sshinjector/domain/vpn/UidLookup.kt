package cn.srv0.sshinjector.domain.vpn

import java.io.File

/**
 * 解析本地 TCP 连接对端进程 UID 的抽象。
 *
 * Socks5ProxyServer 通过它校验发起 SOCKS5 连接的进程是否为自身
 * (UID == Process.myUid())，用于访问控制。抽象为接口使访问控制
 * 逻辑脱离 /proc/net/tcp 的具体实现，便于测试替换与未来换用
 * SO_PEERCRED / Socket 绑定方案。
 */
interface UidLookup {
    /**
     * 解析 (localPort, peerPort) 对应连接的发起进程 UID。
     *
     * @param localPort 服务器端监听端口 (accept 出的 socket 的 localPort)
     * @param peerPort 客户端源端口 (accept 出的 socket 的 remoteSocketAddress.port)
     * @return 匹配连接的 UID; 未匹配或解析失败返回 -1
     */
    fun uidFor(
        localPort: Int,
        peerPort: Int,
    ): Int
}

/**
 * 基于 /proc/net/tcp 与 /proc/net/tcp6 的 UID 解析实现。
 */
class ProcNetUidLookup(
    private val tcpFile: File = File("/proc/net/tcp"),
    private val tcp6File: File = File("/proc/net/tcp6"),
) : UidLookup {
    override fun uidFor(
        localPort: Int,
        peerPort: Int,
    ): Int {
        try {
            for (file in listOf(tcpFile, tcp6File)) {
                if (!file.exists()) continue
                val rows = file.readLines().drop(1)
                val uid = parseUidFromNetTcp(rows, localPort, peerPort)
                if (uid >= 0) return uid
            }
        } catch (_: Exception) {
            // 解析失败时保守拒绝
        }
        return -1
    }
}

/**
 * 从 /proc/net/tcp 行解析与 (localPort, peerPort) 匹配连接的 UID。
 * 抽成纯函数以便单元测试。
 *
 * @param rows 已去除表头的一行或多行 /proc/net/tcp 内容
 * @param localPort 服务器端监听端口 (hex 在 local_address 列)
 * @param peerPort 客户端源端口 (hex 在 rem_address 列)
 * @return 匹配连接的 UID; 未匹配返回 -1
 */
internal fun parseUidFromNetTcp(
    rows: List<String>,
    localPort: Int,
    peerPort: Int,
): Int {
    // 内核 /proc/net/tcp 用 %04X 输出端口 hex (大写), 匹配须忽略大小写
    val hexLocalPort = localPort.toString(16).uppercase().padStart(4, '0')
    val hexPeerPort = peerPort.toString(16).uppercase().padStart(4, '0')

    for (row in rows) {
        val cols = row.trim().split(Regex("\\s+"))
        if (cols.size < 8) continue
        val localAddr = cols[1]
        val remAddr = cols[2]
        if (localAddr.endsWith(":$hexLocalPort", ignoreCase = true) &&
            remAddr.endsWith(":$hexPeerPort", ignoreCase = true)
        ) {
            return cols[7].toInt()
        }
    }
    return -1
}
