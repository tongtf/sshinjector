package cn.srv0.sshinjector.data.remote.config

import android.content.Context
import android.util.Log
import cn.srv0.sshinjector.data.remote.ssh.ExecResult
import cn.srv0.sshinjector.data.remote.ssh.RemoteCommandExecutor
import cn.srv0.sshinjector.data.remote.ssh.SshConnectionTarget
import cn.srv0.sshinjector.domain.model.LoginCredential
import cn.srv0.sshinjector.domain.model.ServerProvisionerContract
import cn.srv0.sshinjector.domain.model.ServerProvisioning
import cn.srv0.sshinjector.domain.model.ServerProvisioning.Step
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务器端一键配置编排器。
 *
 * 安全设计：
 *  - 脚本内容硬编码于 assets，SHA-256 硬编码于代码；
 *    执行前上传服务器并本地校验 sha256sum，防止传输/落盘被篡改。
 *  - 公钥经 stdin 传递，不做 shell 参数拼接，杜绝注入。
 *  - sshd 修改前备份，仅当 `sshd -t` 通过且配置 owner/permission 正确时才 reload。
 *  - sudo 密码仅经 stdin、-p '' 静默传输，不落日志。
 */
@Singleton
class ServerProvisioner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val commandExecutor: RemoteCommandExecutor,
    ) : ServerProvisionerContract {
        private val tag = "ServerProvisioner"

        /** 脚本加载器；测试可替换为文件读取（避免依赖 Android assets） */
        @androidx.annotation.VisibleForTesting
        internal var scriptLoader: () -> String = {
            val input = context.assets.open("ssh_setup_script.sh")
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        /** 从 assets 读取配置脚本（运行时事实来源；哈希在构建期测试中校验一致） */
        private fun loadScript(): String = scriptLoader()

        override fun provision(
            login: LoginCredential,
            publicKey: String,
        ): Flow<ServerProvisioning.ProvisionEvent> =
            callbackFlow {
                val job =
                    launch(Dispatchers.IO) {
                        try {
                            val outcome = runProvision(login, publicKey, ::trySend)
                            trySend(ServerProvisioning.ProvisionEvent.Finished(outcome))
                        } catch (e: Exception) {
                            Log.w(tag, "provision failed", e)
                            trySend(
                                ServerProvisioning.ProvisionEvent.Finished(
                                    ServerProvisioning.Outcome.Failed(
                                        step = null,
                                        message = e.message ?: "provision failed",
                                    ),
                                ),
                            )
                        } finally {
                            close()
                        }
                    }
                awaitClose {
                    job.cancel()
                }
            }

        private suspend fun runProvision(
            login: LoginCredential,
            publicKey: String,
            emit: (ServerProvisioning.ProvisionEvent) -> Unit,
        ): ServerProvisioning.Outcome {
            // ---- Step 1: 权限检测（先 root 后 sudo）----
            step(emit, Step.DETECT_PRIVILEGE)
            val sudoPrefix =
                detectSudoPrefix(login)
                    ?: return ServerProvisioning.Outcome.LocalOnly("账户无 root/sudo 权限")

            // ---- Step 2: 上传脚本 + 校验 SHA-256 ----
            step(emit, Step.UPLOAD_SCRIPT)
            val script = loadScript()
            val scriptHash = sha256(script.toByteArray(Charsets.UTF_8))
            if (scriptHash != ServerProvisioning.SETUP_SCRIPT_SHA256) {
                Log.e(tag, "local script hash mismatch: $scriptHash")
                return ServerProvisioning.Outcome.Failed(
                    Step.UPLOAD_SCRIPT,
                    "本机脚本哈希校验失败（构建期漂移）",
                )
            }
            val uploaded = uploadAndVerifyScript(login, sudoPrefix, script, scriptHash)
            val scriptPath = uploaded.scriptPath ?: return uploaded.outcome

            // ---- Step 3-5: 上传公钥 + 执行脚本 + 验证 ----
            val executeError =
                uploadAndRun(login, sudoPrefix, publicKey, scriptPath, emit)
            if (executeError != null) return executeError

            // ---- Done ----
            step(emit, Step.DONE)
            return ServerProvisioning.Outcome.FullSuccess(ServerProvisioning.TUNNEL_ACCOUNT)
        }

        /** 上传公钥、执行脚本、验证配置；成功返回 null，失败返回对应 Outcome */
        private suspend fun uploadAndRun(
            login: LoginCredential,
            sudoPrefix: String,
            publicKey: String,
            scriptPath: String,
            emit: (ServerProvisioning.ProvisionEvent) -> Unit,
        ): ServerProvisioning.Outcome? {
            step(emit, Step.UPLOAD_PUBKEY)
            if (!isValidPublicKey(publicKey)) {
                exec(login, sudoPrefix, "rm -f $scriptPath")
                return ServerProvisioning.Outcome.Failed(Step.UPLOAD_PUBKEY, "公钥格式非法")
            }
            val pubKeyPath = "/tmp/sshinjector_pubkey_$$.pub"
            exec(login, sudoPrefix, "cat > $pubKeyPath", publicKey.toByteArray(Charsets.UTF_8))

            step(emit, Step.EXECUTE_SCRIPT)
            val run = exec(login, sudoPrefix, "sh $scriptPath $pubKeyPath")
            if (run.exitCode != 0) {
                Log.e(tag, "script failed: ${run.stderr}")
                exec(login, sudoPrefix, "rm -f $scriptPath $pubKeyPath")
                return ServerProvisioning.Outcome.Failed(
                    Step.EXECUTE_SCRIPT,
                    run.stderr.ifBlank { run.stdout }.ifBlank { "exit ${run.exitCode}" },
                )
            }

            step(emit, Step.VERIFY)
            val verify =
                exec(
                    login,
                    sudoPrefix,
                    "id ${ServerProvisioning.TUNNEL_ACCOUNT} && (" +
                        "grep -c 'Match User ${ServerProvisioning.TUNNEL_ACCOUNT}' " +
                        "/etc/ssh/sshd_config || " +
                        "test -f /etc/dropbear/sshinjector.configured)",
                )
            if (verify.exitCode != 0) {
                exec(login, sudoPrefix, "rm -f $scriptPath $pubKeyPath")
                return ServerProvisioning.Outcome.Failed(Step.VERIFY, "配置验证失败: ${verify.stderr}")
            }
            exec(login, sudoPrefix, "rm -f $scriptPath $pubKeyPath")
            return null
        }

        /** 脚本上传结果：scriptPath 非空表示成功；否则 outcome 为失败原因 */
        private data class ScriptUpload(
            val scriptPath: String?,
            val outcome: ServerProvisioning.Outcome,
        )

        /** 上传脚本到服务器并在远端校验 SHA-256 */
        private suspend fun uploadAndVerifyScript(
            login: LoginCredential,
            sudoPrefix: String,
            script: String,
            scriptHash: String,
        ): ScriptUpload {
            val scriptPath = "/tmp/sshinjector_setup_$$.sh"
            val writeScriptCmd = "cat > $scriptPath && chmod 700 $scriptPath && sha256sum $scriptPath"
            val upload = exec(login, sudoPrefix, writeScriptCmd, script.toByteArray(Charsets.UTF_8))
            val remoteHash = extractSha256(upload.stdout)
            if (remoteHash != scriptHash) {
                Log.e(tag, "remote script hash mismatch: remote=$remoteHash expected=$scriptHash")
                exec(login, sudoPrefix, "rm -f $scriptPath")
                return ScriptUpload(null, ServerProvisioning.Outcome.TamperDetected(scriptHash, remoteHash))
            }
            return ScriptUpload(scriptPath, ServerProvisioning.Outcome.Failed(Step.UPLOAD_SCRIPT, ""))
        }

        private fun step(
            emit: (ServerProvisioning.ProvisionEvent) -> Unit,
            step: Step,
        ) {
            emit(ServerProvisioning.ProvisionEvent.StepStarted(step))
        }

        private suspend fun detectSudoPrefix(login: LoginCredential): String? {
            // root 直接执行
            val id = exec(login, null, "id -u")
            if (id.exitCode == 0 && id.stdout.trim() == "0") {
                return ""
            }
            // sudo 免密
            val sudoNopass = exec(login, null, "sudo -n -p '' true")
            if (sudoNopass.exitCode == 0) {
                return "sudo -n"
            }
            // sudo 需要密码（经 stdin 传入）
            if (!login.password.isNullOrEmpty()) {
                val sudoPass = exec(login, null, "$SUDO_WITH_PASSWORD true")
                if (sudoPass.exitCode == 0) {
                    return SUDO_WITH_PASSWORD
                }
            }
            return null
        }

        private suspend fun exec(
            login: LoginCredential,
            sudoPrefix: String?,
            command: String,
            stdin: ByteArray? = null,
        ): ExecResult {
            val effective =
                if (sudoPrefix.isNullOrEmpty()) {
                    command
                } else {
                    "$sudoPrefix $command"
                }
            // sudo -S 需要从 stdin 首行读密码；将密码置于实际数据之前，避免 sudo 误读数据。
            val effectiveStdin =
                if (sudoPrefix == SUDO_WITH_PASSWORD) {
                    val pass = login.password.orEmpty().toByteArray(Charsets.UTF_8)
                    val nl = byteArrayOf('\n'.code.toByte())
                    val prefix = ByteArray(pass.size + nl.size)
                    System.arraycopy(pass, 0, prefix, 0, pass.size)
                    System.arraycopy(nl, 0, prefix, pass.size, nl.size)
                    if (stdin != null) {
                        prefix + stdin
                    } else {
                        prefix
                    }
                } else {
                    stdin
                }
            return commandExecutor.execSingleShot(
                target =
                    SshConnectionTarget(
                        host = login.host,
                        port = login.port,
                        username = login.username,
                        password = login.password,
                        keyAlias = null,
                    ),
                stdinData = effectiveStdin,
                command = effective,
            )
        }

        private companion object {
            const val SUDO_WITH_PASSWORD = "sudo -S -p ''"
        }
    }
