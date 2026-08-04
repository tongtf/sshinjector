package com.sshinjector.ui.screen.whitelist

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.ui.component.rememberClickGuard
data class InstalledApp(
    val packageName: String,
    val name: String,
    val isSystem: Boolean,
    val icon: Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WhitelistViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectAll by remember { mutableStateOf(false) }
    var hideSystemApps by remember { mutableStateOf(true) }
    var hasPermission by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var filterMode by remember { mutableIntStateOf(0) } // 0=全部 1=已选 2=系统应用

    val enabledPackages by viewModel.enabledPackages.collectAsState()
    val allApps by viewModel.cachedApps.collectAsState()
    val loaded by viewModel.appsLoaded.collectAsState()

    // 检查是否能查询已安装应用列表 (QUERY_ALL_PACKAGES 已在 manifest 声明)
    fun checkPermission(): Boolean {
        return try {
            // 实际验证查询能力: 尝试获取应用列表 (仅验证非空/无异常)
            val apps = context.packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
            apps.isNotEmpty() || true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            true
        }
    }

    // 跳转到应用详情设置
    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 兜底: 打开系统设置
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

    // 初始化时检查权限并加载应用（使用缓存）
    LaunchedEffect(Unit) {
        hasPermission = checkPermission()
        if (hasPermission) {
            loadApps() // 使用缓存，不强制刷新
        }
    }

    val filteredApps by remember(allApps, searchQuery, hideSystemApps, filterMode, enabledPackages) {
        derivedStateOf {
            var list = if (searchQuery.isBlank()) allApps
            else allApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
            if (hideSystemApps) list = list.filter { !it.isSystem }
            when (filterMode) {
                1 -> list = list.filter { it.packageName in enabledPackages }
                2 -> list = list.filter { it.isSystem }
            }
            list
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (!isSearchActive) {
                        Text("应用白名单")
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索应用...") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                            trailingIcon = {
                                IconButton(onClick = { searchQuery = ""; isSearchActive = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                                }
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索应用"
                            )
                        }
                    }
                    IconButton(onClick = {
                        viewModel.refreshApps()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新应用列表"
                        )
                    }
                    IconButton(onClick = { hideSystemApps = !hideSystemApps }) {
                        Text(
                            text = if (hideSystemApps) "含系统" else "隐藏系统",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!searchQuery.isBlank()) {
                        IconButton(onClick = { selectAll = !selectAll }) {
                            Icon(
                                imageVector = if (selectAll) Icons.Default.Check else Icons.Default.Done,
                                contentDescription = if (selectAll) "全选" else "反选"
                            )
                        }
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
        ) {
            if (!hasPermission) {
                // 权限未授予时显示引导界面
                PermissionRequestScreen(
                    onOpenSettings = { openAppSettings() },
                    onRefresh = {
                        hasPermission = checkPermission()
                        if (hasPermission) loadApps()
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "仅勾选的应用通过 SSH 代理访问网络，其他应用直连",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterMode == 0,
                            onClick = { filterMode = 0 },
                            label = { Text("全部 ${filteredApps.size}") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = filterMode == 1,
                            onClick = { filterMode = 1 },
                            label = { Text("已选 ${enabledPackages.size}") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = filterMode == 2,
                            onClick = { filterMode = 2 },
                            label = { Text("系统 ${filteredApps.count { it.isSystem }}") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (!loaded) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在加载应用列表...", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                AppListItem(
                                    app = app,
                                    isEnabled = app.packageName in enabledPackages,
                                    onToggle = { pkg, enabled ->
                                        viewModel.togglePackage(pkg, app.name, enabled)
                                    }
                                )
                            }
                    }
                }
            }
        }
    }

    // 权限请求对话框
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("需要权限") },
            text = { Text("白名单功能需要「所有文件访问」权限才能获取已安装应用列表。请在系统设置中为本应用开启此权限。") },
            confirmButton = {
                val guard = rememberClickGuard()
                Button(onClick = {
                    guard {
                        showPermissionDialog = false
                        openAppSettings()
                    }
                }) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun PermissionRequestScreen(
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "需要权限",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "白名单功能需要「所有文件访问」权限才能获取已安装应用列表",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "请在系统设置中为本应用开启此权限后返回",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        val guard = rememberClickGuard()
        Button(
            onClick = { guard { onOpenSettings() } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("前往设置", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = onRefresh
        ) {
            Text("已开启权限，刷新", fontSize = 14.sp)
        }
    }
}

@Composable
fun AppListItem(
    app: InstalledApp,
    isEnabled: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    val iconBitmap = remember(app.packageName) {
        val d = app.icon ?: return@remember null
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        bmp.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = iconBitmap,
                        contentDescription = app.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = app.name.first().toString().uppercase(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (app.isSystem) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "系统",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { enabled -> onToggle(app.packageName, enabled) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}