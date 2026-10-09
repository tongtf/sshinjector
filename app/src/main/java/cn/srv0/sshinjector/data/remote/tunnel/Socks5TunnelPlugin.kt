package cn.srv0.sshinjector.data.remote.tunnel

import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.Socks5ProxyServer
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Socks5TunnelPlugin
    @Inject
    constructor(
        private val jschClient: JschSshClient,
        private val dnsInterceptor: DnsInterceptor,
        private val sshIoDispatcher: cn.srv0.sshinjector.domain.vpn.SshIoDispatcher,
    ) : TunnelPlugin {
        override val id = "socks5"
        private var socksServer: Socks5ProxyServer? = null

        private var _socksAuth: Pair<String, String>? = null
        override val socksAuth: Pair<String, String>? get() = _socksAuth

        override val localSocksPort: Int
            get() = socksServer?.boundPort?.value ?: 0

        override fun tunnelDiagnostics(): String = socksServer?.diagnostics() ?: ""

        override suspend fun connect(config: TunnelConfig): Result<Unit> {
            val c = config as TunnelConfig.Socks5

            return try {
                val sshConfig =
                    ServerConfig(
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
                val result = jschClient.connect(sshConfig)
                if (!result.success) throw Exception(result.error ?: "SSH connection failed")

                val proxy = Socks5ProxyServer(jschClient, dnsInterceptor, sshIoDispatcher)
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
                jschClient.disconnect()
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

        override fun openTcpChannel(
            host: String,
            port: Int,
        ): TunnelChannel? = jschClient.createDirectChannel(host, port)

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
