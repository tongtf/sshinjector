package com.sshinjector.domain.vpn.tunnel

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
        val sshKeyAlgorithm: String = "Ed25519",
        val socksPort: Int = 1080,
    ) : TunnelConfig()

    data object Direct : TunnelConfig() {
        override val common: CommonConfig = CommonConfig()
    }

    data class HttpsProxy(
        override val common: CommonConfig = CommonConfig(),
        val proxyHost: String,
        val proxyPort: Int = 443,
        val username: String? = null,
        val password: String? = null,
        val useTls: Boolean = true,
        val sni: String? = null,
    ) : TunnelConfig()

    data class V2Ray(
        override val common: CommonConfig = CommonConfig(),
        val serverHost: String,
        val serverPort: Int = 443,
        val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",
        val network: String = "tcp",
        val path: String? = null,
        val serviceName: String? = null,
        val useTls: Boolean = true,
        val sni: String? = null,
        val allowInsecure: Boolean = false,
    ) : TunnelConfig()

    data class Trojan(
        override val common: CommonConfig = CommonConfig(),
        val serverHost: String,
        val serverPort: Int = 443,
        val password: String,
        val sni: String? = null,
        val allowInsecure: Boolean = false,
        val peer: String? = null,
    ) : TunnelConfig()

    data class Shadowsocks(
        override val common: CommonConfig = CommonConfig(),
        val serverHost: String,
        val serverPort: Int = 8388,
        val password: String,
        val method: String = "aes-256-gcm",
        val plugin: String? = null,
        val pluginOpts: String? = null,
    ) : TunnelConfig()
}
