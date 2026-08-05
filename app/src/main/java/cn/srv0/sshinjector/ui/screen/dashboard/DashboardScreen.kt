package cn.srv0.sshinjector.ui.screen.dashboard

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.ui.screen.keymanager.KeyKindIcon
import cn.srv0.sshinjector.ui.screen.keymanager.KeyManagerViewModel
import cn.srv0.sshinjector.ui.viewmodel.MainViewModel

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
    val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
    val biometricAuth = fragmentActivity?.let { cn.srv0.sshinjector.ui.biometric.BiometricAuth.from(it) }

    LaunchedEffect(Unit) { keyViewModel.refresh() }

    var columnMode by remember { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf<Long?>(null) }

    val serverConnectionStatus = state.serverConnectionStatus
    val servers by viewModel.allServers.collectAsState(
        initial = emptyList<cn.srv0.sshinjector.domain.model.ServerConfig>())

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                        Text(stringResource(cn.srv0.sshinjector.R.string.dashboard_status_info),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { viewModel.refreshNetworkInfo() },
                            modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_refresh_info),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(cn.srv0.sshinjector.R.string.dashboard_ipv4),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.deviceIpv4, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(cn.srv0.sshinjector.R.string.dashboard_ipv6),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.deviceIpv6, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(cn.srv0.sshinjector.R.string.dashboard_mode),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val (dnsBg, dnsFg) = dnsModeColors(state.dnsMode)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(cn.srv0.sshinjector.R.string.dashboard_network),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.networkDetail,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = state.connectionStatus,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(cn.srv0.sshinjector.R.string.dashboard_cpu),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.cpuUsage,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MainViewModel.colorForRatio(
                                    if (state.cpuUsage != "-") {
                                        state.cpuUsage.replace("%", "").toFloatOrNull()
                                            ?.div(10f) ?: 0f
                                    } else 0f
                                ),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.width(48.dp)
                            )
                        }
                        Text(
                            text = if (state.javaHeapUsage != "-" &&
                                state.nativeHeapUsage != "-")
                                "Heap:${state.javaHeapUsage} Native:${state.nativeHeapUsage}"
                            else "-",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MainViewModel.colorForRatio(
                                if (state.javaHeapUsage != "-" &&
                                    state.javaHeapUsage.contains(" MB")) {
                                    val num = state.javaHeapUsage.replace(" MB", "")
                                        .replace(" GB", "").toFloatOrNull() ?: 0f
                                    val inMb = if (state.javaHeapUsage.contains(" GB")) num * 1024f
                                    else num
                                    inMb / 50f
                                } else 0f
                            ),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

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
                                text = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_servers),
                                fontSize = 16.sp,
                                fontWeight = if (!columnMode) FontWeight.Bold
                                    else FontWeight.Normal,
                                color = if (!columnMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { columnMode = false }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(cn.srv0.sshinjector.R.string.dashboard_keys),
                                fontSize = 16.sp,
                                fontWeight = if (columnMode) FontWeight.Bold
                                    else FontWeight.Normal,
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
                            Icon(Icons.Default.Add,
                                contentDescription = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_add))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (!columnMode) {
                        if (servers.isEmpty()) {
                            EmptyState(
                                icon = Icons.Default.FavoriteBorder,
                                title = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_no_servers),
                                subtitle = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_no_servers_hint),
                                actionText = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_add_server),
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
                                        onToggleDefault = {
                                            viewModel.toggleDefaultServer(server.id) },
                                        onClickEdit = { onNavigateToServerEdit(server.id) },
                                        onClickConnect = {
                                            if (isConnectedToThis) {
                                                viewModel.disconnect()
                                            } else {
                                                val onGranted = {
                                                    viewModel.connect(server.id) }
                                                if (fragmentActivity != null &&
                                                    biometricAuth != null) {
                                                    biometricAuth.connectIfAllowed(
                                                        fragmentActivity, server.keyAlias,
                                                        onGranted)
                                                } else {
                                                    onGranted()
                                                }
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
                                title = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_no_keys),
                                subtitle = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_no_keys_hint),
                                actionText = stringResource(
                                    cn.srv0.sshinjector.R.string.dashboard_add_key),
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
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = key.algorithm,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme
                                                        .colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    val success =
                                                        keyViewModel.copyPublicKey(
                                                            key.publicKey)
                                                    if (success) {
                                                        val label =
                                                            context.resources.getString(
                                                            cn.srv0.sshinjector.R.string.dashboard_key_copied)
                                                        android.widget.Toast.makeText(
                                                            context, label,
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = stringResource(
                                                    cn.srv0.sshinjector.R.string.copy),
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
    val isTransitioning = connectionStatus in listOf(
        "Connecting", "Authenticating", "EstablishingTunnel", "Disconnecting")
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
            IconButton(
                onClick = onToggleDefault,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = stringResource(
                        cn.srv0.sshinjector.R.string.dashboard_default),
                    modifier = Modifier.size(18.dp),
                    tint = if (isDefault)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
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
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = serverInfo,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                        contentDescription = stringResource(
                            cn.srv0.sshinjector.R.string.dashboard_disconnect),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            cn.srv0.sshinjector.R.string.dashboard_connect),
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
                    text = stringResource(
                        cn.srv0.sshinjector.R.string.dashboard_edit_server),
                    onClick = onEdit
                )
                ContextMenuItem(
                    icon = Icons.Default.Star,
                    text = if (isDefault)
                        stringResource(
                            cn.srv0.sshinjector.R.string.dashboard_unset_default)
                    else
                        stringResource(
                            cn.srv0.sshinjector.R.string.dashboard_set_default),
                    onClick = onToggleDefault
                )
                ContextMenuItem(
                    icon = Icons.Default.Close,
                    text = stringResource(
                        cn.srv0.sshinjector.R.string.dashboard_delete_server),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(cn.srv0.sshinjector.R.string.cancel))
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

@Composable
private fun dnsModeColors(dnsMode: String): Pair<Color, Color> {
    return when (dnsMode) {
        "远程代理" -> Color(0xFF7C4DFF) to Color.White
        "本地直连" -> Color(0xFF9E9E9E) to Color.White
        "白名单模式" -> Color(0xFF00BCD4) to Color.White
        "域名分流" -> Color(0xFFFF9800) to Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurface
    }
}