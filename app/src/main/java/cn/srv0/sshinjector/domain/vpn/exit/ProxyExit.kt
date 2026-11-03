package cn.srv0.sshinjector.domain.vpn.exit

/**
 * 附加出口的领域模型 (设计 §7.1)。UI 与规则层只认这个, 不直接碰 [cn.srv0.sshinjector.data.local.entity.ProxyExitEntity]
 * (与 `ServerConfig` / `ServerEntity` 同一分层纪律)。
 *
 * 只带**引用** ([serverId]) 不带 host/port —— 服务器详情永远从 `ServerRepository` 读, 避免两处地址分叉。
 * [name] 已规范化 (小写), 与规则文本里的引用一一对应。
 */
data class ProxyExit(
    val id: Long,
    val name: String,
    val serverId: Long,
    val enabled: Boolean,
    val priority: Int,
)
