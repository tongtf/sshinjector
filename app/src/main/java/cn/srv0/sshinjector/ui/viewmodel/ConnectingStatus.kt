package cn.srv0.sshinjector.ui.viewmodel

/**
 * 收回"乐观 Connecting"状态 (拒绝/取消 VPN 授权时)。
 *
 * `MainViewModel.connect` 只有在真正要去连接时才置 Connecting; 但授权对话框期间用户可能
 * 取消/拒绝 —— 那时不会再有 vpnState 事件把该行改回来, 这一行会永久转圈。
 */
internal fun clearConnectingStatus(
    status: Map<Long, String?>,
    serverId: Long?,
): Map<Long, String?> = if (serverId == null) status else status - serverId
