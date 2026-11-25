package cn.srv0.sshinjector.ui.screen.routing

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.vpn.exit.ExitHealth
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.ui.component.BackNavIcon

/**
 * 多出口配置页 (设计 §13.1 / §13.2)。
 *
 * 结构刻意保持"一屏到底": 总开关 → 附加出口列表 → 规则入口。规则文本量可能很大, 单独一页编辑,
 * 但列表页必须能一眼看出"哪些出口在主出口之外、各自被多少条规则引用"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiExitScreen(
    onBack: () -> Unit,
    onEditRules: () -> Unit,
    viewModel: MultiExitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MultiExitRow?>(null) }
    var pendingRename by remember { mutableStateOf<MultiExitRow?>(null) }
    val nameInvalid = stringResource(R.string.multi_exit_name_invalid)
    val nameTaken = stringResource(R.string.multi_exit_name_taken)
    val addFailed = stringResource(R.string.multi_exit_add_failed)
    val maxExits = stringResource(R.string.multi_exit_max_exits)
    val renameFailed = stringResource(R.string.multi_exit_rename_failed)
    val reconnectRequired = stringResource(R.string.multi_exit_reconnect_required)

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text =
            when (message) {
                MultiExitViewModel.MSG_NAME_TAKEN -> nameTaken
                MultiExitViewModel.MSG_ADD_FAILED -> addFailed
                MultiExitViewModel.MSG_MAX_EXITS -> maxExits
                MultiExitViewModel.MSG_RENAME_FAILED -> renameFailed
                MultiExitViewModel.MSG_RECONNECT_REQUIRED -> reconnectRequired
                else -> message
            }
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multi_exit_title)) },
                navigationIcon = { BackNavIcon(onBack) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            state.unsupportedReason?.let { reason ->
                UnsupportedBanner(reason)
            }

            ToggleRow(
                title = stringResource(R.string.multi_exit_enable),
                subtitle = stringResource(R.string.multi_exit_enable_desc),
                checked = state.enabled,
                // 不支持时仍要允许**关掉**已有配置: 否则切到 SYSTEM DNS 后就再也关不掉了
                enabled = state.supported || state.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
            HorizontalDivider()
            ToggleRow(
                title = stringResource(R.string.multi_exit_failover),
                subtitle = stringResource(R.string.multi_exit_failover_desc),
                checked = state.failoverEnabled,
                enabled = state.enabled,
                onCheckedChange = viewModel::setFailoverEnabled,
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.multi_exit_exits))
            if (state.exits.isEmpty()) {
                Text(
                    stringResource(R.string.multi_exit_exits_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                state.exits.forEach { row ->
                    ExitRow(
                        row = row,
                        onToggle = { viewModel.setExitEnabled(row.exit.id, it) },
                        onRename = { pendingRename = row },
                        onDelete = { pendingDelete = row },
                    )
                }
            }
            val atCap = state.exits.size >= MultiExitViewModel.MAX_ADDITIONAL_EXITS
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (atCap) Modifier else Modifier.clickable { showAddDialog = true })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (atCap) {
                        stringResource(R.string.multi_exit_max_exits)
                    } else {
                        stringResource(R.string.multi_exit_add)
                    },
                    fontSize = 14.sp,
                    color =
                        if (atCap) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditRules)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.multi_exit_rules), fontSize = 16.sp)
                    Text(
                        stringResource(R.string.multi_exit_rules_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.multi_exit_rules_edit),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showAddDialog) {
        AddExitDialog(
            servers = state.servers,
            nameInvalid = nameInvalid,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, serverId ->
                viewModel.addExit(name, serverId)
                showAddDialog = false
            },
        )
    }

    pendingRename?.let { row ->
        RenameExitDialog(
            current = row.exit.name,
            nameInvalid = nameInvalid,
            onDismiss = { pendingRename = null },
            onConfirm = { newName, updateRules ->
                viewModel.renameExit(row.exit.id, newName, updateRules)
                pendingRename = null
            },
        )
    }

    pendingDelete?.let { row ->
        var deleteRules by remember(row.exit.id) { mutableStateOf(row.referencedRules > 0) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.multi_exit_delete_title, row.exit.name)) },
            text = {
                Column {
                    Text(
                        if (row.referencedRules > 0) {
                            stringResource(R.string.multi_exit_delete_referenced, row.referencedRules)
                        } else {
                            stringResource(R.string.multi_exit_delete_desc)
                        },
                    )
                    if (row.referencedRules > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteRules, onCheckedChange = { deleteRules = it })
                            Text(
                                stringResource(R.string.multi_exit_delete_rules, row.referencedRules),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteExit(row.exit.id, deleteRules)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.multi_exit_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.multi_exit_cancel))
                }
            },
        )
    }
}

@Composable
private fun UnsupportedBanner(reason: MultiExitUiState.UnsupportedReason) {
    val text =
        when (reason) {
            MultiExitUiState.UnsupportedReason.SYSTEM_DNS ->
                stringResource(R.string.multi_exit_unsupported_system)
            MultiExitUiState.UnsupportedReason.EMPTY_WHITELIST ->
                stringResource(R.string.multi_exit_unsupported_whitelist)
            MultiExitUiState.UnsupportedReason.DOMAIN_SPLIT ->
                stringResource(R.string.multi_exit_unsupported_domain_split)
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ExitRow(
    row: MultiExitRow,
    onToggle: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.exit.name, fontSize = 15.sp)
            Text(
                stringResource(R.string.multi_exit_exit_subtitle, row.serverName, row.referencedRules),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 会话内运行态: 只有本次会话真的启动过才显示 (未连接时不误导)
            row.runtime?.let { runtime ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(exitHealthLabel(runtime.health)),
                        fontSize = 11.sp,
                        color =
                            when (runtime.health) {
                                ExitHealth.Ready -> MaterialTheme.colorScheme.primary
                                ExitHealth.Degraded, ExitHealth.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    if (runtime.activeConnections > 0) {
                        Text(
                            " · " + stringResource(R.string.multi_exit_runtime_conns, runtime.activeConnections),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (runtime.failTotal > 0) {
                        Text(
                            " · " + stringResource(R.string.multi_exit_runtime_fails, runtime.failTotal),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Switch(checked = row.exit.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.multi_exit_rename))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.multi_exit_delete))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RenameExitDialog(
    current: String,
    nameInvalid: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    var updateRules by remember { mutableStateOf(true) }
    val nameOk = ExitId.isValid(name) && name != current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multi_exit_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.multi_exit_exit_name)) },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameOk,
                    supportingText = {
                        if (name.isNotEmpty() && !nameOk) Text(nameInvalid)
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = updateRules, onCheckedChange = { updateRules = it })
                    Text(
                        stringResource(R.string.multi_exit_rename_rules),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, updateRules) }, enabled = nameOk) {
                Text(stringResource(R.string.multi_exit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.multi_exit_cancel)) }
        },
    )
}

@Composable
private fun AddExitDialog(
    servers: List<ServerConfig>,
    nameInvalid: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var serverId by remember { mutableStateOf(servers.firstOrNull()?.id ?: 0L) }
    var expanded by remember { mutableStateOf(false) }
    val nameOk = ExitId.isValid(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multi_exit_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.multi_exit_exit_name)) },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameOk,
                    supportingText = {
                        if (name.isNotEmpty() && !nameOk) Text(nameInvalid)
                    },
                )
                Spacer(Modifier.height(12.dp))
                Column {
                    Text(
                        stringResource(R.string.multi_exit_server),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { expanded = true }) {
                        Text(servers.firstOrNull { it.id == serverId }?.name ?: "—")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        servers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server.name) },
                                onClick = {
                                    serverId = server.id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, serverId) },
                enabled = nameOk && serverId != 0L,
            ) { Text(stringResource(R.string.multi_exit_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.multi_exit_cancel)) }
        },
    )
}

@StringRes
private fun exitHealthLabel(health: ExitHealth): Int =
    when (health) {
        ExitHealth.Starting -> R.string.multi_exit_state_starting
        ExitHealth.Ready -> R.string.multi_exit_state_ready
        ExitHealth.Degraded -> R.string.multi_exit_state_degraded
        ExitHealth.Failed -> R.string.multi_exit_state_failed
        ExitHealth.Stopped -> R.string.multi_exit_state_stopped
    }
