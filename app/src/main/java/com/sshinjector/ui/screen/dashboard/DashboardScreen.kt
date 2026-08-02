package com.sshinjector.ui.screen.dashboard

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToKeys: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isConnected = state.isConnected
    val startTime = state.startTime

    var duration by remember { mutableStateOf("00:00:00") }
    LaunchedEffect(startTime) {
        if (startTime == null) return@LaunchedEffect
        while (true) {
            val elapsed = (java.util.Date().time - startTime.time) / 1000
            duration = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
            delay(1000)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectDefaultServer()
        }
    }

    LaunchedEffect(state.vpnPermissionIntent) {
        state.vpnPermissionIntent?.let { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    Column(
        modifier = modifier
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
            }
        }

        // 活跃隧道卡片
        if (state.activeTunnels.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("活跃隧道", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    state.activeTunnels.forEach { tunnel ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor = when (tunnel.status) {
                                    com.sshinjector.domain.vpn.tunnel.TunnelState.Status.Connected -> Color(0xFF4CAF50)
                                    com.sshinjector.domain.vpn.tunnel.TunnelState.Status.Connecting,
                                    com.sshinjector.domain.vpn.tunnel.TunnelState.Status.Authenticating -> Color(0xFFFF9800)
                                    com.sshinjector.domain.vpn.tunnel.TunnelState.Status.Failed -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(tunnel.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = tunnel.status.name,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${tunnel.activeConnections} 连接",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 连接状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：连接图标
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isConnected -> Color(0xFFE53935)
                                state.connectionStatus == "Failed" -> MaterialTheme.colorScheme.error
                                state.connectionStatus == "Connecting" ||
                                state.connectionStatus == "Authenticating" ||
                                state.connectionStatus == "EstablishingTunnel" -> Color(0xFFFF9800)
                                state.connectionStatus == "Disconnecting" -> Color(0xFFFF9800)
                                else -> Color(0xFF4CAF50)
                            }
                        )
                        .clickable {
                            if (isConnected) {
                                viewModel.disconnect()
                            } else if (state.hasDefaultServer) {
                                viewModel.connectDefaultServerWithDiag()
                            } else {
                                onNavigateToServers()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val isConnecting = state.connectionStatus in listOf("Connecting", "Authenticating", "EstablishingTunnel", "Disconnecting")
                    when {
                        isConnecting -> CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                        isConnected -> Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "断开连接",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                        else -> Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "连接服务器",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 右侧：状态信息
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isConnected -> "已连接"
                            state.connectionStatus == "Connecting" -> "正在连接"
                            state.connectionStatus == "Authenticating" -> "正在认证"
                            state.connectionStatus == "EstablishingTunnel" -> "建立隧道"
                            state.connectionStatus == "Disconnecting" -> "断开中..."
                            state.connectionStatus == "Failed" -> "连接失败"
                            else -> "未连接"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val serverLabel = when {
                        isConnected -> state.currentServer
                        state.hasDefaultServer -> state.defaultServerName
                        else -> "未配置服务器"
                    }
                    Text(
                        text = serverLabel,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 诊断信息 badge
                    val diag = state.diagnostics
                    if (diag.lastTestTime > 0 || diag.isRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (diag.isRunning) {
                                DiagBadge(text = "测试中...", color = Color(0xFFFF9800))
                            } else {
                                if (diag.dnsSuccess) {
                                    DiagBadge(
                                        text = "DNS ${diag.dnsLatencyMs}ms ${diag.dnsSuccessCount}/5",
                                        color = Color(0xFF4CAF50)
                                    )
                                } else {
                                    DiagBadge(text = "DNS 超时", color = MaterialTheme.colorScheme.error)
                                }
                                if (diag.httpSuccess) {
                                    DiagBadge(
                                        text = "HTTP ${diag.httpLatencyMs}ms ${diag.httpSuccessCount}/5",
                                        color = Color(0xFF4CAF50)
                                    )
                                } else {
                                    DiagBadge(text = "HTTP 失败", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    if (isConnected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${state.currentServerUser}@${state.currentServerHost}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "运行: $duration",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        // 连接日志 - 占据剩余空间
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
                    Text("连接日志", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (state.logLevel == 0) "简洁" else "详细",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 日志已按逆序存储（最新在前），直接显示
                    val filteredLogs = if (state.logLevel == 0) {
                        state.connectionLogs.filter {
                            it.level != MainViewModel.LogLevel.DEBUG
                        }
                    } else {
                        state.connectionLogs
                    }.take(20) // 只显示最新 20 条

                    if (filteredLogs.isEmpty()) {
                        Text(
                            text = "暂无日志",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        filteredLogs.forEach { entry ->
                            LogEntryItem(entry, state.logLevel == 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(entry: MainViewModel.ConnectionLog, showTimestamp: Boolean) {
    val color = when (entry.level) {
        MainViewModel.LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        MainViewModel.LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        MainViewModel.LogLevel.SUCCESS -> Color(0xFF4CAF50)
        MainViewModel.LogLevel.ERROR -> MaterialTheme.colorScheme.error
        MainViewModel.LogLevel.WARNING -> Color(0xFFFF9800)
    }

    val prefix = when (entry.level) {
        MainViewModel.LogLevel.INFO -> "●"
        MainViewModel.LogLevel.DEBUG -> "○"
        MainViewModel.LogLevel.SUCCESS -> "✓"
        MainViewModel.LogLevel.ERROR -> "✕"
        MainViewModel.LogLevel.WARNING -> "△"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态图标
        Text(
            text = prefix,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.width(14.dp)
        )
        // 时间戳（如果启用）
        if (showTimestamp) {
            Text(
                text = entry.timestamp,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(50.dp)
            )
        }
        // 日志消息
        Text(
            text = entry.message,
            fontSize = 12.sp,
            color = if (entry.level == MainViewModel.LogLevel.DEBUG)
                MaterialTheme.colorScheme.outline
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DiagBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
