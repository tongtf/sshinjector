package com.sshinjector.ui.screen.server

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.data.local.entity.ServerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    serverId: Long,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ServerEditViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val isNew = serverId == -1L
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var showKeyDropdown by remember { mutableStateOf(false) }
    var enableIPv6 by remember { mutableStateOf(true) }
    var mtu by remember { mutableStateOf("1500") }
    var keepAlive by remember { mutableStateOf("30") }
    var setAsDefault by remember { mutableStateOf(false) }
    var tunnelType by remember { mutableStateOf("socks5") }

    val availableKeys by viewModel.keyAliases.collectAsState()

    LaunchedEffect(serverId) {
        viewModel.load(serverId) { entity ->
            name = entity.name
            host = entity.host
            port = entity.port.toString()
            username = entity.username
            keyAlias = entity.keyAlias
            enableIPv6 = entity.enableIPv6
            mtu = entity.mtu.toString()
            keepAlive = entity.keepAliveInterval.toString()
            setAsDefault = entity.isActive
            tunnelType = entity.tunnelType
        }
    }

    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加服务器" else "编辑服务器") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val entity = ServerEntity(
                            id = if (isNew) 0 else serverId,
                            name = name,
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            keyAlias = keyAlias,
                            isActive = setAsDefault,
                            enableIPv6 = enableIPv6,
                            mtu = mtu.toIntOrNull() ?: 1500,
                            keepAliveInterval = keepAlive.toIntOrNull() ?: 30,
                            tunnelType = tunnelType,
                        )
                        viewModel.save(serverId, entity, onSave, setAsDefault)
                    }) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = "保存")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("基础配置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextFieldRow("名称", "我的 VPS", name, { name = it })
                    OutlinedTextFieldRow("主机", "example.com", host, { host = it }, singleLine = true)
                    OutlinedTextFieldRow("端口", "22", port, { port = it }, singleLine = true, numeric = true)
                    OutlinedTextFieldRow("用户名", "root", username, { username = it }, singleLine = true)
                    KeyAliasSelector(keyAlias, availableKeys, showKeyDropdown, onSelect = { keyAlias = it; showKeyDropdown = false }, onToggleDropdown = { showKeyDropdown = !showKeyDropdown }, onGenerate = { viewModel.generateAndAssociate { newAlias -> keyAlias = newAlias } })
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("隧道配置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    TunnelTypeSelector(
                        selectedType = tunnelType,
                        onTypeSelected = { tunnelType = it }
                    )

                    // 显示当前隧道类型的能力标签
                    val selectedPlugin = TUNNEL_TYPES.find { it.id == tunnelType }
                    if (selectedPlugin != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            selectedPlugin.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("网络配置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchRow("设置为默认服务器", setAsDefault, { setAsDefault = it })
                    SwitchRow("启用 IPv6", enableIPv6, { enableIPv6 = it })
                    OutlinedTextFieldRow("MTU", "1500", mtu, { mtu = it }, singleLine = true, numeric = true)
                    OutlinedTextFieldRow("保活间隔 (秒)", "30", keepAlive, { keepAlive = it }, singleLine = true, numeric = true)
                }
            }

            if (!isNew) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.delete(serverId, onSave) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("删除服务器")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OutlinedTextFieldRow(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    numeric: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 16.sp, modifier = Modifier.width(120.dp))
        Spacer(modifier = Modifier.width(16.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            placeholder = { Text(placeholder) },
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
            )
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun KeyAliasSelector(
    selectedAlias: String,
    availableKeys: List<String>,
    isDropdownExpanded: Boolean,
    onSelect: (String) -> Unit,
    onToggleDropdown: () -> Unit,
    onGenerate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "密钥", fontSize = 16.sp, modifier = Modifier.width(120.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (availableKeys.isEmpty()) {
                    // 没有密钥时显示生成按钮
                    Button(
                        onClick = onGenerate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("生成新密钥并关联", fontSize = 14.sp)
                        }
                    }
                } else {
                    // 有密钥时显示下拉选择
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable { onToggleDropdown() }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedAlias.isBlank()) "点击选择密钥..." else selectedAlias,
                            fontSize = 16.sp,
                            color = if (selectedAlias.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                    DropdownMenu(
                        expanded = isDropdownExpanded,
                        onDismissRequest = { onToggleDropdown() }
                    ) {
                        availableKeys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key) },
                                onClick = { onSelect(key) },
                                trailingIcon = if (key == selectedAlias) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("生成新密钥", color = MaterialTheme.colorScheme.primary) },
                            onClick = onGenerate,
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    }
}
