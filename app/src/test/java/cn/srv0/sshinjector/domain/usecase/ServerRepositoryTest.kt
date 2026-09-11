package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.entity.DnsMode
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.remote.ssh.CredentialCrypto
import cn.srv0.sshinjector.domain.model.ServerConfig
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date

/**
 * ServerRepository.saveServerEdit 的凭据加密与不可编辑字段保留契约。
 *
 * 入参为 domain 层 [ServerConfig]（UI 不再接触 Entity）；本测试用真实 Repository +
 * mock DAO/Crypto 锁定行为，含"密码未变则保留原密文字节"这一关键不变量。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {
    private lateinit var serverDao: ServerDao
    private lateinit var credentialCrypto: CredentialCrypto
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        serverDao = mock()
        val whitelistDao = mock<WhitelistDao>()
        credentialCrypto = mock()
        whenever(credentialCrypto.encrypt(any())).thenAnswer { inv ->
            inv.getArgument<String?>(0)?.let { "enc:v1:$it" }
        }
        repository = ServerRepository(serverDao, whitelistDao, credentialCrypto)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun config(
        id: Long = 0,
        name: String = "vps",
        host: String = "example.com",
        password: String? = null,
    ) = ServerConfig(id = id, name = name, host = host, username = "root", keyAlias = "k", password = password)

    @Test
    fun `new server insert encrypts password`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverDao.insert(any())).thenReturn(1L)

            repository.saveServerEdit(-1L, config(password = "secret"), setAsDefault = false)

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).insert(captor.capture())
            assertEquals("enc:v1:secret", captor.firstValue.password)
            assertEquals(false, captor.firstValue.isActive)
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
                    port = 2222,
                    username = "u",
                    keyAlias = "k",
                    keyAlgorithm = "Ed25519",
                    keyPassphrase = "pp-enc",
                    password = "enc:v1:stored-pass",
                    hostKeyFingerprint = "SHA256:abc",
                    dnsMode = DnsMode.LOCAL,
                    remoteDnsServer = "1.1.1.1",
                    allowedPackages = "[\"com.a\"]",
                    excludedRoutes = "10.0.0.0/8",
                    createdAt = Date(12345),
                )
            whenever(serverDao.getByIdBlocking(7)).thenReturn(stored)

            repository.saveServerEdit(7, config(id = 7, name = "new name", host = "b.com"), setAsDefault = false)

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).update(captor.capture())
            val saved = captor.firstValue
            // 可编辑字段来自 incoming
            assertEquals("new name", saved.name)
            assertEquals("b.com", saved.host)
            // 密码未提供 → 保留原密文（字节稳定）
            assertEquals("enc:v1:stored-pass", saved.password)
            // 不可编辑字段沿用既有值
            assertEquals("Ed25519", saved.keyAlgorithm)
            assertEquals("pp-enc", saved.keyPassphrase)
            assertEquals("SHA256:abc", saved.hostKeyFingerprint)
            assertEquals(DnsMode.LOCAL, saved.dnsMode)
            assertEquals("1.1.1.1", saved.remoteDnsServer)
            assertEquals("[\"com.a\"]", saved.allowedPackages)
            assertEquals("10.0.0.0/8", saved.excludedRoutes)
            assertEquals(Date(12345), saved.createdAt)
        }

    @Test
    fun `edit with new password encrypts it before update`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverDao.getByIdBlocking(7)).thenReturn(
                ServerEntity(id = 7, name = "x", host = "h", username = "u", keyAlias = "k", password = "enc:v1:old"),
            )

            repository.saveServerEdit(7, config(id = 7, password = "new-secret"), setAsDefault = false)

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).update(captor.capture())
            assertEquals("enc:v1:new-secret", captor.firstValue.password)
        }

    @Test
    fun `setAsDefault activates the saved server`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverDao.getByIdBlocking(7)).thenReturn(
                ServerEntity(id = 7, name = "x", host = "h", username = "u", keyAlias = "k"),
            )

            repository.saveServerEdit(7, config(id = 7), setAsDefault = true)

            verify(serverDao).setActive(7)
        }

    @Test
    fun `wizard path inserts new server without activating`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverDao.insert(any())).thenReturn(1L)

            repository.saveServerEdit(-1L, config(name = "wiz", host = "h.com"), setAsDefault = false)

            val captor = argumentCaptor<ServerEntity>()
            verify(serverDao).insert(captor.capture())
            assertEquals("wiz", captor.firstValue.name)
            verify(serverDao, never()).setActive(any())
        }
}
