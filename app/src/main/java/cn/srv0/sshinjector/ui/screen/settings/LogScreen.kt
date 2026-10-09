package cn.srv0.sshinjector.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.theme.extendedColors
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志查看: 按级别过滤、复制、导出 (下载目录)、清空。
 * 内容为 VpnController.addLog 产生的应用日志 (最近 2000 条)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    // 默认隐藏 DEBUG (每包/内部机制噪音): 点 DEBUG chip 自动放行, 点"全部"复位
    var showDebug by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lineFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val fileNameFormat = remember { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }

    val visible =
        remember(entries, filter, showDebug) {
            entries.filter { entry ->
                (showDebug || entry.level != LogLevel.DEBUG) && (filter == null || entry.level == filter)
            }
        }
    val display = remember(visible) { visible.asReversed() }

    // 文案在组合作用域解析 (lint: 不允许用 LocalContext 查资源, 配置变更会拿到旧值)
    val msgEmpty = stringResource(R.string.log_empty)
    val msgCleared = stringResource(R.string.log_cleared)
    val msgCopied = stringResource(R.string.log_copied, visible.size)
    val msgExportTemplate = stringResource(R.string.log_export_done) // 含 %1$s 占位, 点击时替换文件名
    val msgExportFailed = stringResource(R.string.log_export_failed)

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun exportableText(): String? {
        if (visible.isEmpty()) return null
        return viewModel.formatEntries(visible, lineFormat)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    BackNavIcon(onNavigateBack)
                },
                actions = {
                    IconButton(
                        onClick = {
                            val text = exportableText()
                            if (text == null) {
                                toast(msgEmpty)
                            } else {
                                viewModel.copyToClipboard(context, text)
                                toast(msgCopied)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.log_copy),
                        )
                    }
                    IconButton(
                        onClick = {
                            val text = exportableText()
                            if (text == null) {
                                toast(msgEmpty)
                            } else {
                                val fileName = "sshinjector-log-${fileNameFormat.format(Date())}.txt"
                                scope.launch {
                                    viewModel
                                        .exportToDownloads(context, fileName, text)
                                        .fold(
                                            onSuccess = { toast(msgExportTemplate.replace("%1\$s", it)) },
                                            onFailure = { toast(msgExportFailed) },
                                        )
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.log_export),
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.clear()
                            toast(msgCleared)
                        },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.log_clear),
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
                    .padding(innerPadding),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = {
                        filter = null
                        showDebug = false
                    },
                    label = { Text(stringResource(R.string.log_all), fontSize = 12.sp) },
                )
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = filter == level,
                        onClick = {
                            filter = level
                            if (level == LogLevel.DEBUG) showDebug = true
                        },
                        label = { Text(level.name, fontSize = 12.sp) },
                    )
                }
            }

            if (display.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(display) { entry ->
                        Text(
                            text =
                                "${lineFormat.format(Date(entry.timestamp))} " +
                                    "[${entry.level.name}] ${entry.message}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = logLevelColor(entry.level),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun logLevelColor(level: LogLevel) =
    when (level) {
        LogLevel.ERROR -> MaterialTheme.extendedColors.statusError
        LogLevel.WARNING -> MaterialTheme.extendedColors.statusWarning
        LogLevel.SUCCESS -> MaterialTheme.extendedColors.statusOk
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
