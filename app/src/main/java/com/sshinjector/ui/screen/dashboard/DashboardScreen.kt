package com.sshinjector.ui.screen.dashboard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.ui.screen.keymanager.KeyKindIcon
import com.sshinjector.ui.screen.keymanager.KeyManagerViewModel
import com.sshinjector.ui.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToServerAdd: () -> Unit,
    onNavigateToServerEdit: (Long) -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToKeys: () -> Unit,
    onNavigateToKeyAdd: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel(),
    keyViewModel: KeyManagerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            state.pendingConnectServerId?.let { serverId ->
                viewModel.onVpnPermissionGranted(serverId)
            }
        }
    }

    LaunchedEffect(state.vpnPermissionIntent) {
        state.vpnPermissionIntent?.let { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    val context = LocalContext.current

    LaunchedEffect(Unit) { keyViewModel.refresh() }

    var columnMode by remember { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf<Long?>(null) }

    val serverConnectionStatus = state.serverConnectionStatus
    val servers by viewModel.allServers.collectAsState(initial = emptyList())

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 网络信息
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("网络信息", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("IPv4", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.deviceIpv4, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("IPv6", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.deviceIpv6, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("DNS", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val (dnsBg, dnsFg) = when (state.dnsMode) {
                            "远程代理" -> Color(0xFF7C4DFF) to Color.White
                            "本地直连" -> Color(0xFF9E9E9E) to Color.White
                            "白名单模式" -> Color(0xFF00BCD4) to Color.White
                            "域名分流" -> Color(0xFFFF9800) to Color.White
                            else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = dnsBg,
                            modifier = Modifier.clickable { viewModel.switchDnsMode() }
                        ) {
                            Text(
                                text = state.dnsMode,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = dnsFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("代理", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.proxyAddress, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (state.isConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("连接", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${state.currentServerUser}@${state.currentServerHost}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("运行时间", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(state.connectionDuration, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 服务器 / 密钥 双列卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "服务器",
                                fontSize = 16.sp,
                                fontWeight = if (!columnMode) FontWeight.Bold else FontWeight.Normal,
                                color = if (!columnMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { columnMode = false }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "密钥",
                                fontSize = 16.sp,
                                fontWeight = if (columnMode) FontWeight.Bold else FontWeight.Normal,
                                color = if (columnMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { columnMode = true }
                            )
                        }
                        IconButton(
                            onClick = {
                                if (columnMode) onNavigateToKeyAdd() else onNavigateToServerAdd()
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (!columnMode) {
                        if (servers.isEmpty()) {
                            // 空状态设计
                            EmptyState(
                                icon = Icons.Default.FavoriteBorder,
                                title = "暂无服务器",
                                subtitle = "添加服务器以开始使用 VPN 代理",
                                actionText = "添加服务器",
                                onAction = onNavigateToServerAdd
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(servers, key = { it.id }) { server ->
                                    val isCurrent = state.currentServerId == server.id
                                    val isConnectedToThis = state.isConnected && isCurrent
                                    val serverStatus = serverConnectionStatus[server.id]

                                    ServerListItem(
                                        serverName = server.name,
                                        serverInfo = "${server.username}@${server.host}:${server.port}",
                                        isDefault = server.isActive,
                                        isConnected = isConnectedToThis,
                                        connectionStatus = serverStatus,
                                        onToggleDefault = { viewModel.toggleDefaultServer(server.id) },
                                        onClickEdit = { onNavigateToServerEdit(server.id) },
                                        onClickConnect = {
                                            if (isConnectedToThis) {
                                                viewModel.disconnect()
                                            } else {
                                                viewModel.connect(server.id)
                                            }
                                        },
                                        onLongClick = { showServerMenu = server.id }
                                    )
                                }
                            }
                        }
                    } else {
                        val keysList = keyViewModel.keys.collectAsState().value
                        if (keysList.isEmpty()) {
                            EmptyState(
                                icon = Icons.Default.DateRange,
                                title = "暂无密钥",
                                subtitle = "生成或导入 SSH 密钥以进行身份验证",
                                actionText = "添加密钥",
                                onAction = onNavigateToKeyAdd
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(keysList, key = { it.alias }) { key ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onNavigateToKeys() }
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            KeyKindIcon(
                                                kind = key.kind,
                                                isBiometricProtected = key.isBiometricProtected,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = key.alias,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = key.algorithm,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    val success = keyViewModel.copyPublicKey(key.publicKey)
                                                    if (success) {
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "公钥已复制",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = "复制",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 服务器长按菜单
    showServerMenu?.let { serverId ->
        val server = servers.find { it.id == serverId }
        if (server != null) {
            ServerContextMenu(
                serverName = server.name,
                isDefault = server.isActive,
                onEdit = {
                    showServerMenu = null
                    onNavigateToServerEdit(serverId)
                },
                onToggleDefault = {
                    showServerMenu = null
                    viewModel.toggleDefaultServer(serverId)
                },
                onDelete = {
                    showServerMenu = null
                    viewModel.deleteServer(serverId)
                },
                onDismiss = { showServerMenu = null }
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onAction() }
            ) {
                Text(
                    text = actionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerListItem(
    serverName: String,
    serverInfo: String,
    isDefault: Boolean,
    isConnected: Boolean,
    connectionStatus: String?,
    onToggleDefault: () -> Unit,
    onClickEdit: () -> Unit,
    onClickConnect: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isTransitioning = connectionStatus in listOf("Connecting", "Authenticating", "EstablishingTunnel", "Disconnecting")

    // 脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTransitioning) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isConnected -> MaterialTheme.colorScheme.primaryContainer
            isDefault -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        label = "containerColor"
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        modifier = Modifier.combinedClickable(
            onClick = onClickEdit,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：默认星标
            IconButton(
                onClick = onToggleDefault,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "默认",
                    modifier = Modifier.size(18.dp),
                    tint = if (isDefault)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            // 中间：服务器信息 (占 2/3)
            Row(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = serverName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = serverInfo,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 右侧：连接按钮 (占 1/3)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onClickConnect() }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isTransitioning -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .scale(pulseScale),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    isConnected -> Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "断开连接",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "连接",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ServerContextMenu(
    serverName: String,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onToggleDefault: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(serverName) },
        text = {
            Column {
                ContextMenuItem(
                    icon = Icons.Default.Create,
                    text = "编辑服务器",
                    onClick = onEdit
                )
                ContextMenuItem(
                    icon = Icons.Default.Star,
                    text = if (isDefault) "取消默认" else "设为默认",
                    onClick = onToggleDefault
                )
                ContextMenuItem(
                    icon = Icons.Default.Close,
                    text = "删除服务器",
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun ContextMenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            color = tint
        )
    }
}
