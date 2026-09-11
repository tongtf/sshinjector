package cn.srv0.sshinjector.ui.screen.server

import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.data.remote.ssh.KnownHostsManager
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * ServerEditViewModel 现为薄委托层：save/load/delete 均转调 ServerRepository，入参为 domain 层
 * [ServerConfig]（UI 不再接触 Entity）。凭据加密与字段保留的行为契约见
 * [cn.srv0.sshinjector.domain.usecase.ServerRepositoryTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerEditViewModelTest {
    private lateinit var serverRepository: ServerRepository
    private lateinit var viewModel: ServerEditViewModel

    @Before
    fun setUp() {
        // ViewModel init 会在构造时 launch (collect 全局设置流), 需先初始化 Main dispatcher
        Dispatchers.setMain(StandardTestDispatcher())
        serverRepository = mock()
        val settings = mock<SettingsDataStore>()
        doReturn(emptyFlow<Int?>()).`when`(settings).mtu
        doReturn(emptyFlow<Int?>()).`when`(settings).keepAlive
        doReturn(emptyFlow<Boolean?>()).`when`(settings).enableIPv6
        viewModel = ServerEditViewModel(serverRepository, mock<SshKeyManager>(), mock<KnownHostsManager>(), settings)
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
    fun `save delegates to repository and fires onDone`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(serverRepository.saveServerEdit(any(), any(), any())).thenReturn(1L)
            var done = false
            viewModel.save(-1L, config(password = "secret"), { done = true }, setAsDefault = true)
            testScheduler.advanceUntilIdle()

            val captor = argumentCaptor<ServerConfig>()
            verify(serverRepository).saveServerEdit(eq(-1L), captor.capture(), eq(true))
            assertEquals("secret", captor.firstValue.password)
            assertTrue(done)
        }

    @Test
    fun `load emits config from repository`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val cfg = ServerConfig(id = 7, name = "old", host = "a.com", username = "u", keyAlias = "k")
            whenever(serverRepository.getServerById(7)).thenReturn(cfg)

            var loaded: ServerConfig? = null
            viewModel.load(7) { loaded = it }
            testScheduler.advanceUntilIdle()

            verify(serverRepository).getServerById(7)
            assertTrue(loaded?.host == "a.com")
        }

    @Test
    fun `delete reactivates next server when deleting active one`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val active = ServerConfig(id = 7, name = "a", host = "h", username = "u", keyAlias = "k", isActive = true)
            whenever(serverRepository.getServerById(7)).thenReturn(active)
            whenever(serverRepository.getAllServers()).thenReturn(
                listOf(ServerConfig(id = 8, name = "b", host = "h2", username = "u", keyAlias = "k")),
            )
            whenever(serverRepository.deleteServer(any())).thenReturn(0)
            whenever(serverRepository.setActiveServer(any())).thenReturn(0)

            var done = false
            viewModel.delete(7) { done = true }
            testScheduler.advanceUntilIdle()

            verify(serverRepository).deleteServer(7)
            verify(serverRepository).setActiveServer(8)
            assertTrue(done)
        }
}
