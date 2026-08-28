package cn.srv0.sshinjector.di

import android.content.Context
import cn.srv0.sshinjector.data.local.database.AppDatabase
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.config.ServerProvisioner
import cn.srv0.sshinjector.data.remote.ssh.CredentialCrypto
import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.data.remote.ssh.KnownHostsManager
import cn.srv0.sshinjector.data.remote.ssh.RemoteCommandExecutor
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.model.ServerProvisionerContract
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.PacketProcessor
import cn.srv0.sshinjector.domain.vpn.SshIoDispatcher
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
    ): AppDatabase = AppDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideServerDao(db: AppDatabase): cn.srv0.sshinjector.data.local.dao.ServerDao = db.serverDao()

    @Provides
    @Singleton
    fun provideWhitelistDao(db: AppDatabase): cn.srv0.sshinjector.data.local.dao.WhitelistDao = db.whitelistDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): SettingsDataStore = SettingsDataStore(context)

    @Provides
    @Singleton
    fun provideCredentialCrypto(): CredentialCrypto = CredentialCrypto()

    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: cn.srv0.sshinjector.data.local.dao.ServerDao,
        whitelistDao: cn.srv0.sshinjector.data.local.dao.WhitelistDao,
        credentialCrypto: CredentialCrypto,
    ): ServerRepository = ServerRepository(serverDao, whitelistDao, credentialCrypto)

    @Provides
    @Singleton
    fun provideSshKeyManager(
        @ApplicationContext context: Context,
    ): SshKeyManager = SshKeyManager(context)

    @Provides
    @Singleton
    fun provideKnownHostsManager(
        @ApplicationContext context: Context,
    ): KnownHostsManager = KnownHostsManager(context)

    @Provides
    @Singleton
    fun provideJschSshClient(
        keyManager: SshKeyManager,
        knownHostsManager: KnownHostsManager,
    ): JschSshClient = JschSshClient(keyManager, knownHostsManager)

    @Provides
    @Singleton
    fun provideRemoteCommandExecutor(client: JschSshClient): RemoteCommandExecutor = client

    @Provides
    @Singleton
    fun providePacketProcessor(
        tunnelManager: TunnelManager,
        sshIoDispatcher: SshIoDispatcher,
    ): PacketProcessor = PacketProcessor(tunnelManager, sshIoDispatcher)

    @Provides
    @Singleton
    fun provideDnsInterceptor(): DnsInterceptor = DnsInterceptor()

    @Provides
    @Singleton
    fun provideServerProvisioner(
        @ApplicationContext context: Context,
        commandExecutor: RemoteCommandExecutor,
    ): ServerProvisionerContract = ServerProvisioner(context, commandExecutor)
}
