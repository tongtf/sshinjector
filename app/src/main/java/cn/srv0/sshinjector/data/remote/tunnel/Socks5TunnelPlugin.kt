package cn.srv0.sshinjector.data.remote.tunnel

import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.Socks5ProxyServer
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import cn.srv0.sshinjector.domain.vpn.tunnel.ConfigField
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelCapability
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfigDescriptor
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelState
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        override val displayName = "SOCKS5 (SSH)"
        override val iconResId = R.drawable.ic_vpn_key
        override val capabilities =
            setOf(
                TunnelCapability.TCP,
                TunnelCapability.DNS_OVER_TUNNEL,
                TunnelCapability.DOMAIN_RESOLVE,
                TunnelCapability.IP_CONNECT,
            )

        override val configDescriptor =
            TunnelConfigDescriptor(
                fields =
                    listOf(
                        ConfigField.TextField(key = "sshHost", label = "SSH 服务器", placeholder = "example.com"),
                        ConfigField.NumberField(key = "sshPort", label = "SSH 端口", defaultValue = 22),
                        ConfigField.TextField(key = "sshUsername", label = "用户名"),
                        ConfigField.TextField(key = "sshKeyAlias", label = "密钥别名"),
                        ConfigField.TextField(key = "sshPassword", label = "密码", isPassword = true, required = false),
                        ConfigField.DropdownField(
                            key = "sshKeyAlgorithm",
                            label = "密钥算法",
                            options =
                                listOf("ECDSA_P256" to "ECDSA P-256", "Ed25519" to "Ed25519", "RSA4096" to "RSA 4096"),
                        ),
                        ConfigField.NumberField(
                            key = "socksPort",
                            label = "本地 SOCKS 端口",
                            defaultValue = 1080,
                            min = 1024,
                        ),
                    ),
            )

        private val _state = MutableStateFlow(TunnelState())
        override val state: StateFlow<TunnelState> = _state.asStateFlow()

        private val _stats = MutableStateFlow(TunnelStats())
        override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

        private var socksServer: Socks5ProxyServer? = null
        private var startTime: Long = 0

        private var _socksAuth: Pair<String, String>? = null
        override val socksAuth: Pair<String, String>? get() = _socksAuth

        override val localSocksPort: Int
            get() = socksServer?.boundPort?.value ?: 0

        override fun tunnelDiagnostics(): String = socksServer?.diagnostics() ?: ""

        override suspend fun connect(config: TunnelConfig): Result<Unit> {
            val c = config as TunnelConfig.Socks5
            _state.value = TunnelState(status = TunnelState.Status.Connecting, serverAddress = c.sshHost)

            return try {
                _state.value = _state.value.copy(status = TunnelState.Status.Authenticating)
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
                startTime = System.currentTimeMillis()
                _state.value = TunnelState(status = TunnelState.Status.Connected, serverAddress = c.sshHost)
                Result.success(Unit)
            } catch (e: Exception) {
                _state.value = TunnelState(status = TunnelState.Status.Failed, error = e.message)
                disconnect()
                Result.failure(e)
            }
        }

        override suspend fun disconnect() {
            _state.value = _state.value.copy(status = TunnelState.Status.Disconnecting)
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
            startTime = 0
            _state.value = TunnelState()
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

        override fun sendUdp(
            dstHost: String,
            dstPort: Int,
            payload: ByteArray,
        ): Unit = throw UnsupportedOperationException("UDP not supported by $id")

        override suspend fun forwardDns(query: ByteArray): ByteArray? = null

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
