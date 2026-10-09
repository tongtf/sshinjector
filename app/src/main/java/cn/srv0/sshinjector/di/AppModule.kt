package cn.srv0.sshinjector.di

import android.content.Context
import cn.srv0.sshinjector.data.local.database.AppDatabase
import cn.srv0.sshinjector.data.remote.config.ServerProvisioner
import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.data.remote.ssh.RemoteCommandExecutor
import cn.srv0.sshinjector.domain.model.ServerProvisionerContract
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
    fun provideRemoteCommandExecutor(client: JschSshClient): RemoteCommandExecutor = client

    @Provides
    @Singleton
    fun provideServerProvisioner(
        @ApplicationContext context: Context,
        commandExecutor: RemoteCommandExecutor,
    ): ServerProvisionerContract = ServerProvisioner(context, commandExecutor)
}
