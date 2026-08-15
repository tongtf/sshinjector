package cn.srv0.sshinjector.ui.screen.server

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.ui.component.rememberClickGuard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    serverId: Long,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerEditViewModel = hiltViewModel(),
) {
    val isNew = serverId == -1L
    val snackbarHostState = remember { SnackbarHostState() }

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
    var socksPort by remember { mutableStateOf("1080") }

    val availableKeys by viewModel.keyAliases.collectAsState()
    val globalSettings by viewModel.globalSettings.collectAsState()
    val guard = rememberClickGuard()
    val scope = rememberCoroutineScope()
    var saveAttempted by remember { mutableStateOf(false) }

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
            socksPort = entity.socksPort.toString()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(serverId) {
        viewModel.refreshKeys()
    }

    val nameErr = ServerFormValidator.nameError(name)
    val hostErr = ServerFormValidator.hostError(host)
    val portErr = ServerFormValidator.portError(port)
    val usernameErr = ServerFormValidator.usernameError(username)
    val socksPortErr = ServerFormValidator.socksPortError(socksPort)
    val mtuErr = ServerFormValidator.mtuError(mtu)
    val keepAliveErr = ServerFormValidator.keepAliveError(keepAlive)
    val firstError =
        listOfNotNull(nameErr, hostErr, portErr, usernameErr, socksPortErr, mtuErr, keepAliveErr).firstOrNull()
    val firstErrorMessage = firstError?.let { stringResource(errorRes(it)) }
    val nameErrorText = if (saveAttempted && nameErr != null) stringResource(errorRes(nameErr)) else null
    val hostErrorText = if (saveAttempted && hostErr != null) stringResource(errorRes(hostErr)) else null
    val portErrorText = if (saveAttempted && portErr != null) stringResource(errorRes(portErr)) else null
    val usernameErrorText = if (saveAttempted && usernameErr != null) stringResource(errorRes(usernameErr)) else null
    val socksPortErrorText = if (saveAttempted && socksPortErr != null) stringResource(errorRes(socksPortErr)) else null
    val mtuErrorText = if (saveAttempted && mtuErr != null) stringResource(errorRes(mtuErr)) else null
    val keepAliveErrorText = if (saveAttempted && keepAliveErr != null) stringResource(errorRes(keepAliveErr)) else null

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) {
                            stringResource(R.string.server_add)
                        } else {
                            stringResource(R.string.server_edit)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            guard {
                                saveAttempted = true
                                if (firstError != null) {
                                    scope.launch { snackbarHostState.showSnackbar(firstErrorMessage.orEmpty()) }
                                    return@guard
                                }
                                val entity =
                                    ServerEntity(
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
                                        socksPort = socksPort.toIntOrNull() ?: 1080,
                                    )
                                viewModel.save(serverId, entity, onSave, setAsDefault)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.save),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.server_save),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.server_field_basic),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_name),
                        stringResource(R.string.server_placeholder_name),
                        name,
                        { name = it },
                        isError = nameErrorText != null,
                        supportingText = nameErrorText,
                    )
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_host),
                        stringResource(R.string.server_placeholder_host),
                        host,
                        { host = it },
                        singleLine = true,
                        isError = hostErrorText != null,
                        supportingText = hostErrorText,
                    )
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_port),
                        stringResource(R.string.server_placeholder_port),
                        port,
                        { port = it },
                        singleLine = true,
                        numeric = true,
                        isError = portErrorText != null,
                        supportingText = portErrorText,
                    )
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_username),
                        stringResource(R.string.server_placeholder_username),
                        username,
                        { username = it },
                        singleLine = true,
                        isError = usernameErrorText != null,
                        supportingText = usernameErrorText,
                    )
                    KeyAliasSelector(
                        keyAlias,
                        availableKeys,
                        showKeyDropdown,
                        onSelect = {
                            keyAlias = it
                            showKeyDropdown = false
                        },
                        onToggleDropdown = {
                            viewModel.refreshKeys()
                            showKeyDropdown = !showKeyDropdown
                        },
                        onGenerate = {
                            viewModel.generateAndAssociate {
                                    newAlias ->
                                keyAlias = newAlias
                            }
                        },
                    )
                    if (!isNew) {
                        OutlinedButton(
                            onClick = {
                                guard {
                                    viewModel.resetHostKey(host, port.toIntOrNull() ?: 22)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.server_reset_fingerprint))
                        }
                        Text(
                            stringResource(R.string.server_reset_fingerprint_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.server_field_tunnel),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_socks_port),
                        stringResource(R.string.server_placeholder_socks),
                        socksPort,
                        { socksPort = it },
                        singleLine = true,
                        numeric = true,
                        isError = socksPortErrorText != null,
                        supportingText = socksPortErrorText,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.server_field_network),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchRow(
                        stringResource(R.string.server_set_default),
                        setAsDefault,
                        { setAsDefault = it },
                    )
                    SwitchRow(
                        stringResource(R.string.server_enable_ipv6),
                        enableIPv6,
                        { enableIPv6 = it },
                    )
                    if (globalSettings.enableIPv6 != null) {
                        GlobalOverrideHint("全局设置: 启用 IPv6 = ${globalSettings.enableIPv6}")
                    }
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_mtu),
                        "1500",
                        mtu,
                        { mtu = it },
                        singleLine = true,
                        numeric = true,
                        isError = mtuErrorText != null,
                        supportingText = mtuErrorText ?: globalSettings.mtu?.let { "全局设置: MTU = $it" },
                    )
                    OutlinedTextFieldRow(
                        stringResource(R.string.server_keepalive),
                        "30",
                        keepAlive,
                        { keepAlive = it },
                        singleLine = true,
                        numeric = true,
                        isError = keepAliveErrorText != null,
                        supportingText = keepAliveErrorText ?: globalSettings.keepAlive?.let { "全局设置: 保活 = ${it}s" },
                    )
                }
            }

            if (!isNew) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                guard {
                                    viewModel.delete(serverId, onSave)
                                }
                            },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.server_delete))
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
    numeric: Boolean = false,
    password: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation =
            if (password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    when {
                        numeric -> KeyboardType.Number
                        password -> KeyboardType.Password
                        else -> KeyboardType.Text
                    },
            ),
    )
}

@Composable
private fun GlobalOverrideHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun errorRes(error: ServerFormError): Int =
    when (error) {
        ServerFormError.NAME_REQUIRED -> R.string.server_error_name_required
        ServerFormError.NAME_TOO_LONG -> R.string.server_error_name_too_long
        ServerFormError.HOST_REQUIRED -> R.string.server_error_host_required
        ServerFormError.HOST_INVALID -> R.string.server_error_host_invalid
        ServerFormError.PORT_RANGE -> R.string.wizard_invalid_port
        ServerFormError.SOCKS_PORT_RANGE -> R.string.server_error_socks_port_range
        ServerFormError.MTU_RANGE -> R.string.server_error_mtu_range
        ServerFormError.KEEPALIVE_RANGE -> R.string.server_error_keepalive_range
        ServerFormError.USERNAME_REQUIRED -> R.string.server_error_username_required
        ServerFormError.USERNAME_INVALID -> R.string.server_error_username_invalid
    }

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
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
    onGenerate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (availableKeys.isEmpty()) {
            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.server_generate_and_assoc),
                        fontSize = 14.sp,
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = selectedAlias,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.server_key)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.server_key_placeholder),
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggleDropdown() },
                )
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clickable { onToggleDropdown() },
                )
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { onToggleDropdown() },
                ) {
                    availableKeys.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(key) },
                            onClick = { onSelect(key) },
                            trailingIcon =
                                if (key == selectedAlias) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.server_generate_key),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = onGenerate,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
