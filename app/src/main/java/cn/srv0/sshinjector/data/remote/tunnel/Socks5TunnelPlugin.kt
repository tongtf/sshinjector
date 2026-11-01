package cn.srv0.sshinjector.data.remote.tunnel

import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.Socks5ProxyServer
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin

/**
 * SOCKS5-over-SSH 隧道插件。
 *
 * **由 [Socks5TunnelPluginFactory] 创建, 不是 DI 单例**: 一个实例绑定一个出口
 * (一个 `Socks5ProxyServer` 监听 + 一个 `JschSshClient` 池)。多出口 = 多个实例。
 */
class Socks5TunnelPlugin(
    private val client: JschSshClient,
    private val dnsInterceptor: DnsInterceptor,
    private val sshIoDispatcher: cn.srv0.sshinjector.domain.vpn.SshIoDispatcher,
    override val exitId: String,
    private val label: String,
) : TunnelPlugin {
    override val id = "socks5"
    private var socksServer: Socks5ProxyServer? = null

    private var _socksAuth: Pair<String, String>? = null
    override val socksAuth: Pair<String, String>? get() = _socksAuth

    override val localSocksPort: Int
        get() = socksServer?.boundPort?.value ?: 0

    /**
     * 隧道侧资源快照: 代理连接/流量 + SSH 会话池 + SSH IO 线程池。
     * 三段合起来回答"资源有没有在泄漏" (旧实现只有第一段, 池/线程数不可见)。
     * 多出口时前缀出口名, 否则无法判断"哪条出口在失败" (关闭多出口时前缀为空, 文案不变)。
     */
    override fun tunnelDiagnostics(): String {
        val body =
            listOf(
                socksServer?.diagnostics() ?: "",
                client.poolDiagnostics(),
                sshIoDispatcher.diagnostics(),
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        return if (label.isEmpty()) body else "[$label] $body"
    }

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        val c = config as TunnelConfig.Socks5

        return try {
            val sshConfig =
                ServerConfig(
                    // C-1: 运行时隧道曾丢这两个字段 —— 指纹落库与 DB 强校验都失效
                    id = c.serverId,
                    hostKeyFingerprint = c.hostKeyFingerprint,
                    name = "SOCKS5",
                    host = c.sshHost,
                    port = c.sshPort,
                    username = c.sshUsername,
                    keyAlias = c.sshKeyAlias,
                    password = c.sshPassword,
                    keyAlgorithm = ServerConfig.KeyAlgorithm.valueOf(c.sshKeyAlgorithm),
                    connectTimeout = c.common.connectTimeout,
                    keepAliveInterval = c.common.keepAliveInterval,
                )
            val result = client.connect(sshConfig)
            if (!result.success) throw Exception(result.error ?: "SSH connection failed")

            val proxy = Socks5ProxyServer(client, dnsInterceptor, sshIoDispatcher)
            // 每次连接生成新凭据 (RFC 1929); 必须在 start() 前设置, 否则先到的连接会 fail-closed 被拒
            val auth = generateSocksAuth()
            proxy.setExpectedAuth(auth.first, auth.second)
            _socksAuth = auth
            val proxyResult = proxy.start(c.socksPort, "127.0.0.1")
            if (proxyResult.isFailure) throw proxyResult.exceptionOrNull()!!

            socksServer = proxy
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        try {
            socksServer?.stop()
        } catch (_: Exception) {
        }
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
        socksServer = null
        _socksAuth = null
    }

    /** 16 字节随机 → URL-safe Base64 (22 字符, ≤ RFC 1929 的 255 上限), 每次连接新生成。 */
    private fun generateSocksAuth(): Pair<String, String> {
        val rnd = java.security.SecureRandom()

        fun token(): String {
            val bytes = ByteArray(16)
            rnd.nextBytes(bytes)
            return java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes)
        }
        return token() to token()
    }

    override fun registerTunCallback(
        clientPort: Int,
        callback: (ByteArray, Int, Int) -> Unit,
    ) {
        socksServer?.registerTunCallback(clientPort, callback)
    }

    override fun removeTunCallback(clientPort: Int) {
        socksServer?.removeTunCallback(clientPort)
    }

    override fun registerTargetEofCallback(
        clientPort: Int,
        callback: (Boolean) -> Unit,
    ) {
        socksServer?.registerTargetEofCallback(clientPort, callback)
    }

    override fun removeTargetEofCallback(clientPort: Int) {
        socksServer?.removeTargetEofCallback(clientPort)
    }
}
