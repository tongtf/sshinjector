package com.sshinjector.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.domain.vpn.tunnel.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRouteSettings: () -> Unit = {},
    onNavigateToDomainListSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val autoConnect by viewModel.autoConnect.collectAsState()
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    val notificationEnabled by viewModel.notificationEnabled.collectAsState()
    val mtu by viewModel.mtu.collectAsState()
    val keepAlive by viewModel.keepAlive.collectAsState()
    val enableIPv6 by viewModel.enableIPv6.collectAsState()
    val dnsMode by viewModel.dnsMode.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val logLevel by viewModel.logLevel.collectAsState()
    val availablePlugins by viewModel.availablePlugins.collectAsState()
    val activePlugin by viewModel.activePlugin.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection("常规") {
                SettingsRow(
                    title = "自动连接",
                    subtitle = "启动时自动连接上次服务器",
                    trailing = { Switch(checked = autoConnect, onCheckedChange = { viewModel.setAutoConnect(it) }) }
                )
                SettingsRow(
                    title = "生物识别解锁",
                    subtitle = "使用指纹/面部识别保护密钥",
                    trailing = { Switch(checked = biometricUnlock, onCheckedChange = { viewModel.setBiometricUnlock(it) }) }
                )
                SettingsRow(
                    title = "常驻通知",
                    subtitle = "在通知栏显示连接状态",
                    trailing = { Switch(checked = notificationEnabled, onCheckedChange = { viewModel.setNotificationEnabled(it) }) }
                )
            }

            SettingsSection("网络") {
                Text("MTU: $mtu", fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Slider(
                    value = mtu.toFloat(),
                    onValueChange = { viewModel.setMtu(it.toInt()) },
                    valueRange = 1280f..1500f,
                    steps = 54,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Text("保活间隔: ${keepAlive}s", fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Slider(
                    value = keepAlive.toFloat(),
                    onValueChange = { viewModel.setKeepAlive(it.toInt()) },
                    valueRange = 10f..120f,
                    steps = 21,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                SettingsRow(
                    title = "IPv6 支持",
                    subtitle = "启用 IPv6 双栈",
                    trailing = { Switch(checked = enableIPv6, onCheckedChange = { viewModel.setEnableIPv6(it) }) }
                )

                SettingsRow(
                    title = "连接模式",
                    subtitle = when(dnsMode) { 0 -> "远程代理"; 1 -> "本地直连"; 2 -> "自动模式"; 3 -> "域名分流"; else -> "远程代理" },
                    trailing = {
                        Text(
                            text = when(dnsMode) { 0 -> "远程代理"; 1 -> "本地直连"; 2 -> "自动模式"; 3 -> "域名分流"; else -> "远程代理" },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = { showDnsDialog = true }
                )

                SettingsRow(
                    title = "域名列表",
                    subtitle = "配置域名分流列表 (仅域名分流模式生效)",
                    trailing = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onNavigateToDomainListSettings
                )
            }

            SettingsSection("外观") {
                SettingsRow(
                    title = "主题",
                    subtitle = when(theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" },
                    trailing = {
                        IconButton(onClick = { showThemeDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "切换主题",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

                SettingsRow(
                    title = "日志级别",
                    subtitle = if (logLevel == 0) "简洁" else "详细",
                    trailing = {
                        IconButton(onClick = { showLogLevelDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "设置日志级别",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }

            SettingsSection("隧道插件") {
                SettingsRow(
                    title = "路由规则",
                    subtitle = "配置应用标签与隧道映射",
                    trailing = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onNavigateToRouteSettings
                )
                availablePlugins.forEach { plugin ->
                    val isActive = activePlugin?.id == plugin.id
                    val statusText = when {
                        isActive -> "运行中"
                        else -> "就绪"
                    }
                    val statusColor = when {
                        isActive && plugin.state.value.status == TunnelState.Status.Connected ->
                            MaterialTheme.colorScheme.primary
                        isActive -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    SettingsRow(
                        title = plugin.displayName,
                        subtitle = statusText,
                        trailing = {
                            Text(
                                text = if (isActive) "●" else "○",
                                fontSize = 16.sp,
                                color = statusColor
                            )
                        }
                    )
                }
                if (availablePlugins.isEmpty()) {
                    Text(
                        "无可用插件",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                }
            }

            SettingsSection("关于") {
                SettingsRow("版本", "1.0.0", trailing = {})
                SettingsRow("开源协议", "MIT License", trailing = {})
                SettingsRow(
                    title = "GitHub",
                    subtitle = "查看源代码",
                    trailing = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                )
            }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setTheme(value); showThemeDialog = false }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontSize = 16.sp)
                            if (theme == value) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("取消") } }
        )
    }

    if (showDnsDialog) {
        AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            title = { Text("连接模式") },
            text = {
                Column {
                    listOf(
                        0 to "远程代理" to "全部流量走 VPN 隧道 (SOCKS5)",
                        1 to "本地直连" to "全部流量走物理网卡，不经过 VPN",
                        2 to "自动模式" to "白名单应用走 VPN，其余走物理网卡",
                        3 to "域名分流" to "命中域名列表走隧道，其余域名直连"
                    ).forEach { (item, desc) ->
                        val (value, label) = item
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setDnsMode(value); showDnsDialog = false }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, fontSize = 16.sp)
                                Text(desc, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (dnsMode == value) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDnsDialog = false }) { Text("取消") } }
        )
    }

    if (showLogLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLogLevelDialog = false },
            title = { Text("日志级别") },
            text = {
                Column {
                    listOf(0 to "简洁", 1 to "详细").forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setLogLevel(value); showLogLevelDialog = false }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, fontSize = 16.sp)
                                if (value == 0) {
                                    Text("仅显示关键状态信息",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("显示详细连接过程和调试信息",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (logLevel == value) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLogLevelDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp)
            Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
