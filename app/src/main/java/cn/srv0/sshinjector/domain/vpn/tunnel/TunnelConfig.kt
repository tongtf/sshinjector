package cn.srv0.sshinjector.domain.vpn.tunnel

sealed class TunnelConfig {
    abstract val common: CommonConfig

    data class CommonConfig(
        val connectTimeout: Int = 10000,
        val keepAliveInterval: Int = 30000,
    )

    data class Socks5(
        override val common: CommonConfig = CommonConfig(),
        val sshHost: String,
        val sshPort: Int = 22,
        val sshUsername: String,
        val sshKeyAlias: String,
        val sshPassword: String? = null,
        val sshKeyAlgorithm: String = "ECDSA_P256",
        val socksPort: Int = 1080,
    ) : TunnelConfig()
}
