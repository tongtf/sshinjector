package com.sshinjector.di

import com.sshinjector.data.remote.tunnel.Socks5TunnelPlugin
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
}