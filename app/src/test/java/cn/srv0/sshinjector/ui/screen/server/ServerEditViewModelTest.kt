package cn.srv0.sshinjector.ui.screen.server

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.entity.DnsMode
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.ssh.CredentialCrypto
import cn.srv0.sshinjector.data.remote.ssh.KnownHostsManager
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ServerEditViewModelTest {
    private lateinit var serverDao: ServerDao
    private lateinit var credentialCrypto: CredentialCrypto
    private lateinit var viewModel: ServerEditViewModel

    @Before
    fun setUp() {
        // ViewModel init 会在构造时 launch (collect 全局设置流), 需先初始化 Main dispatcher
        Dispatchers.setMain(StandardTestDispatcher())
        serverDao = mock()
        credentialCrypto = mock()
        whenever(credentialCrypto.encrypt(any())).thenAnswer { invocation ->
            invocation.getArgument<String?>(0)?.let { "enc:v1:$it" }
        }
        val settings = mock<SettingsDataStore>()
        // init 中 collect 全局设置流, mock 需要返回非空流
        org.mockito.kotlin
            .doReturn(kotlinx.coroutines.flow.emptyFlow<Int?>())
            .`when`(settings)
            .mtu
        org.mockito.kotlin
            .doReturn(kotlinx.coroutines.flow.emptyFlow<Int?>())
            .`when`(settings)
            .keepAlive
        org.mockito.kotlin
            .doReturn(kotlinx.coroutines.flow.emptyFlow<Boolean?>())
            .`when`(settings)
            .enableIPv6
        viewModel =
            ServerEditViewModel(
                serverDao,
                mock<SshKeyManager>(),
                mock<KnownHostsManager>(),
                settings,
                credentialCrypto,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new server insert encrypts password`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverDao.insert(any())).thenReturn(1L)

            viewModel.save(-1L, entity(password = "secret"), {})
            testScheduler.advanceUntilIdle()

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).insert(captor.capture())
            assertEquals("enc:v1:secret", captor.firstValue.password)
        }

    @Test
    fun `edit preserves stored password and uneditable fields`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val stored =
                ServerEntity(
                    id = 7,
                    name = "old",
                    host = "a.com",
                    port = 22,
                    username = "u",
                    keyAlias = "k",
                    password = "enc:v1:stored-pass",
                    hostKeyFingerprint = "SHA256:abc",
                    dnsMode = DnsMode.LOCAL,
                    remoteDnsServer = "1.1.1.1",
                    allowedPackages = "[\"com.a\"]",
                    createdAt = Date(12345),
                )
            whenever(serverDao.getByIdBlocking(7)).thenReturn(stored)
            whenever(serverDao.update(any())).thenReturn(1)

            viewModel.save(7, entity(id = 7, name = "new name", host = "b.com"), {})
            testScheduler.advanceUntilIdle()

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).update(captor.capture())
            val saved = captor.firstValue
            assertEquals("b.com", saved.host)
            assertEquals("enc:v1:stored-pass", saved.password)
            assertEquals("SHA256:abc", saved.hostKeyFingerprint)
            assertEquals(DnsMode.LOCAL, saved.dnsMode)
            assertEquals("1.1.1.1", saved.remoteDnsServer)
            assertEquals("[\"com.a\"]", saved.allowedPackages)
            assertEquals(Date(12345), saved.createdAt)
        }

    @Test
    fun `edit with new password encrypts it before update`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverDao.getByIdBlocking(7)).thenReturn(entity(id = 7))
            whenever(serverDao.update(any())).thenReturn(1)

            viewModel.save(7, entity(id = 7, password = "new-secret"), {})
            testScheduler.advanceUntilIdle()

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).update(captor.capture())
            assertEquals("enc:v1:new-secret", captor.firstValue.password)
        }

    private fun entity(
        id: Long = 0,
        name: String = "vps",
        host: String = "example.com",
        password: String? = null,
    ) = ServerEntity(
        id = id,
        name = name,
        host = host,
        port = 22,
        username = "root",
        keyAlias = "k",
        password = password,
    )
}
