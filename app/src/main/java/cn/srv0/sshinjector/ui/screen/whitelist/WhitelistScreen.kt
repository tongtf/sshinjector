package cn.srv0.sshinjector.ui.screen.whitelist

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.ui.component.rememberClickGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WhitelistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectAll by remember { mutableStateOf(false) }
    var hideSystemApps by remember { mutableStateOf(true) }
    var hasPermission by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var filterMode by remember { mutableIntStateOf(0) }

    val enabledPackages by viewModel.enabledPackages.collectAsState()
    val allApps by viewModel.cachedApps.collectAsState()
    val loaded by viewModel.appsLoaded.collectAsState()

    fun checkPermission(): Boolean {
        return try {
            val apps =
                context.packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            apps.isNotEmpty() || true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    fun openAppSettings() {
        try {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .apply { data = Uri.parse("package:${context.packageName}") }
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun loadApps(forceRefresh: Boolean = false) {
        if (!checkPermission()) {
            showPermissionDialog = true
            return
        }
        viewModel.loadApps(forceRefresh)
    }

    LaunchedEffect(Unit) {
        hasPermission = checkPermission()
        if (hasPermission) {
            loadApps()
        }
    }

    val filteredApps by remember(
        allApps,
        searchQuery,
        hideSystemApps,
        filterMode,
        enabledPackages,
    ) {
        derivedStateOf {
            var list =
                if (searchQuery.isBlank()) {
                    allApps
                } else {
                    allApps.filter {
                        it.name.contains(searchQuery, ignoreCase = true)
                    }
                }
            if (hideSystemApps) list = list.filter { !it.isSystem }
            when (filterMode) {
                1 -> list = list.filter { it.packageName in enabledPackages }
                2 -> list = list.filter { it.isSystem }
            }
            list
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (!isSearchActive) {
                        Text(stringResource(R.string.whitelist_title))
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    stringResource(
                                        R.string.whitelist_search_hint,
                                    ),
                                )
                            },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription =
                                        stringResource(
                                            R.string.search,
                                        ),
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearchActive = false
                                }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription =
                                            stringResource(
                                                R.string.close,
                                            ),
                                    )
                                }
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription =
                                    stringResource(
                                        R.string.whitelist_search,
                                    ),
                            )
                        }
                    }
                    IconButton(onClick = {
                        viewModel.refreshApps()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription =
                                stringResource(
                                    R.string.whitelist_refresh,
                                ),
                        )
                    }
                    IconButton(onClick = {
                        hideSystemApps = !hideSystemApps
                    }) {
                        Text(
                            text =
                                if (hideSystemApps) {
                                    stringResource(R.string.whitelist_show_system)
                                } else {
                                    stringResource(R.string.whitelist_hide_system)
                                },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!searchQuery.isBlank()) {
                        IconButton(onClick = {
                            selectAll = !selectAll
                        }) {
                            Icon(
                                imageVector =
                                    if (selectAll) {
                                        Icons.Default.Check
                                    } else {
                                        Icons.Default.Done
                                    },
                                contentDescription =
                                    if (selectAll) {
                                        stringResource(R.string.whitelist_select_all)
                                    } else {
                                        stringResource(R.string.whitelist_deselect_all)
                                    },
                            )
                        }
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
                    .padding(innerPadding),
        ) {
            if (!hasPermission) {
                PermissionRequestScreen(
                    onOpenSettings = { openAppSettings() },
                    onRefresh = {
                        hasPermission = checkPermission()
                        if (hasPermission) loadApps()
                    },
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.whitelist_banner),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = filterMode == 0,
                            onClick = { filterMode = 0 },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.whitelist_filter_all,
                                        filteredApps.size,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = filterMode == 1,
                            onClick = { filterMode = 1 },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.whitelist_filter_selected,
                                        enabledPackages.size,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = filterMode == 2,
                            onClick = { filterMode = 2 },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.whitelist_filter_system,
                                        filteredApps.count { it.isSystem },
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (!loaded) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.whitelist_loading),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppListItem(
                                app = app,
                                isEnabled = app.packageName in enabledPackages,
                                onToggle = { pkg, enabled ->
                                    viewModel.togglePackage(
                                        pkg,
                                        app.name,
                                        enabled,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    stringResource(
                        R.string.whitelist_permission_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.whitelist_permission_msg,
                    ),
                )
            },
            confirmButton = {
                val guard = rememberClickGuard()
                Button(onClick = {
                    guard {
                        showPermissionDialog = false
                        openAppSettings()
                    }
                }) {
                    Text(stringResource(R.string.whitelist_permission_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun PermissionRequestScreen(
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.whitelist_need_permission),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.whitelist_need_permission_desc),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.whitelist_need_permission_hint),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        val guard = rememberClickGuard()
        Button(
            onClick = { guard { onOpenSettings() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.whitelist_goto_settings),
                fontSize = 16.sp,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = onRefresh,
        ) {
            Text(
                stringResource(R.string.whitelist_have_permission_refresh),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
fun AppListItem(
    app: InstalledApp,
    isEnabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    val context2 = LocalContext.current
    val iconBitmap by produceState<
        androidx.compose.ui.graphics.ImageBitmap?,
        >(null, app.packageName) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val d =
                        context2.packageManager.getApplicationIcon(
                            app.packageName,
                        )
                    val w = d.intrinsicWidth.coerceAtLeast(1)
                    val h = d.intrinsicHeight.coerceAtLeast(1)
                    val bmp =
                        android.graphics.Bitmap.createBitmap(
                            w, h, android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    val canvas = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, w, h)
                    d.draw(canvas)
                    bmp.asImageBitmap()
                }.getOrNull()
            }
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = iconBitmap
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = app.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = app.name.first().toString().uppercase(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color =
                            if (isEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    if (app.isSystem) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text =
                                stringResource(
                                    R.string.whitelist_system_tag,
                                ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                alpha = 0.7f,
                            )
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = {
                        enabled ->
                    onToggle(app.packageName, enabled)
                },
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor =
                            MaterialTheme
                                .colorScheme.primaryContainer,
                    ),
            )
        }
    }
}
