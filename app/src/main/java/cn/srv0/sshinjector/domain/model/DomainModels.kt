package cn.srv0.sshinjector.domain.model

import java.util.*

data class ServerConfig(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val keyAlias: String,
    val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.ECDSA_P256,
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
    val hostKeyFingerprint: String? = null, // SSH Host Key 指纹 (SHA256)
    val keyPassphrase: String? = null, // 私钥 passphrase（遗留，由 SshKeyManager 独立管理）
    val remoteDnsServer: String = "8.8.8.8", // REMOTE 模式 DNS 解析服务器
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

/**
 * 连接链路健康归因步骤: 运行期端到端探测失败或连接期失败时, 告诉用户是哪一步出了问题。
 * label 为用户可见文案 (与代码库既有惯例一致, UI 文案在 VM 层硬编码中文)。
 */
enum class HealthStep(
    val label: String,
) {
    SSH("SSH 连接"),
    AUTH("身份认证"),
    PROXY("本地代理"),
    TUNNEL("隧道通道"),
    DNS("DNS 解析"),
    REMOTE("远端网络"),
    TUN("虚拟网卡"),
}

data class VpnState(
    val status: VpnStatus = VpnStatus.Disconnected,
    val server: ServerConfig? = null,
    val stats: ConnectionStats = ConnectionStats(),
    val error: String? = null,
    /** Connected 且端到端探测通过才为 true (已建立 ≠ 已验证可用)。 */
    val verified: Boolean = false,
    /** 当前故障步骤; null = 无故障或未归因。 */
    val failedStep: HealthStep? = null,
    /** 隧道出口 IP (IP 回显获取, 会话级缓存); null = 未取回。 */
    val exitIp: String? = null,
) {
    enum class VpnStatus {
        Disconnected,
        Connecting,
        Connected,
        Disconnecting,
        Failed,
    }
}
