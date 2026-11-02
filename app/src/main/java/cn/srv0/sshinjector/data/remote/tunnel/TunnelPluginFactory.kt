package cn.srv0.sshinjector.data.remote.tunnel

import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.remote.ssh.JschSshClient
import cn.srv0.sshinjector.data.remote.ssh.KnownHostsManager
import cn.srv0.sshinjector.data.remote.ssh.SshKeyManager
import cn.srv0.sshinjector.domain.vpn.ConnectionBudgetHolder
import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.SshIoDispatcher
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隧道插件工厂: **每出口一个实例**。
 *
 * 为什么需要工厂 (而不是 DI 单例):
 * - 插件持有 `Socks5ProxyServer` (一个监听端口) 与 `JschSshClient` (一个 3 会话的池),
 *   这两者都是**每出口一份**的可变状态 —— 单例做不到多出口;
 * - 主出口也走工厂, 不存在"主出口专用类" (`PrimarySocks5Plugin` 是 v1 设计的虚构类)。
 *
 * `protocolId` 用于按协议分派 (P3 的外部 SOCKS5/HTTP 出口只要再绑一个工厂)。
 */
interface TunnelPluginFactory {
    val protocolId: String

    /**
     * @param exitId 出口身份 (`"default"` / 用户命名), 见 [TunnelPlugin.exitId]
     * @param label  展示名 (只进日志前缀); 主出口传空串
     */
    fun create(
        exitId: String,
        label: String,
    ): TunnelPlugin
}

@Singleton
class Socks5TunnelPluginFactory
    @Inject
    constructor(
        /** 主出口复用 Hilt 单例 (它的池/状态本来就是全局唯一的那个)。 */
        private val primaryClient: JschSshClient,
        private val keyManager: SshKeyManager,
        private val knownHosts: KnownHostsManager,
        private val serverDao: ServerDao,
        private val dnsInterceptor: DnsInterceptor,
        private val sshIoDispatcher: SshIoDispatcher,
        private val budgetHolder: ConnectionBudgetHolder,
    ) : TunnelPluginFactory {
        override val protocolId = "socks5"

        override fun create(
            exitId: String,
            label: String,
        ): TunnelPlugin {
            val client =
                if (exitId == DEFAULT_EXIT_ID) {
                    primaryClient
                } else {
                    // 附加出口必须有自己的池 (3 条会话), 否则出口之间没有任何隔离。
                    // `exitLabel` 让它的池失败日志降 WARNING 并指明出口 (C-5)。
                    JschSshClient(keyManager, knownHosts, serverDao).also { it.exitLabel = exitId }
                }
            return Socks5TunnelPlugin(
                client = client,
                dnsInterceptor = dnsInterceptor,
                sshIoDispatcher = sshIoDispatcher,
                budgetHolder = budgetHolder,
                exitId = exitId,
                label = label,
            )
        }

        private companion object {
            const val DEFAULT_EXIT_ID = "default"
        }
    }
