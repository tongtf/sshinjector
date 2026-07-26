package com.sshinjector.di

import android.content.Context
import com.sshinjector.data.local.database.AppDatabase
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.data.remote.ssh.JschSshClient
import com.sshinjector.data.remote.ssh.SshKeyManager
import com.sshinjector.domain.usecase.ServerRepository
import com.sshinjector.domain.vpn.DnsInterceptor
import com.sshinjector.domain.vpn.PacketProcessor
import com.sshinjector.domain.vpn.Socks5ProxyServer
import com.sshinjector.domain.vpn.SshChannelFactory
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }
    
    @Provides
    @Singleton
    fun provideServerDao(db: AppDatabase): com.sshinjector.data.local.dao.ServerDao {
        return db.serverDao()
    }
    
    @Provides
    @Singleton
    fun provideWhitelistDao(db: AppDatabase): com.sshinjector.data.local.dao.WhitelistDao {
        return db.whitelistDao()
    }
    
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore {
        return SettingsDataStore(context)
    }
    
    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: com.sshinjector.data.local.dao.ServerDao,
        whitelistDao: com.sshinjector.data.local.dao.WhitelistDao
    ): ServerRepository {
        return ServerRepository(serverDao, whitelistDao)
    }
    
    @Provides
    @Singleton
    fun provideSshKeyManager(@ApplicationContext context: Context): SshKeyManager {
        return SshKeyManager(context)
    }
    
    @Provides
    @Singleton
    fun provideJschSshClient(keyManager: SshKeyManager): JschSshClient {
        return JschSshClient(keyManager)
    }

    @Provides
    @Singleton
    fun provideSshChannelFactory(sshClient: JschSshClient): SshChannelFactory {
        return sshClient
    }
    
    @Provides
    @Singleton
    fun provideSocks5ProxyServer(sshChannelFactory: SshChannelFactory): Socks5ProxyServer {
        return Socks5ProxyServer(sshChannelFactory)
    }
    
    @Provides
    @Singleton
    fun providePacketProcessor(socks5Proxy: Socks5ProxyServer): PacketProcessor {
        return PacketProcessor(socks5Proxy)
    }
    
    @Provides
    @Singleton
    fun provideDnsInterceptor(): DnsInterceptor {
        return DnsInterceptor()
    }
}