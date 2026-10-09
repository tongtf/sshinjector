package cn.srv0.sshinjector.domain.model

import androidx.annotation.StringRes
import cn.srv0.sshinjector.R
import java.util.Date

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
    val connectTimeout: Int = 10000,
    /** SSH keepAlive 周期, 单位: 秒 (与 ServerEntity/UI 一致 — 曾因按毫秒 delay 导致每 30ms 一个心跳包) */
    val keepAliveInterval: Int = 30,
    val mtu: Int = 1500,
    val enableIPv6: Boolean = true,
    val excludedRoutes: List<String> = emptyList(),
    val password: String? = null, // 可选：SSH 密码认证
    val socksPort: Int = 1080, // 本地 SOCKS5 监听端口
    val hostKeyFingerprint: String? = null, // SSH Host Key 指纹 (SHA256)
) {
    enum class KeyAlgorithm { Ed25519, RSA4096, ECDSA_P256 }
}

data class WhitelistApp(
    val packageName: String,
    val appName: String,
    var isEnabled: Boolean = true,
    val addedAt: Date = Date(),
)

data class ConnectionStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val startTime: Date = Date(),
)

/**
 * 连接链路健康归因步骤: 运行期端到端探测失败或连接期失败时, 告诉用户是哪一步出了问题。
 * 文案经 [labelRes] 走字符串资源 (多语言), 由 StatusDisplay 统一渲染。
 */
enum class HealthStep(
    @StringRes val labelRes: Int,
) {
    SSH(R.string.health_step_ssh),
    AUTH(R.string.health_step_auth),
    PROXY(R.string.health_step_proxy),
    TUNNEL(R.string.health_step_tunnel),
    DNS(R.string.health_step_dns),
    REMOTE(R.string.health_step_remote),
    TUN(R.string.health_step_tun),
    FORWARD(R.string.health_step_forward),
}

/** 连接流程阶段 (仅 Connecting 期间有意义); 文案经 [labelRes] 走字符串资源, 与 HealthStep 同风格。 */
enum class ConnectStage(
    @StringRes val labelRes: Int,
) {
    LOAD(R.string.connect_stage_load),
    TUN(R.string.connect_stage_tun),
    TUNNEL(R.string.connect_stage_tunnel),
    DNS(R.string.connect_stage_dns),
    ROUTES(R.string.connect_stage_routes),
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
    /** 当前连接流程阶段; null = 无阶段 (非连接中或未细分)。 */
    val connectStage: ConnectStage? = null,
) {
    enum class VpnStatus {
        Disconnected,
        Connecting,
        Connected,
        Disconnecting,
        Failed,
    }
}
