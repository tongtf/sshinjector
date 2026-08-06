package cn.srv0.sshinjector.data.remote.config

import cn.srv0.sshinjector.data.remote.ssh.ExecResult
import cn.srv0.sshinjector.data.remote.ssh.RemoteCommandExecutor
import cn.srv0.sshinjector.domain.model.LoginCredential
import cn.srv0.sshinjector.domain.model.ServerProvisioning
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.mock
import org.mockito.stubbing.Answer
import java.io.File

/**
 * ServerProvisioner 编排逻辑单元测试。
 *
 * 注意：mockito-kotlin 对带默认参数的 suspend 函数 + any() 匹配存在已知问题，
 * 因此这里用 defaultAnswer 按命令字符串路由，而非 whenever(...).thenReturn。
 */
class ServerProvisionerTest {
    private lateinit var provisioner: ServerProvisioner

    private val login = LoginCredential(host = "example.com", port = 22, username = "root", password = "secret")
    private val validPubKey =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG2K1rQwC0kQhVtBzQyXb3b1b3b1b3b1b3b1b3b1b3b1b3b1b comment"

    private val scriptContent: String by lazy {
        val candidates =
            listOf(
                File("src/main/assets/ssh_setup_script.sh"),
                File("app/src/main/assets/ssh_setup_script.sh"),
            )
        candidates.firstOrNull { it.exists() }?.readText(Charsets.UTF_8) ?: ""
    }

    /** 构造一个按命令字符串返回结果的 mock（defaultAnswer 方式，规避 suspend+any() 坑） */
    private fun mockExecutor(handler: (command: String, stdin: ByteArray?) -> ExecResult): RemoteCommandExecutor {
        val answer: Answer<Any> =
            Answer { invocation: InvocationOnMock ->
                val cmd = invocation.getArgument<String>(2)
                val stdin = invocation.getArgument<ByteArray?>(1)
                handler(cmd, stdin)
            }
        return mock(defaultAnswer = answer)
    }

    @Before
    fun setUp() {
        // provisioner 在具体测试中通过 mockExecutor 注入
    }

    @Test
    fun `full success path with root privilege`() =
        runBlocking {
            val executor =
                mockExecutor { cmd, stdin ->
                    when {
                        cmd == "id -u" -> ExecResult(0, "0\n", "")
                        cmd.contains("sha256sum") && cmd.contains("cat > /tmp/sshinjector_setup_") ->
                            ExecResult(0, "${sha256(scriptContent)}  /tmp/sshinjector_setup_1.sh\n", "")
                        cmd.startsWith("sh /tmp/sshinjector_setup_") &&
                            cmd.endsWith(".pub") ->
                            ExecResult(0, "complete\n", "")
                        cmd.contains("id sshproxy") || cmd.contains("Match User") -> ExecResult(0, "1\n", "")
                        cmd.startsWith("rm -f") -> ExecResult(0, "", "")
                        cmd.startsWith("cat > /tmp/sshinjector_pubkey") -> ExecResult(0, "", "")
                        else -> ExecResult(0, "", "")
                    }
                }
            provisioner = ServerProvisioner(Mockito.mock(android.content.Context::class.java), executor)
            provisioner.scriptLoader = { scriptContent }

            val events = provisioner.provision(login, validPubKey).toList()
            val finished = events.filterIsInstance<ServerProvisioning.ProvisionEvent.Finished>().last()
            assertTrue(
                "expected FullSuccess, got $finished",
                finished.outcome is ServerProvisioning.Outcome.FullSuccess,
            )
            assertEquals("sshproxy", (finished.outcome as ServerProvisioning.Outcome.FullSuccess).account)
        }

    @Test
    fun `tampered script detected remotely`() =
        runBlocking {
            val executor =
                mockExecutor { cmd, _ ->
                    when {
                        cmd == "id -u" -> ExecResult(0, "0\n", "")
                        cmd.contains("sha256sum") && cmd.contains("cat > /tmp/sshinjector_setup_") ->
                            ExecResult(0, "${"0".repeat(64)}  /tmp/sshinjector_setup_1.sh\n", "")
                        cmd.startsWith("rm -f") -> ExecResult(0, "", "")
                        else -> ExecResult(0, "", "")
                    }
                }
            provisioner = ServerProvisioner(Mockito.mock(android.content.Context::class.java), executor)
            provisioner.scriptLoader = { scriptContent }

            val events = provisioner.provision(login, validPubKey).toList()
            val finished = events.filterIsInstance<ServerProvisioning.ProvisionEvent.Finished>().last()
            assertTrue(
                "expected TamperDetected, got $finished",
                finished.outcome is ServerProvisioning.Outcome.TamperDetected,
            )
        }

    @Test
    fun `no root or sudo leads to LocalOnly`() =
        runBlocking {
            val executor =
                mockExecutor { cmd, _ ->
                    when {
                        cmd == "id -u" -> ExecResult(1, "", "permission denied")
                        cmd == "sudo -n -p '' true" -> ExecResult(1, "", "no sudo")
                        cmd == "sudo -S -p '' true" -> ExecResult(1, "", "bad password")
                        else -> ExecResult(0, "", "")
                    }
                }
            provisioner = ServerProvisioner(Mockito.mock(android.content.Context::class.java), executor)
            provisioner.scriptLoader = { scriptContent }

            val events = provisioner.provision(login, validPubKey).toList()
            val finished = events.filterIsInstance<ServerProvisioning.ProvisionEvent.Finished>().last()
            assertTrue(
                "expected LocalOnly (no root/sudo), got $finished",
                finished.outcome is ServerProvisioning.Outcome.LocalOnly,
            )
        }

    @Test
    fun `invalid public key rejected before upload`() =
        runBlocking {
            val executor =
                mockExecutor { cmd, _ ->
                    when {
                        cmd == "id -u" -> ExecResult(0, "0\n", "")
                        cmd.contains("sha256sum") && cmd.contains("cat > /tmp/sshinjector_setup_") ->
                            ExecResult(0, "${sha256(scriptContent)}  /tmp/sshinjector_setup_1.sh\n", "")
                        cmd.startsWith("rm -f") -> ExecResult(0, "", "")
                        else -> ExecResult(0, "", "")
                    }
                }
            provisioner = ServerProvisioner(Mockito.mock(android.content.Context::class.java), executor)
            provisioner.scriptLoader = { scriptContent }

            val events = provisioner.provision(login, "rm -rf / # injection").toList()
            val finished = events.filterIsInstance<ServerProvisioning.ProvisionEvent.Finished>().last()
            assertTrue(
                "expected Failed(invalid pubkey), got $finished",
                finished.outcome is ServerProvisioning.Outcome.Failed,
            )
            assertEquals(
                ServerProvisioning.Step.UPLOAD_PUBKEY,
                (finished.outcome as ServerProvisioning.Outcome.Failed).step,
            )
        }

    @Test
    fun `verify failure reported`() =
        runBlocking {
            val executor =
                mockExecutor { cmd, _ ->
                    when {
                        cmd == "id -u" -> ExecResult(0, "0\n", "")
                        cmd.contains("sha256sum") && cmd.contains("cat > /tmp/sshinjector_setup_") ->
                            ExecResult(0, "${sha256(scriptContent)}  /tmp/sshinjector_setup_1.sh\n", "")
                        cmd.startsWith("sh /tmp/sshinjector_setup_") &&
                            cmd.endsWith(".pub") ->
                            ExecResult(0, "complete\n", "")
                        cmd.contains("id sshproxy") -> ExecResult(1, "", "account missing")
                        cmd.startsWith("rm -f") -> ExecResult(0, "", "")
                        cmd.startsWith("cat > /tmp/sshinjector_pubkey") -> ExecResult(0, "", "")
                        else -> ExecResult(0, "", "")
                    }
                }
            provisioner = ServerProvisioner(Mockito.mock(android.content.Context::class.java), executor)
            provisioner.scriptLoader = { scriptContent }

            val events = provisioner.provision(login, validPubKey).toList()
            val finished = events.filterIsInstance<ServerProvisioning.ProvisionEvent.Finished>().last()
            assertTrue(
                "expected Failed(verify), got $finished",
                finished.outcome is ServerProvisioning.Outcome.Failed,
            )
        }

    @Test
    fun `password never appears in command arguments`() =
        runBlocking {
            val seenCommands = mutableListOf<String>()
            val executor =
                mockExecutor { cmd, _ ->
                    seenCommands += cmd
                    when {
                        cmd == "id -u" -> ExecResult(0, "0\n", "")
                        cmd.contains("sha256sum") && cmd.contains("cat > /tmp/sshinjector_setup_") ->
                            ExecResult(0, "${sha256(scriptContent)}  /tmp/sshinjector_setup_1.sh\n", "")
                        cmd.startsWith("sh /tmp/sshinjector_setup_") &&
                            cmd.endsWith(".pub") ->
                            ExecResult(0, "complete\n", "")
                        cmd.contains("id sshproxy") || cmd.contains("Match User") -> ExecResult(0, "1\n", "")
                        cmd.startsWith("rm -f") -> ExecResult(0, "", "")
                        cmd.startsWith("cat > /tmp/sshinjector_pubkey") -> ExecResult(0, "", "")
                        else -> ExecResult(0, "", "")
                    }
                }
            provisioner = ServerProvisioner(Mockito.mock(android.content.Context::class.java), executor)
            provisioner.scriptLoader = { scriptContent }

            provisioner.provision(login, validPubKey).toList()
            seenCommands.forEach { cmd ->
                assertTrue(
                    "password must never appear in command args: $cmd",
                    !cmd.contains("secret"),
                )
            }
        }

    private fun sha256(data: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
