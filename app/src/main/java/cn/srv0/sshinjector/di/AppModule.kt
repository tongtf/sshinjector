package cn.srv0.sshinjector.di

import android.content.Context
import cn.srv0.sshinjector.data.local.database.AppDatabase
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.ssh.CredentialCrypto
import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.data.remote.ssh.KnownHostsManager
import cn.srv0.sshinjector.data.remote.ssh.RemoteCommandExecutor
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.PacketProcessor
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideServerDao(db: AppDatabase): cn.srv0.sshinjector.data.local.dao.ServerDao {
        return db.serverDao()
    }

    @Provides
    @Singleton
    fun provideWhitelistDao(db: AppDatabase): cn.srv0.sshinjector.data.local.dao.WhitelistDao {
        return db.whitelistDao()
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): SettingsDataStore {
        return SettingsDataStore(context)
    }

    @Provides
    @Singleton
    fun provideCredentialCrypto(): CredentialCrypto {
        return CredentialCrypto()
    }

    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: cn.srv0.sshinjector.data.local.dao.ServerDao,
        whitelistDao: cn.srv0.sshinjector.data.local.dao.WhitelistDao,
        credentialCrypto: CredentialCrypto,
    ): ServerRepository {
        return ServerRepository(serverDao, whitelistDao, credentialCrypto)
    }

    @Provides
    @Singleton
    fun provideSshKeyManager(
        @ApplicationContext context: Context,
    ): SshKeyManager {
        return SshKeyManager(context)
    }

    @Provides
    @Singleton
    fun provideKnownHostsManager(
        @ApplicationContext context: Context,
    ): KnownHostsManager {
        return KnownHostsManager(context)
    }

    @Provides
    @Singleton
    fun provideJschSshClient(
        keyManager: SshKeyManager,
        knownHostsManager: KnownHostsManager,
    ): JschSshClient {
        return JschSshClient(keyManager, knownHostsManager)
    }

    @Provides
    @Singleton
    fun provideRemoteCommandExecutor(client: JschSshClient): RemoteCommandExecutor = client

    @Provides
    @Singleton
    fun providePacketProcessor(tunnelManager: TunnelManager): PacketProcessor {
        return PacketProcessor(tunnelManager)
    }

    @Provides
    @Singleton
    fun provideDnsInterceptor(): DnsInterceptor {
        return DnsInterceptor()
    }
}
