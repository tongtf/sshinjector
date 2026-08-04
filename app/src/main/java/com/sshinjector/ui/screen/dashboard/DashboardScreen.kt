package com.sshinjector.ui.screen.dashboard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.ui.screen.keymanager.KeyKindIcon
import com.sshinjector.ui.screen.keymanager.KeyManagerViewModel
import com.sshinjector.ui.viewmodel.MainViewModel

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

    // 服务器连接状态: serverId -> status
    val serverConnectionStatus = state.serverConnectionStatus

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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

            // 服务器 / 密钥 双列卡片
            val servers by viewModel.allServers.collectAsState(initial = emptyList())
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

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (!columnMode) {
                            if (servers.isEmpty()) {
                                Text(
                                    text = "暂无服务器，点击添加",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToServers() }
                                        .padding(vertical = 8.dp)
                                )
                            } else {
                                servers.forEach { server ->
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
                                        }
                                    )
                                    if (server != servers.last()) {
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }
                        } else {
                            val keysList = keyViewModel.keys.collectAsState().value
                            if (keysList.isEmpty()) {
                                Text(
                                    text = "暂无密钥，点击添加",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToKeys() }
                                        .padding(vertical = 8.dp)
                                )
                            } else {
                                keysList.forEach { key ->
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
}

@Composable
fun ServerListItem(
    serverName: String,
    serverInfo: String,
    isDefault: Boolean,
    isConnected: Boolean,
    connectionStatus: String?,
    onToggleDefault: () -> Unit,
    onClickEdit: () -> Unit,
    onClickConnect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isConnected)
            MaterialTheme.colorScheme.primaryContainer
        else if (isDefault)
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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

            // 中间：服务器信息 (占 2/3) - 点击跳转编辑
            Row(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .clickable { onClickEdit() }
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

            // 右侧：连接按钮 (占 1/3) - 点击连接/断开
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onClickConnect() }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val isTransitioning = connectionStatus in listOf("Connecting", "Authenticating", "EstablishingTunnel", "Disconnecting")
                when {
                    isTransitioning -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
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
