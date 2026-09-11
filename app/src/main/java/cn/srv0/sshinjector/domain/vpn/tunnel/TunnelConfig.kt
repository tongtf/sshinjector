package cn.srv0.sshinjector.domain.vpn.tunnel

import cn.srv0.sshinjector.domain.model.ServerConfig

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

    companion object {
        /**
         * 从 domain [ServerConfig] 构建 SOCKS5-over-SSH 隧道配置。
         * 显式 [password] 优先于服务器存储的密码（运行时覆盖）。
         */
        fun forSocks5(
            server: ServerConfig,
            password: String? = null,
        ): Socks5 =
            Socks5(
                sshHost = server.host,
                sshPort = server.port,
                sshUsername = server.username,
                sshKeyAlias = server.keyAlias,
                sshPassword = password ?: server.password,
                sshKeyAlgorithm = server.keyAlgorithm.name,
                common =
                    CommonConfig(
                        connectTimeout = server.connectTimeout,
                        keepAliveInterval = server.keepAliveInterval,
                    ),
                socksPort = server.socksPort,
            )
    }
}
