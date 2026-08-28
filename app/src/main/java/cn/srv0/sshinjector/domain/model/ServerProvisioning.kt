package cn.srv0.sshinjector.domain.model

import kotlinx.coroutines.flow.Flow

/**
 * 服务器端一键配置的状态机与结果模型。
 */
object ServerProvisioning {
    /**
     * 服务器端配置脚本的 SHA-256，硬编码用于防篡改校验。
     * 与 assets/ssh_setup_script.sh 逐字节一致时由构建期测试保证。
     */
    const val SETUP_SCRIPT_SHA256 =
        "0cd9fbb7e0c64a83821fdcb0210e5afbd88838f4a1b216b3f024fece0d433126"

    /** 服务器端临时脚本文件名（执行后删除） */
    const val REMOTE_SCRIPT_NAME = "ssh_setup_script.sh"

    /** 固定隧道账号 */
    const val TUNNEL_ACCOUNT = "sshproxy"

    /** 配置步骤（用于 UI 逐步展示） */
    enum class Step {
        DETECT_PRIVILEGE,
        UPLOAD_SCRIPT,
        UPLOAD_PUBKEY,
        EXECUTE_SCRIPT,
        VERIFY,
        DONE,
    }

    /** 步骤结果 */
    sealed class StepResult {
        data class Running(
            val step: Step,
        ) : StepResult()

        data class Succeeded(
            val step: Step,
            val detail: String? = null,
        ) : StepResult()
    }

    /** 最终结果 */
    sealed class Outcome {
        /** 完整成功：隧道账号已配置 */
        data class FullSuccess(
            val account: String,
        ) : Outcome()

        /** 无 root/sudo 权限，仅保存本机配置 */
        data class LocalOnly(
            val reason: String,
        ) : Outcome()

        /** 脚本完整性校验失败（可能被篡改） */
        data class TamperDetected(
            val expectedSha256: String,
            val actualSha256: String?,
        ) : Outcome()

        /** 失败 */
        data class Failed(
            val step: Step?,
            val message: String,
        ) : Outcome()
    }

    /** 配置过程事件：逐步进度 + 最终结果 */
    sealed class ProvisionEvent {
        data class StepStarted(
            val step: Step,
        ) : ProvisionEvent()

        data class StepCompleted(
            val step: Step,
            val detail: String? = null,
        ) : ProvisionEvent()

        data class Finished(
            val outcome: Outcome,
        ) : ProvisionEvent()
    }
}

/** 登录凭证（仅用于本次远程配置，不入库） */
data class LoginCredential(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
)

/**
 * 一键配置执行器接口（由 ServerProvisioner 实现），
 * 隔离出以便 ViewModel 层可注入/测试。
 */
interface ServerProvisionerContract {
    /** 逐步发出 [ServerProvisioning.ProvisionEvent]，最终为 Finished(outcome) */
    fun provision(
        login: LoginCredential,
        publicKey: String,
    ): Flow<ServerProvisioning.ProvisionEvent>
}
