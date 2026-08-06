package cn.srv0.sshinjector.data.remote.ssh

/** SSH 连接目标（host/port/认证信息），供一次性命令执行使用 */
data class SshConnectionTarget(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val keyAlias: String?,
)

/**
 * 远程命令执行抽象。由 JschSshClient 实现，
 * ServerProvisioner 依赖此接口以便测试替换。
 */
interface RemoteCommandExecutor {
    suspend fun execSingleShot(
        target: SshConnectionTarget,
        stdinData: ByteArray? = null,
        command: String,
        timeoutMs: Int = 60000,
    ): ExecResult
}
