package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.AppUidResolver
import java.net.InetAddress

/**
 * 一次路由决策的全部输入 (设计 §6.3)。
 *
 * **只在 SYN 决策一次**: uid / 包名 / 域名 / IP / 端口 在这一刻取齐, 结果写进
 * `TcpConnection.exitId`, 后续不再重算 (唯一例外是 §9.5 的"建连前快速失败重试一次")。
 *
 * 字段可空语义 (缺哪个就只跳过依赖它的规则, 绝不因此改变"隧道/直连"判定):
 * - [pkg] null = uid 未解析/无归属 → `app:` 规则不参与匹配;
 * - [domain] null = 假 IP 反查不到 → 域名规则不参与匹配;
 * - [ip] 必须取 `conn.dstIp` (不是 `ParsedPacket.dstIp`) —— `select` 跑在
 *   `forwardSynToTunnel` 协程里, 此时 TUN 读缓冲可能已被下一帧覆盖 (AGENTS 的
 *   "惰性地址同帧"不变量), 越帧读取会让快照的 `地址惰性失效` 增长。
 */
data class RouteTarget(
    val uid: Int = AppUidResolver.NO_UID,
    val pkg: String? = null,
    val domain: String? = null,
    val ip: InetAddress,
    val port: Int,
)
