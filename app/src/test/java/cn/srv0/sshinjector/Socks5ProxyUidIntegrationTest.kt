package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.ProcNetUidLookup
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * 集成测试: 验证 Socks5ProxyServer.getPeerUid 在真实 socket 连接 + 真实
 * /proc/net/tcp 下能正确解析出发起连接的进程 UID。
 *
 * 背景: VPN 进程 (TcpStateMachine.forwardThroughLocalSocks) connect 到
 * 本地 SOCKS5 服务器 (127.0.0.1:1080)。服务器 accept 后通过 /proc/net/tcp
 * 校验对端 UID 是否为自身。端口方向写反会导致匹配失败返回 -1, 拒绝所有连接。
 *
 * 仅 Linux (存在 /proc/net/tcp) 运行。
 */
class Socks5ProxyUidIntegrationTest {
    @Test
    fun `real local connection uid resolves to current process uid`() {
        assumeTrue("/proc/net/tcp not available, skipping", File("/proc/net/tcp").exists())

        // 1. 服务器监听随机端口 (模拟 Socks5ProxyServer)
        val serverChannel =
            ServerSocketChannel.open().apply {
                configureBlocking(true)
                bind(InetSocketAddress("127.0.0.1", 0))
            }
        val listenPort = (serverChannel.socket() as ServerSocket).localPort

        // 2. 客户端 connect (模拟 VPN 进程发起连接)
        val client = SocketChannel.open(InetSocketAddress("127.0.0.1", listenPort))

        // 3. 服务器 accept
        val accepted = serverChannel.accept()
        val ourPort = accepted.socket().localPort // 监听端口
        val peerPort =
            accepted.socket().remoteSocketAddress
                .let { it as InetSocketAddress }.port // 客户端源端口

        // 4. 模拟 getPeerUid 完整逻辑: 依次尝试 tcp 与 tcp6
        //    注意 Java 的 127.0.0.1 连接可能走 IPv6 双栈 (::ffff:127.0.0.1), 落在 tcp6
        val resolvedUid = resolveUidLikeGetPeerUid(ourPort, peerPort)

        // 5. 对端 UID 必须等于当前进程 UID
        val currentUid = currentProcessUid()
        assertEquals("resolved UID should equal current process UID", currentUid, resolvedUid)

        // 6. 清理
        accepted.close()
        client.close()
        serverChannel.close()
    }

    @Test
    fun `wrong port pair does not resolve`() {
        assumeTrue("/proc/net/tcp not available, skipping", File("/proc/net/tcp").exists())

        val serverChannel =
            ServerSocketChannel.open().apply {
                configureBlocking(true)
                bind(InetSocketAddress("127.0.0.1", 0))
            }
        val listenPort = (serverChannel.socket() as ServerSocket).localPort
        val client = SocketChannel.open(InetSocketAddress("127.0.0.1", listenPort))
        val accepted = serverChannel.accept()

        // 与真实连接完全无关的端口对, 不应匹配到任何行
        val unrelated = resolveUidLikeGetPeerUid(listenPort + 1, 1)

        assertEquals("unrelated port pair must miss", -1, unrelated)

        accepted.close()
        client.close()
        serverChannel.close()
    }

    private fun resolveUidLikeGetPeerUid(
        localPort: Int,
        peerPort: Int,
    ): Int {
        // 使用真实 ProcNetUidLookup (tcp + tcp6), 与 Socks5ProxyServer 生产路径一致
        return ProcNetUidLookup().uidFor(localPort, peerPort)
    }

    private fun currentProcessUid(): Int {
        val output =
            ProcessBuilder("id", "-u")
                .start().inputStream.bufferedReader().readLine()
        return output.trim().toInt()
    }
}
