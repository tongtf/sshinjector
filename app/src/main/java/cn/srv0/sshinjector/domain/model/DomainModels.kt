package cn.srv0.sshinjector.domain.model

import java.net.InetAddress
import java.util.*

data class ServerConfig(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val keyAlias: String,
    val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
    var isActive: Boolean = false,
    val createdAt: Date = Date(),
    var updatedAt: Date = Date(),
    var lastConnectedAt: Date? = null,
    val connectTimeout: Int = 10000,
    val keepAliveInterval: Int = 30000,
    val mtu: Int = 1500,
    val enableIPv6: Boolean = true,
    val dnsMode: DnsMode = DnsMode.Remote,
    val allowedPackages: List<String> = emptyList(),
    val excludedRoutes: List<String> = emptyList(),
    val password: String? = null, // 可选：SSH 密码认证
    val socksPort: Int = 1080, // 本地 SOCKS5 监听端口
) {
    enum class KeyAlgorithm { Ed25519, RSA4096, ECDSA_P256 }
    enum class DnsMode { Remote, Local, System }
}

data class WhitelistApp(
    val packageName: String,
    val appName: String,
    val iconHash: String = "",
    var isEnabled: Boolean = true,
    val addedAt: Date = Date(),
    var lastUsedAt: Date? = null,
)

data class ConnectionStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val startTime: Date = Date(),
    val lastUpdate: Date = Date(),
)

data class VpnState(
    val status: VpnStatus = VpnStatus.Disconnected,
    val server: ServerConfig? = null,
    val stats: ConnectionStats = ConnectionStats(),
    val error: String? = null,
    val isReconnecting: Boolean = false,
) {
    enum class VpnStatus {
        Disconnected,
        Connecting,
        Authenticating,
        EstablishingTunnel,
        Connected,
        Disconnecting,
        Reconnecting,
        Failed
    }
}