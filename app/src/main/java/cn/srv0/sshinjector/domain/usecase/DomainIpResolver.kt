package cn.srv0.sshinjector.domain.usecase

import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.RemoteDomainResolver
import cn.srv0.sshinjector.domain.vpn.RemoteResolveOutcome
import cn.srv0.sshinjector.domain.vpn.SshIoDispatcher
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 真实 IP 实际走的路径 —— UI 据此标注 `本地视角 (远端不可用)` (设计 §7.2)。 */
enum class ResolvePath {
    /** 开关关着 (或本来就是本地): 受保护 socket 直查。 */
    LOCAL,

    /** 开关打开且远端解析成功: 拿到的是 VPS 出口会连的 IP。 */
    REMOTE,

    /** 开关打开但远端不可用 (exec 被禁 / 池饱和 / 黑洞 / 无记录) → 已静默回退本地。 */
    LOCAL_FALLBACK,
}

data class DomainResolveResult(
    val ips: List<String>,
    val path: ResolvePath,
)

/**
 * 域名统计「解析真实 IP」的路径裁决 (设计 §7.2)。
 *
 * 两条腿:
 * - **本地视角 (默认)**: `DnsInterceptor.resolveLocally` (受保护 socket 直查, 零 SSH 依赖);
 * - **远端视角 (开关打开时)**: 池内会话 exec `getent ahosts` → VPS 出口实际会连的 IP。
 *
 * 纪律 (违反即回归):
 * ① 远端不可用 (Unavailable/Failed/池饱和/无记录) **绝不报错**, 一律静默回退本地视角 ——
 *    残留 WARNING 会让「故障归因」凭空多一类, 而用户点一次解析不该产生故障记录;
 * ② 挂起前先做 `isSaturated()` 预检: 数据面吃紧时让位, 不为一次展示动作再去挤 SSH 会话;
 * ③ **单飞**由调用方 (ViewModel) 保证 —— 这里不做排队, 也不做后台批量解析;
 * ④ 本地腿是阻塞调用, 必须离开调用线程 (`Dispatchers.IO`)。
 */
@Singleton
class DomainIpResolver
    @Inject
    constructor(
        private val dnsInterceptor: DnsInterceptor,
        private val remoteResolver: RemoteDomainResolver,
        private val sshIoDispatcher: SshIoDispatcher,
    ) {
        suspend fun resolve(
            domain: String,
            useRemote: Boolean,
        ): DomainResolveResult {
            if (useRemote) {
                val remote = tryRemote(domain)
                if (remote != null) return DomainResolveResult(remote, ResolvePath.REMOTE)
            }
            val ips = withContext(Dispatchers.IO) { dnsInterceptor.resolveLocally(domain) }
            return DomainResolveResult(
                ips = ips,
                path = if (useRemote) ResolvePath.LOCAL_FALLBACK else ResolvePath.LOCAL,
            )
        }

        /** 远端一次尝试; 返回 null = 不可用 (已记 INFO), 调用方回退本地。 */
        private suspend fun tryRemote(domain: String): List<String>? {
            if (sshIoDispatcher.isSaturated()) {
                logFallback(domain, "SSH IO 池饱和")
                return null
            }
            return when (val outcome = remoteResolver.resolveIps(domain)) {
                is RemoteResolveOutcome.Ok ->
                    if (outcome.ips.isEmpty()) {
                        logFallback(domain, "远端无记录")
                        null
                    } else {
                        // 自证点 (设计 §11): 远端解析耗时/条数/会话 —— 无 adb 时判读"远端腿到底走没走"。
                        VpnController.appLog(
                            "远端解析 · $domain — ${outcome.elapsedMs}ms · ${outcome.ips.size} 个 IP" +
                                " (${outcome.sessionId})",
                            LogLevel.INFO,
                        )
                        outcome.ips
                    }
                RemoteResolveOutcome.Unavailable -> {
                    logFallback(domain, "exec 不可用或会话池无活口")
                    null
                }
                RemoteResolveOutcome.Failed -> {
                    logFallback(domain, "远端无响应")
                    null
                }
            }
        }

        private fun logFallback(
            domain: String,
            reason: String,
        ) {
            VpnController.appLog("远端解析不可用 · $domain — 已回退本地视角 ($reason)", LogLevel.INFO)
        }
    }
