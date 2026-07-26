package com.sshinjector.ui.screen.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: RouteSettingsViewModel = hiltViewModel(),
) {
    val installedApps by viewModel.installedApps.collectAsState()
    val tagTunnels by viewModel.tagTunnels.collectAsState()
    val defaultTunnel by viewModel.defaultTunnel.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val plugins = viewModel.availablePlugins

    var showAppDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saved) {
        if (saved) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("路由规则") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Default.Check, contentDescription = "保存", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("保存", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("默认隧道", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        DefaultTunnelSelector(
                            selectedId = defaultTunnel,
                            plugins = plugins,
                            onSelect = { viewModel.setDefaultTunnel(it) }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("标签 → 隧道映射", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showTagDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "添加映射", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        val entries = viewModel.getTagTunnels()
                        if (entries.isEmpty()) {
                            Text("暂无映射", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            entries.forEach { (tag, tunnelId) ->
                                val plugin = plugins.find { it.id == tunnelId }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("标签: $tag", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text("隧道: ${plugin?.displayName ?: tunnelId}", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { showDeleteConfirm = tag }) {
                                        Icon(Icons.Default.Close, contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("应用标签", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showAppDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "添加应用标签", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        val appTagEntries = viewModel.getAppTagsList()
                        if (appTagEntries.isEmpty()) {
                            Text("暂无应用标签", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            appTagEntries.forEach { (pkg, name, tags) ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                Text(pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { viewModel.setAppTags(pkg, emptySet()) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "删除标签",
                                                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            tags.forEach { tag ->
                                                Text(
                                                    text = tag,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 8.dp, vertical = 2.dp)
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
    }

    if (showTagDialog) {
        AddTagTunnelDialog(
            plugins = plugins,
            existingTags = tagTunnels.keys,
            onConfirm = { tag, tunnelId ->
                viewModel.setTagTunnel(tag, tunnelId)
                showTagDialog = false
            },
            onDismiss = { showTagDialog = false }
        )
    }

    if (showAppDialog) {
        AddAppTagDialog(
            apps = installedApps,
            existingTags = tagTunnels.keys,
            onConfirm = { pkg, tags ->
                viewModel.addAppTag(pkg, tags.first())
                showAppDialog = false
            },
            onDismiss = { showAppDialog = false }
        )
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除映射") },
            text = { Text("确定删除标签「${showDeleteConfirm}」的隧道映射？") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeTagTunnel(showDeleteConfirm!!)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun DefaultTunnelSelector(
    selectedId: String,
    plugins: List<TunnelPlugin>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = plugins.find { it.id == selectedId }

    Box {
        OutlinedTextField(
            value = selected?.displayName ?: selectedId,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            label = { Text("默认隧道") }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            plugins.forEach { plugin ->
                DropdownMenuItem(
                    text = { Text(plugin.displayName) },
                    onClick = { onSelect(plugin.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun AddTagTunnelDialog(
    plugins: List<TunnelPlugin>,
    existingTags: Set<String>,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    var selectedTunnelId by remember { mutableStateOf(plugins.firstOrNull()?.id ?: "socks5") }
    var tunnelExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加标签映射") },
        text = {
            Column {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("标签名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = tag.isBlank() || existingTags.contains(tag)
                )
                if (existingTags.contains(tag)) {
                    Text("标签已存在", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Box {
                    val selected = plugins.find { it.id == selectedTunnelId }
                    OutlinedTextField(
                        value = selected?.displayName ?: selectedTunnelId,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().clickable { tunnelExpanded = true },
                        label = { Text("隧道") }
                    )
                    DropdownMenu(expanded = tunnelExpanded, onDismissRequest = { tunnelExpanded = false }) {
                        plugins.forEach { plugin ->
                            DropdownMenuItem(
                                text = { Text(plugin.displayName) },
                                onClick = { selectedTunnelId = plugin.id; tunnelExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tag, selectedTunnelId) },
                enabled = tag.isNotBlank() && !existingTags.contains(tag)
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddAppTagDialog(
    apps: List<InstalledAppInfo>,
    existingTags: Set<String>,
    onConfirm: (String, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf("") }
    var tagExpanded by remember { mutableStateOf(false) }

    val filteredApps = if (searchQuery.isBlank()) apps
    else apps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为应用添加标签") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索应用") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(filteredApps.take(30)) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedApp = app.packageName }
                                .background(
                                    if (selectedApp == app.packageName) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(app.name, fontSize = 14.sp)
                                Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box {
                    OutlinedTextField(
                        value = selectedTag,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().clickable { tagExpanded = true },
                        label = { Text("标签") }
                    )
                    DropdownMenu(expanded = tagExpanded, onDismissRequest = { tagExpanded = false }) {
                        existingTags.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(tag) },
                                onClick = { selectedTag = tag; tagExpanded = false }
                            )
                        }
                        if (existingTags.isEmpty()) {
                            DropdownMenuItem(text = { Text("暂无标签，请先添加标签→隧道映射") }, onClick = {})
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedApp?.let { onConfirm(it, setOf(selectedTag)) } },
                enabled = selectedApp != null && selectedTag.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
