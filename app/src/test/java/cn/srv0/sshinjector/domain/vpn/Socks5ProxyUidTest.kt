package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 getPeerUid 使用的端口匹配逻辑 (parseUidFromNetTcp)。
 *
 * 回归场景: 端口方向写反 (remAddr==localPort && localAddr==peerPort) 导致
 * 永远匹配不到 /proc/net/tcp 中的连接行, getPeerUid 返回 -1,
 * Socks5ProxyServer 拒绝所有来自 VPN 进程的连接, 白名单 APP 无法访问网络。
 */
class Socks5ProxyUidTest {
    private val header =
        listOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        )

    private fun procRow(
        local: String,
        remote: String,
        st: String,
        uid: Int,
    ): String = "  0: $local $remote $st 00000000:00000000 00:00000000 00000000 $uid 0 12345 1 0000000000000000 0 0 0"

    @Test
    fun `match returns uid when localPort is in local_address`() {
        // VPN 进程 (uid=10123) 连接 127.0.0.1:1080, 源端口 52382
        // /proc/net/tcp 行 (服务器视角): local=127.0.0.1:1080, rem=127.0.0.1:52382
        val rows =
            header +
                procRow(
                    // 127.0.0.1:1080 / 127.0.0.1:52382
                    local = "0100007F:0438",
                    remote = "0100007F:CC9E",
                    st = "01", uid = 10123,
                )
        assertEquals(10123, parseUidFromNetTcp(rows, localPort = 1080, peerPort = 52382))
    }

    @Test
    fun `wrong direction does not match`() {
        // 若方向写反 (localPort 去匹配 rem_address), 则匹配不到
        val rows =
            header +
                procRow(
                    local = "0100007F:0438",
                    remote = "0100007F:CC9E",
                    st = "01", uid = 10123,
                )
        // 反方向: localPort=52382 并不在 local_address 中
        assertEquals(-1, parseUidFromNetTcp(rows, localPort = 52382, peerPort = 1080))
    }

    @Test
    fun `other connections on the listen port are not matched`() {
        // 存在其它连接到 1080 的客户端, 端口不匹配时应忽略
        val rows =
            header +
                procRow(local = "0100007F:0438", remote = "0100007F:AAAA", st = "01", uid = 99999) +
                procRow(local = "0100007F:0438", remote = "0100007F:CC9E", st = "01", uid = 10123)
        assertEquals(10123, parseUidFromNetTcp(rows, localPort = 1080, peerPort = 52382))
    }

    @Test
    fun `no match returns -1`() {
        val rows =
            header +
                procRow(
                    local = "0100007F:0438", remote = "0100007F:CC9E", st = "01", uid = 10123,
                )
        assertEquals(-1, parseUidFromNetTcp(rows, localPort = 1080, peerPort = 9999))
    }

    @Test
    fun `ipv6 row is parsed with same direction`() {
        // ::1 在 /proc/net/tcp6 中的表示 (IPv6 地址转 32 hex 字符)
        val local6 = "00000000000000000000000001000000" + ":0438" // [::1]:1080
        val remote6 = "00000000000000000000000001000000" + ":CC9E" // [::1]:52382
        val row =
            " 0: $local6 $remote6 01 00000000:00000000 00:00000000 00000000 10123 0 12345 1 0000000000000000 0 0 0"
        assertEquals(10123, parseUidFromNetTcp(listOf(row), localPort = 1080, peerPort = 52382))
    }

    @Test
    fun `malformed rows are skipped`() {
        val badRows =
            listOf(
                // 列数不足, 应被跳过
                "  0: 0100007F:0438 0100007F:CC9E",
                "  garbage line",
                "",
            )
        assertEquals(-1, parseUidFromNetTcp(badRows, localPort = 1080, peerPort = 52382))
    }
}
