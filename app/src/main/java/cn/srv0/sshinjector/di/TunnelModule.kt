package cn.srv0.sshinjector.di

import cn.srv0.sshinjector.data.remote.tunnel.Socks5TunnelPluginFactory
import cn.srv0.sshinjector.data.remote.tunnel.TunnelPluginFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * 隧道工厂注册表: **按协议名**绑定工厂 (不是插件实例)。
 * 插件实例由 `TunnelManager.startExit` 按出口创建 —— 主出口也走同一条路。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelModule {
    @Binds @IntoMap
    @StringKey("socks5")
    abstract fun bindSocks5Factory(impl: Socks5TunnelPluginFactory): TunnelPluginFactory
}
