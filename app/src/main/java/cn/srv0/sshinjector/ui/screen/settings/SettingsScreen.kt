package cn.srv0.sshinjector.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.ui.viewmodel.dnsModeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateToDomainListSettings: () -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    onNavigateToServerManagement: () -> Unit = {},
    onNavigateToKeyManagement: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    val mtu by viewModel.mtu.collectAsState()
    val keepAlive by viewModel.keepAlive.collectAsState()
    val enableIPv6 by viewModel.enableIPv6.collectAsState()
    val dnsMode by viewModel.dnsMode.collectAsState()
    var showDnsDialog by remember { mutableStateOf(false) }
    var showBiometricDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
    val biometricAuth = fragmentActivity?.let { cn.srv0.sshinjector.ui.biometric.BiometricAuth.from(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection("管理") {
                SettingsRow(
                    title = "服务器管理",
                    subtitle = "添加、编辑或连接服务器",
                    trailing = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onNavigateToServerManagement
                )
                SettingsRow(
                    title = "密钥管理",
                    subtitle = "查看、生成、导入或复制密钥",
                    trailing = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onNavigateToKeyManagement
                )
                SettingsRow(
                    title = "应用白名单",
                    subtitle = "选择进入 VPN 隧道的应用",
                    trailing = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onNavigateToWhitelist
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
                    subtitle = dnsModeLabel(dnsMode),
                    trailing = {
                        Text(
                            text = dnsModeLabel(dnsMode),
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

            SettingsSection("安全") {
                SettingsRow(
                    title = "生物识别解锁",
                    subtitle = "使用指纹/面部识别保护密钥",
                    trailing = {
                        Switch(
                            checked = biometricUnlock,
                            onCheckedChange = { enabled ->
                                if (!enabled && biometricUnlock) {
                                    // 关闭时需要验证
                                    showBiometricDialog = true
                                } else {
                                    viewModel.setBiometricUnlock(enabled)
                                }
                            }
                        )
                    }
                )
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

    if (showDnsDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            title = { Text("连接模式") },
            text = {
                Column {
                    listOf(
                        0 to "远程代理" to "全部流量走 VPN 隧道 (SOCKS5)",
                        1 to "本地直连" to "全部流量走物理网卡，不经过 VPN",
                        2 to "白名单模式" to "白名单应用走 VPN，其余走物理网卡",
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
                                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDnsDialog = false }) { Text("取消") } }
        )
    }

    if (showBiometricDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBiometricDialog = false },
            title = { Text("关闭生物识别解锁") },
            text = { Text("关闭后将不再使用指纹/面部识别保护密钥，请验证身份以确认。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBiometricDialog = false
                        val activity = fragmentActivity
                        if (activity != null && biometricAuth != null) {
                            biometricAuth.authenticate(
                                activity = activity,
                                title = "验证身份",
                                onSuccess = { viewModel.setBiometricUnlock(false) },
                                onCancelled = {}
                            )
                        }
                    }
                ) { Text("验证并关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showBiometricDialog = false }) { Text("取消") }
            }
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

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { content() }
}
