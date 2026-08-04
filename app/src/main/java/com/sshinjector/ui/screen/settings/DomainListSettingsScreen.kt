package com.sshinjector.ui.screen.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.sshinjector.data.local.DomainListSource
import com.sshinjector.data.local.DomainListState
import com.sshinjector.data.local.preferences.SettingsDataStore
import com.sshinjector.ui.component.rememberClickGuard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainListSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DomainListSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val domainListUrl by viewModel.domainListUrl.collectAsState()
    var urlInput by remember { mutableStateOf(domainListUrl) }
    val guard = rememberClickGuard()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("域名列表") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("列表来源 URL", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "支持 gfwlist(base64) 或纯文本格式，每行一条规则。" +
                            "仅在「域名分流」连接模式下生效。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("URL") }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { guard { viewModel.setDomainListUrl(urlInput.trim()) } },
                            modifier = Modifier.weight(1f)
                        ) { Text("保存 URL") }
                        TextButton(onClick = {
                            urlInput = SettingsDataStore.DEFAULT_DOMAIN_LIST_URL
                            viewModel.resetToDefault()
                        }) { Text("恢复默认") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("列表状态", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    when (val s = state) {
                        is DomainListState.Idle -> StatusRow("状态", "未加载")
                        is DomainListState.Loading -> StatusRow("状态", "更新中...")
                        is DomainListState.Ready -> {
                            StatusRow("状态", "已加载")
                            StatusRow("规则数量", s.matcher.ruleCount.toString())
                            StatusRow("来源", when (s.source) {
                                DomainListSource.BUILTIN -> "内置默认"
                                DomainListSource.DISK -> "本地缓存"
                                DomainListSource.URL -> "远程下载"
                            })
                            StatusRow("更新时间", s.updatedAt?.let(::formatTime) ?: "未知")
                        }
                        is DomainListState.Error -> {
                            StatusRow("状态", "更新失败")
                            StatusRow("错误", s.message)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { guard { viewModel.updateList() } },
                        enabled = state !is DomainListState.Loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state is DomainListState.Loading) "更新中..." else "立即更新") }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
