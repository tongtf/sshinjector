package com.sshinjector.di

import com.sshinjector.data.remote.tunnel.DirectTunnelPlugin
import com.sshinjector.data.remote.tunnel.HttpsProxyTunnelPlugin
import com.sshinjector.data.remote.tunnel.ShadowsocksTunnelPlugin
import com.sshinjector.data.remote.tunnel.Socks5TunnelPlugin
import com.sshinjector.data.remote.tunnel.TrojanTunnelPlugin
import com.sshinjector.data.remote.tunnel.V2RayTunnelPlugin
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelModule {

    @Binds @IntoMap @StringKey("socks5")
    abstract fun bindSocks5(impl: Socks5TunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("direct")
    abstract fun bindDirect(impl: DirectTunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("https_proxy")
    abstract fun bindHttpsProxy(impl: HttpsProxyTunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("v2ray")
    abstract fun bindV2Ray(impl: V2RayTunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("trojan")
    abstract fun bindTrojan(impl: TrojanTunnelPlugin): TunnelPlugin

    @Binds @IntoMap @StringKey("shadowsocks")
    abstract fun bindShadowsocks(impl: ShadowsocksTunnelPlugin): TunnelPlugin
}
