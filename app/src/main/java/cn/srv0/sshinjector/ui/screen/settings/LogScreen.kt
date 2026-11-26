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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.theme.extendedColors
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志行尾部"出口"按钮的高度: 压到接近单行文本, 否则每条可路由日志都把列表撑成 40dp 行距。 */
private val EXIT_BUTTON_HEIGHT = 26.dp

/**
 * 日志查看: 按级别过滤、复制、导出 (下载目录)、清空, 以及对带域名的行"一键固定出口"。
 * 内容为 VpnController.addLog 产生的应用日志 (最近 2000 条)。
 *
 * 复制/导出**永远导出全量条目** (级别筛选只影响列表展示): 「故障归因」等结论行是 INFO 级,
 * 跟随筛选导出会让证据与结论对不上 (无 adb 现场全靠这份文件判读)。
 * 新增的"出口"按钮只改路由规则的**存储**, 不影响日志内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val exits by viewModel.exits.collectAsState()
    val routingMessage by viewModel.routingMessage.collectAsState()
    val routingCanUndo by viewModel.routingCanUndo.collectAsState()
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    // 默认隐藏 DEBUG (每包/内部机制噪音): 点 DEBUG chip 自动放行, 点"全部"复位
    var showDebug by remember { mutableStateOf(false) }
    // 正在选出口的域名 (null = 对话框关闭): 全页共用一个 AlertDialog, 不为每行各建一个
    var routingDomain by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
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
    // 复制/导出走**全量**条目 (不跟随级别筛选): 「故障归因」是 INFO 级, 按级别筛掉后
    // 导出的证据与结论对不上 —— 那是无 adb 现场唯一的判据来源。
    val msgCopied = stringResource(R.string.log_copied, entries.size)
    val msgExportTemplate = stringResource(R.string.log_export_done) // 含 %1$s 占位, 点击时替换文件名
    val msgExportFailed = stringResource(R.string.log_export_failed)
    // 路由结果文案: 含 %1$s (域名) 占位, 由 Snackbar 侧 format
    val routingAppliedTemplate = stringResource(R.string.log_routing_applied)
    val routingDisabledTemplate = stringResource(R.string.log_routing_applied_disabled)
    val routingFailedTemplate = stringResource(R.string.log_routing_failed)
    val routingUndoLabel = stringResource(R.string.log_routing_undo)
    val routingMainExitLabel = stringResource(R.string.log_routing_main_exit)

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun exportableText(): String? {
        if (entries.isEmpty()) return null
        return viewModel.formatEntries(entries, lineFormat)
    }

    // 路由结果统一走 Snackbar (带撤销) —— 撤销必须能点, Toast 承载不了操作
    LaunchedEffect(routingMessage) {
        val message = routingMessage ?: return@LaunchedEffect
        val text =
            when (message.outcome) {
                LogRoutingOutcome.APPLIED -> routingAppliedTemplate.format(message.domain)
                LogRoutingOutcome.APPLIED_DISABLED -> routingDisabledTemplate.format(message.domain)
                LogRoutingOutcome.FAILED -> routingFailedTemplate.format(message.domain)
            }
        val result =
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = if (routingCanUndo) routingUndoLabel else null,
                duration = SnackbarDuration.Long,
            )
        viewModel.consumeRoutingMessage()
        if (result == SnackbarResult.ActionPerformed) viewModel.undo()
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        label = { Text(stringResource(level.labelRes), fontSize = 12.sp) },
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
                        val domain = entry.domain
                        // 原来是整行一个 Text; 改成 Row 后仍保留 monospace/级别颜色/自动换行:
                        // 文本占余下宽度 (weight(1f)), 按钮只在"有域名且存在可选出口"时出现
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text =
                                    "${lineFormat.format(Date(entry.timestamp))} " +
                                        "[${entry.level.name}] ${entry.message}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = logLevelColor(entry.level),
                                modifier = Modifier.weight(1f),
                            )
                            if (domain != null && exits.isNotEmpty()) {
                                TextButton(
                                    onClick = { routingDomain = domain },
                                    modifier = Modifier.height(EXIT_BUTTON_HEIGHT),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.log_routing_action),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 统一个出口选择对话框: default 显示为"主出口", 其余显示出口名
    val domainForDialog = routingDomain
    if (domainForDialog != null) {
        AlertDialog(
            onDismissRequest = { routingDomain = null },
            title = {
                Text(
                    text = stringResource(R.string.log_routing_title, domainForDialog),
                    fontSize = 15.sp,
                )
            },
            text = {
                // 出口数量由用户配置 (有限), 滚动兜底避免对话框超出屏幕
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    exits.forEach { exit ->
                        TextButton(
                            onClick = {
                                routingDomain = null
                                viewModel.applyDomain(domainForDialog, exit)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = if (exit == ExitId.DEFAULT) routingMainExitLabel else exit,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { routingDomain = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
