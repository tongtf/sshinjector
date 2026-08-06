package cn.srv0.sshinjector.ui.screen.server

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.entity.ServerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsState()
    val connectingServerId by viewModel.connectingServerId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
    val biometricAuth =
        fragmentActivity?.let {
            cn.srv0.sshinjector.ui.biometric.BiometricAuth.from(it)
        }

    LaunchedEffect(servers, connectingServerId) {
        if (connectingServerId != null) {
            val server = servers.find { it.id == connectingServerId }
            if (server?.isActive == true) {
                viewModel.clearConnecting()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddServer) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.server_add),
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
        if (servers.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.server_no_servers),
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.server_add_hint),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        isConnecting = connectingServerId == server.id,
                        onEdit = { onEditServer(server.id) },
                        onConnect = {
                            if (server.isActive || connectingServerId == server.id) {
                                viewModel.disconnect()
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.dashboard_disconnecting),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                val onGranted = {
                                    viewModel.connect(server.id)
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.dashboard_connecting_to,
                                        ).format(
                                            server.name,
                                        ),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                if (fragmentActivity != null && biometricAuth != null) {
                                    biometricAuth.connectIfAllowed(
                                        fragmentActivity,
                                        server.keyAlias,
                                        onGranted,
                                    )
                                } else {
                                    onGranted()
                                }
                            }
                        },
                        onToggleDefault = {
                            viewModel.toggleDefault(server.id)
                            android.widget.Toast.makeText(
                                context,
                                if (server.isActive) {
                                    context.getString(R.string.server_default_unset)
                                } else {
                                    context.getString(R.string.server_default_set)
                                },
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ServerCard(
    server: ServerEntity,
    isConnecting: Boolean = false,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onToggleDefault: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (server.isActive || isConnecting) 4.dp else 1.dp,
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (server.isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        onClick = onEdit,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(onClick = onToggleDefault) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription =
                                if (server.isActive) {
                                    stringResource(R.string.dashboard_unset_default)
                                } else {
                                    stringResource(R.string.dashboard_set_default)
                                },
                            modifier = Modifier.size(24.dp),
                            tint =
                                if (server.isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (server.isActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        Text(
                            text = "${server.username}@${server.host}:${server.port}",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (server.isActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
                if (server.isActive) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.server_current),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                IconButton(onClick = onConnect) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            if (server.isActive) {
                                Icons.Default.Close
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription =
                                if (server.isActive) {
                                    stringResource(R.string.server_disconnect)
                                } else {
                                    stringResource(R.string.server_connect)
                                },
                            tint =
                                if (server.isActive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text =
                    stringResource(
                        R.string.server_mtu_info,
                        server.mtu,
                        server.keepAliveInterval,
                        if (server.enableIPv6) {
                            stringResource(R.string.server_ipv6_on)
                        } else {
                            stringResource(R.string.server_ipv6_off)
                        },
                    ),
                fontSize = 12.sp,
                color =
                    if (server.isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}
