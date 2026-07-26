package com.sshinjector.data.remote.tunnel

import com.sshinjector.R
import com.sshinjector.domain.vpn.TunnelChannel
import com.sshinjector.domain.vpn.tunnel.ConfigField
import com.sshinjector.domain.vpn.tunnel.TunnelCapability
import com.sshinjector.domain.vpn.tunnel.TunnelConfig
import com.sshinjector.domain.vpn.tunnel.TunnelConfigDescriptor
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import com.sshinjector.domain.vpn.tunnel.TunnelState
import com.sshinjector.domain.vpn.tunnel.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class V2RayTunnelPlugin @Inject constructor() : TunnelPlugin {

    override val id = "v2ray"
    override val displayName = "V2Ray"
    override val iconResId = R.drawable.ic_vpn_key
    override val capabilities = setOf(
        TunnelCapability.TCP,
        TunnelCapability.UDP,
        TunnelCapability.DNS_OVER_TUNNEL,
        TunnelCapability.DOMAIN_RESOLVE,
        TunnelCapability.IP_CONNECT,
        TunnelCapability.TLS,
    )

    override val configDescriptor = TunnelConfigDescriptor(
        fields = listOf(
            ConfigField.TextField(key = "serverHost", label = "服务器地址"),
            ConfigField.NumberField(key = "serverPort", label = "端口", defaultValue = 443),
            ConfigField.TextField(key = "uuid", label = "UUID"),
            ConfigField.NumberField(key = "alterId", label = "AlterID", defaultValue = 0, min = 0),
            ConfigField.DropdownField(
                key = "security", label = "加密方式",
                options = listOf("auto" to "自动", "aes-128-gcm" to "AES-128-GCM", "chacha20-poly1305" to "ChaCha20", "none" to "无")
            ),
            ConfigField.DropdownField(
                key = "network", label = "传输协议",
                options = listOf("tcp" to "TCP", "ws" to "WebSocket", "grpc" to "gRPC")
            ),
            ConfigField.TextField(key = "path", label = "路径 (WS/gRPC)", required = false),
            ConfigField.SwitchField(key = "useTls", label = "使用 TLS", defaultValue = true),
            ConfigField.TextField(key = "sni", label = "SNI", required = false),
            ConfigField.SwitchField(key = "allowInsecure", label = "允许不安全连接", defaultValue = false),
        )
    )

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats

    override suspend fun connect(config: TunnelConfig): Result<Unit> {
        // Phase 3: integrate V2Ray-core
        TODO("Phase 3: V2Ray implementation")
    }

    override suspend fun disconnect() {
        TODO("Phase 3")
    }

    override fun openTcpChannel(host: String, port: Int): TunnelChannel? {
        TODO("Phase 3")
    }
}
