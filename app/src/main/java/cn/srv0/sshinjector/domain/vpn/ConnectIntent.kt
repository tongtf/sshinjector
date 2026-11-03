package cn.srv0.sshinjector.domain.vpn

/**
 * 「点连接」该走哪条路。
 *
 * 背景: 旧实现在"已连接"时**静默 return** (只有 logcat 一行), 用户在服务器列表里点另一台
 * 服务器看到的是"什么都没发生", 而且该行状态被 UI 乐观置成 Connecting 后永远转圈。
 * 判定抽成纯函数, 便于单测锁死三条分支。
 */
enum class ConnectIntent {
    /** 没有会话在跑 → 正常建连。 */
    CONNECT,

    /** 已经连着同一台服务器 → 什么都不做 (只回一条 INFO, 不重连)。 */
    ALREADY_CONNECTED,

    /** 已连着另一台 (或当前服务器未知) → 拆旧建新。 */
    SWITCH,
}

/**
 * @param running [VpnController.isVpnRunning] (注意它在 Connecting 阶段就已为 true)
 * @param currentServerId 当前会话的服务器 id, null = 未知
 * @param targetServerId 用户点的那台
 */
fun connectIntent(
    running: Boolean,
    currentServerId: Long?,
    targetServerId: Long,
): ConnectIntent =
    when {
        !running -> ConnectIntent.CONNECT
        currentServerId == targetServerId -> ConnectIntent.ALREADY_CONNECTED
        else -> ConnectIntent.SWITCH
    }
