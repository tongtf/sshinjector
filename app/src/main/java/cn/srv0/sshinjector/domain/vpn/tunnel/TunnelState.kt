package cn.srv0.sshinjector.domain.vpn.tunnel

data class TunnelState(
    val status: Status = Status.Disconnected,
    val error: String? = null,
    val serverAddress: String? = null,
) {
    enum class Status {
        Disconnected,
        Connecting,
        Authenticating,
        Connected,
        Disconnecting,
        Failed
    }
}

data class TunnelStats(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val activeTcpConnections: Int = 0,
    val uptimeMs: Long = 0,
)
