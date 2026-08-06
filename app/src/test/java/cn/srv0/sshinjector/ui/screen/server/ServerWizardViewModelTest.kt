package cn.srv0.sshinjector.ui.screen.server

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.model.LoginCredential
import cn.srv0.sshinjector.domain.model.ServerProvisionerContract
import cn.srv0.sshinjector.domain.model.ServerProvisioning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * ServerWizardViewModel 向导状态机与保存逻辑单元测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerWizardViewModelTest {
    private lateinit var serverDao: ServerDao
    private lateinit var keyManager: SshKeyManager
    private lateinit var provisioner: ServerProvisionerContract
    private lateinit var viewModel: ServerWizardViewModel

    @Before
    fun setUp() {
        serverDao = mock()
        keyManager = mock()
        provisioner = mock()
        viewModel = ServerWizardViewModel(serverDao, keyManager, provisioner)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `server info step validates and advances to login`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            viewModel.setServerName("my vps")
            viewModel.setHost("example.com")
            viewModel.setPort("22")

            val invalidMessages = mutableListOf<Int>()
            val ok = viewModel.submitServerInfo { invalidMessages += it }

            assertTrue(ok)
            assertTrue(invalidMessages.isEmpty())
            assertEquals(WizardStep.LOGIN_CREDENTIALS, viewModel.currentStep.value)
        }

    @Test
    fun `server info step rejects blank fields`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            viewModel.setHost("example.com")
            viewModel.setPort("22")

            val invalidMessages = mutableListOf<Int>()
            val ok = viewModel.submitServerInfo { invalidMessages += it }

            assertFalse(ok)
            assertTrue(invalidMessages.isNotEmpty())
            assertEquals(WizardStep.SERVER_INFO, viewModel.currentStep.value)
        }

    @Test
    fun `server info step rejects invalid port`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            viewModel.setServerName("vps")
            viewModel.setHost("example.com")
            viewModel.setPort("99999")

            val invalidMessages = mutableListOf<Int>()
            val ok = viewModel.submitServerInfo { invalidMessages += it }

            assertFalse(ok)
            assertTrue(invalidMessages.isNotEmpty())
        }

    @Test
    fun `full success path saves server with tunnel account`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(keyManager.generateKeyPair(any(), any(), any())).thenReturn("pubkey")
            whenever(keyManager.getPublicKey(any())).thenReturn("ssh-ed25519 AAAA pubkey")
            whenever(
                provisioner.provision(any<LoginCredential>(), any()),
            ).thenReturn(
                flowOf(
                    ServerProvisioning.ProvisionEvent.StepStarted(ServerProvisioning.Step.DETECT_PRIVILEGE),
                    ServerProvisioning.ProvisionEvent.Finished(
                        ServerProvisioning.Outcome.FullSuccess("sshproxy"),
                    ),
                ),
            )
            whenever(serverDao.insert(any())).thenReturn(1L)

            viewModel.setServerName("my vps")
            viewModel.setHost("example.com")
            viewModel.setPort("22")
            viewModel.submitServerInfo {}
            viewModel.setLoginUsername("root")
            viewModel.setLoginPassword("secret")
            viewModel.submitCredentials {}
            testScheduler.advanceUntilIdle()

            assertEquals(WizardStep.RESULT, viewModel.currentStep.value)
            val result = viewModel.result.value
            assertTrue(result is WizardResult.Success)
            assertEquals("sshproxy", (result as WizardResult.Success).account)

            viewModel.save {}
            testScheduler.advanceUntilIdle()
            val captor = argumentCaptor<cn.srv0.sshinjector.data.local.entity.ServerEntity>()
            verify(serverDao).insert(captor.capture())
            val entity = captor.firstValue
            assertEquals("sshproxy", entity.username)
            assertEquals("example.com", entity.host)
            assertEquals(22, entity.port)
            assertTrue(viewModel.saved.value)
        }

    @Test
    fun `local only outcome still saves with tunnel account and generated key`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(keyManager.generateKeyPair(any(), any(), any())).thenReturn("pubkey")
            whenever(keyManager.getPublicKey(any())).thenReturn("ssh-ed25519 AAAA pubkey")
            whenever(
                provisioner.provision(any<LoginCredential>(), any()),
            ).thenReturn(
                flowOf(
                    ServerProvisioning.ProvisionEvent.Finished(
                        ServerProvisioning.Outcome.LocalOnly("no root"),
                    ),
                ),
            )
            whenever(serverDao.insert(any())).thenReturn(1L)

            viewModel.setServerName("vps")
            viewModel.setHost("example.com")
            viewModel.setPort("22")
            viewModel.submitServerInfo {}
            viewModel.setLoginUsername("user")
            viewModel.submitCredentials {}
            testScheduler.advanceUntilIdle()

            assertEquals(WizardStep.RESULT, viewModel.currentStep.value)
            assertTrue(viewModel.result.value is WizardResult.LocalOnly)

            viewModel.save {}
            testScheduler.advanceUntilIdle()
            val captor = argumentCaptor<cn.srv0.sshinjector.data.local.entity.ServerEntity>()
            verify(serverDao).insert(captor.capture())
            assertEquals("sshproxy", captor.firstValue.username)
        }

    @Test
    fun `tamper detected leads to tampered result and no save`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(keyManager.generateKeyPair(any(), any(), any())).thenReturn("pubkey")
            whenever(keyManager.getPublicKey(any())).thenReturn("ssh-ed25519 AAAA pubkey")
            whenever(
                provisioner.provision(any<LoginCredential>(), any()),
            ).thenReturn(
                flowOf(
                    ServerProvisioning.ProvisionEvent.Finished(
                        ServerProvisioning.Outcome.TamperDetected("expected", "actual"),
                    ),
                ),
            )

            viewModel.setServerName("vps")
            viewModel.setHost("example.com")
            viewModel.setPort("22")
            viewModel.submitServerInfo {}
            viewModel.setLoginUsername("user")
            viewModel.submitCredentials {}
            testScheduler.advanceUntilIdle()

            assertEquals(WizardStep.RESULT, viewModel.currentStep.value)
            assertTrue(viewModel.result.value is WizardResult.Tampered)
        }

    @Test
    fun `failed outcome shows failed result`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            whenever(keyManager.generateKeyPair(any(), any(), any())).thenReturn("pubkey")
            whenever(keyManager.getPublicKey(any())).thenReturn("ssh-ed25519 AAAA pubkey")
            whenever(
                provisioner.provision(any<LoginCredential>(), any()),
            ).thenReturn(
                flowOf(
                    ServerProvisioning.ProvisionEvent.Finished(
                        ServerProvisioning.Outcome.Failed(ServerProvisioning.Step.EXECUTE_SCRIPT, "boom"),
                    ),
                ),
            )

            viewModel.setServerName("vps")
            viewModel.setHost("example.com")
            viewModel.setPort("22")
            viewModel.submitServerInfo {}
            viewModel.setLoginUsername("user")
            viewModel.submitCredentials {}
            testScheduler.advanceUntilIdle()

            assertEquals(WizardStep.RESULT, viewModel.currentStep.value)
            assertTrue(viewModel.result.value is WizardResult.Failed)
        }
}
