package cn.srv0.sshinjector.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import cn.srv0.sshinjector.domain.vpn.DomainStatSnapshot
import cn.srv0.sshinjector.domain.vpn.DomainSuffixSet
import cn.srv0.sshinjector.domain.vpn.SuggestionDirection
import cn.srv0.sshinjector.domain.vpn.SuggestionTarget
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.domain.vpn.routing.RoutingSuggestion
import cn.srv0.sshinjector.domain.vpn.routing.RoutingSuggestionReason
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.component.formatTime
import cn.srv0.sshinjector.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 域名访问统计 (P0 只读展示 + P1 名单动作入口): 本会话访问过的域名 / 真实 IP / 连接数 / 上下行 /
 * 最近访问 / 端口, 支持搜索 (300ms debounce)、排序、筛选、单域名手动解析真实 IP、导出 CSV、清空。
 *
 * P1: 每个**域名**行右侧两个 chip「广告」「代理」——点击只是**进 / 出待确认建议列表**,
 * 不直接改任何名单; 复核页 (onOpenSuggestions) 里勾选后统一应用并可撤销。
 *
 * 排序/筛选/快照/名单成员判定全在 ViewModel 的 `Dispatchers.Default` 里做; 本文件只做展示与交互分发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainStatsScreen(
    onBack: () -> Unit = {},
    onOpenSuggestions: () -> Unit = {},
    viewModel: DomainStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    // 一次性结果文案在组合作用域解析 (lint: 不允许用 LocalContext 查资源)
    val msgCleared = stringResource(R.string.domain_stats_cleared)
    val msgExportOk = stringResource(R.string.domain_stats_export_ok)
    val msgExportFailed = stringResource(R.string.domain_stats_export_failed)
    val msgResolveFailed = stringResource(R.string.domain_stats_resolve_failed)
    val msgIpUnavailable = stringResource(R.string.domain_stats_ip_unavailable)
    val msgRemoteUnavailable = stringResource(R.string.domain_stats_remote_unavailable)

    // 路由 (一键出口) 结果: 走 Snackbar 的一半带「撤销」, 另一半只是失败提示
    val routingApplied = stringResource(R.string.domain_routing_applied)
    val routingAppliedDisabled = stringResource(R.string.domain_routing_applied_disabled)
    val routingFailed = stringResource(R.string.domain_routing_failed)
    val routingFailedApply = stringResource(R.string.domain_routing_failed_apply)
    val routingUndo = stringResource(R.string.domain_routing_undo)

    LaunchedEffect(state.routingMessage) {
        val code = state.routingMessage ?: return@LaunchedEffect
        val text =
            when (code) {
                DomainStatsViewModel.MSG_ROUTING_APPLIED -> routingApplied
                DomainStatsViewModel.MSG_ROUTING_APPLIED_DISABLED -> routingAppliedDisabled
                DomainStatsViewModel.MSG_ROUTING_FAILED -> routingFailed
                DomainStatsViewModel.MSG_ROUTING_FAILED_APPLY -> routingFailedApply
                else -> null
            }
        if (text == null) {
            viewModel.consumeRoutingMessage()
            return@LaunchedEffect
        }
        val canUndo =
            code == DomainStatsViewModel.MSG_ROUTING_APPLIED ||
                code == DomainStatsViewModel.MSG_ROUTING_APPLIED_DISABLED
        val result =
            snackbarHost.showSnackbar(
                message = text,
                actionLabel = if (canUndo && state.routingCanUndo) routingUndo else null,
                duration = SnackbarDuration.Long,
            )
        viewModel.consumeRoutingMessage()
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRouting()
    }

    LaunchedEffect(state.message) {
        val code = state.message ?: return@LaunchedEffect
        val text =
            when (code) {
                DomainStatsViewModel.MSG_CLEARED -> msgCleared
                DomainStatsViewModel.MSG_EXPORT_OK -> msgExportOk
                DomainStatsViewModel.MSG_EXPORT_FAILED -> msgExportFailed
                DomainStatsViewModel.MSG_RESOLVE_FAILED -> msgResolveFailed
                DomainStatsViewModel.MSG_IP_UNAVAILABLE -> msgIpUnavailable
                DomainStatsViewModel.MSG_REMOTE_UNAVAILABLE -> msgRemoteUnavailable
                else -> null
            }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    // 「UID 归属探针」结果: 弹一个**可复制**的弹窗 —— 无 adb 的现场, 用户要能把结论贴出来回报
    // (原来是 Toast: 既不能选也不能复制, 且弹一次就被消费掉 —— 真机反馈"且无法复制")。
    // 关闭弹窗时才消费, 避免每次重组重弹。
    val probeResult = state.appUidProbe
    val probeFormat = stringResource(R.string.domain_stats_app_uid_probe_result)
    if (probeResult != null) {
        val probeText =
            buildString {
                append(
                    probeFormat.format(
                        probeResult.ownerUid.name,
                        if (probeResult.uidLabelResolvable) "ok" else "n/a",
                    ),
                )
                // 成因 + 数据面命中率: 环回探针的 NO_OWNER 是预期结果, 判定 ④ 是否可用看后面这行
                if (probeResult.detail.isNotEmpty()) append("\n").append(probeResult.detail)
                if (probeResult.dataPlane.isNotEmpty()) append("\n").append(probeResult.dataPlane)
            }
        AppUidProbeDialog(text = probeText, onDismiss = { viewModel.consumeAppUidProbe() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.domain_stats_title)) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.settings_ad_block_refresh_now),
                        )
                    }
                    IconButton(onClick = { scope.launch { viewModel.exportCsv(context) } }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.domain_stats_export),
                        )
                    }
                    IconButton(onClick = { viewModel.probeAppUid() }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = stringResource(R.string.domain_stats_app_uid_probe),
                        )
                    }
                    IconButton(
                        onClick = {
                            expanded = null
                            viewModel.clear()
                        },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.domain_stats_clear),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (state.pendingCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.domain_stats_pending_bar, state.pendingCount),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenSuggestions) {
                        Text(stringResource(R.string.domain_stats_view_pending), fontSize = 13.sp)
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            // 远端解析开关 (设计 §9.2 顶部开关区; 默认关): 打开后"解析真实 IP"走池内会话 exec
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.domain_stats_use_remote_resolve),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.useRemoteResolve,
                    onCheckedChange = { viewModel.setUseRemoteResolve(it) },
                )
            }
            if (state.useRemoteResolve && state.remoteUnavailable) {
                Text(
                    text = stringResource(R.string.domain_stats_remote_unavailable),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = { Text(stringResource(R.string.domain_stats_search_hint)) },
                singleLine = true,
                maxLines = 1,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            ChipRow {
                DomainStatsSort.entries.forEach { sort ->
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { viewModel.setSort(sort) },
                        label = { Text(sortLabel(sort), fontSize = 12.sp) },
                    )
                }
            }

            ChipRow {
                DomainStatsFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filterLabel(filter), fontSize = 12.sp) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.domain_stats_count, state.total, state.cap, state.trimmed),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (state.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.domain_stats_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    // 列表首项: 路由建议 (失败多 / 下行大且未固定出口的域名) —— 非空才出现
                    if (state.routingSuggestions.isNotEmpty()) {
                        item(key = ROUTING_SUGGESTIONS_KEY) {
                            RoutingSuggestionCard(
                                suggestions = state.routingSuggestions,
                                exits = state.routingExits,
                                onApply = { domain, exitId -> viewModel.applySuggestion(domain, exitId) },
                            )
                        }
                    }
                    items(state.rows, key = { it.domain }) { row ->
                        DomainStatCard(
                            row = row,
                            marks = state.marks[DomainSuffixSet.normalize(row.domain)],
                            appLabels = state.appLabels,
                            exitId = state.domainExits[row.domain] ?: ExitId.DEFAULT,
                            exits = state.routingExits,
                            expanded = expanded == row.domain,
                            resolving = state.resolving == row.domain,
                            onToggle = {
                                expanded = if (expanded == row.domain) null else row.domain
                            },
                            onResolve = { viewModel.resolveRealIp(row.domain) },
                            onToggleChip = { target -> viewModel.toggleSuggestion(row.domain, target) },
                            onSelectExit = { exitId -> viewModel.setDomainExit(row.domain, exitId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun sortLabel(sort: DomainStatsSort): String =
    when (sort) {
        DomainStatsSort.CONNECTIONS -> stringResource(R.string.domain_stats_sort_connections)
        DomainStatsSort.TRAFFIC -> stringResource(R.string.domain_stats_sort_traffic)
        DomainStatsSort.RECENT -> stringResource(R.string.domain_stats_sort_recent)
        DomainStatsSort.DOMAIN -> stringResource(R.string.domain_stats_sort_domain)
    }

@Composable
private fun filterLabel(filter: DomainStatsFilter): String =
    when (filter) {
        DomainStatsFilter.ALL -> stringResource(R.string.domain_stats_filter_all)
        DomainStatsFilter.WITH_REAL_IP -> stringResource(R.string.domain_stats_filter_with_ip)
        DomainStatsFilter.UNKNOWN_IP -> stringResource(R.string.domain_stats_filter_unknown_ip)
        DomainStatsFilter.FAILED -> stringResource(R.string.domain_stats_filter_failed)
    }

@Composable
private fun DomainStatCard(
    row: DomainStatSnapshot,
    marks: RowListMarks?,
    appLabels: Map<Int, String>,
    exitId: String,
    exits: List<String>,
    expanded: Boolean,
    resolving: Boolean,
    onToggle: () -> Unit,
    onResolve: () -> Unit,
    onToggleChip: (SuggestionTarget) -> Unit,
    onSelectExit: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.domain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = ipText(row),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // IP 字面量条目没有域名语义 (key 就是 "ip:port"), 不给名单 chip / 出口 chip
        if (!row.isIpLiteral) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                ListChip(
                    label = stringResource(R.string.domain_stats_chip_adblock),
                    inList = marks?.inAdBlock,
                    pending = marks?.adPending,
                    onClick = { onToggleChip(SuggestionTarget.AD_BLOCK) },
                )
                ListChip(
                    label = stringResource(R.string.domain_stats_chip_domainlist),
                    inList = marks?.inDomainList,
                    pending = marks?.listPending,
                    onClick = { onToggleChip(SuggestionTarget.DOMAIN_LIST) },
                )
                // 一键固定出口: 选中即写规则文本 (Snackbar 可撤销); 主出口显示本地化的「主出口」
                ExitPicker(
                    label = exitChipLabel(exitId),
                    exits = exits,
                    onSelect = onSelectExit,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = summaryText(row, appLabels),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            DetailContent(row = row, appLabels = appLabels, resolving = resolving, onResolve = onResolve)
        }
    }
}

/**
 * 「路由建议」卡片 (列表首项): 失败多 / 下行大的域名 + 原因 + 「应用」出口下拉。
 *
 * 应用与行内保存走**同一条** `upsertDomain` 路径 (Snackbar 可撤销) —— 建议只是入口, 不是第二套写逻辑。
 */
@Composable
private fun RoutingSuggestionCard(
    suggestions: List<RoutingSuggestion>,
    exits: List<String>,
    onApply: (String, String) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.domain_routing_suggestions),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            suggestions.forEach { suggestion ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = suggestion.domain,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = suggestionReason(suggestion),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ExitPicker(
                        label = stringResource(R.string.domain_routing_apply),
                        exits = exits,
                        onSelect = { exitId -> onApply(suggestion.domain, exitId) },
                    )
                }
            }
        }
    }
}

/** 建议原因: 失败 N 次 / 下行 X (由 `RoutingSuggestions.pick` 的枚举决定, 不做字符串判断)。 */
@Composable
private fun suggestionReason(suggestion: RoutingSuggestion): String =
    when (suggestion.reason) {
        RoutingSuggestionReason.FAILED ->
            stringResource(R.string.domain_routing_suggestion_failed, suggestion.failedConnCount)
        RoutingSuggestionReason.TRAFFIC ->
            stringResource(
                R.string.domain_routing_suggestion_traffic,
                MainViewModel.formatBytes(suggestion.downBytes),
            )
    }

/** 出口 chip 文案: `出口: <name>` (主出口显示本地化「主出口」, 附加出口就是规则里的名字)。 */
@Composable
private fun exitChipLabel(exitId: String): String =
    stringResource(R.string.domain_routing_exit) + ": " +
        exitDisplayName(exitId)

@Composable
private fun exitDisplayName(exitId: String): String =
    if (exitId == ExitId.DEFAULT) {
        stringResource(R.string.domain_routing_main_exit)
    } else {
        exitId
    }

/**
 * 出口下拉 (行内 chip 与建议卡片共用)。
 *
 * 点击才展开 —— LazyColumn 里 20000 行也只给**可见行**各持一个 `mutableStateOf`, 不做全量预计算。
 */
@Composable
private fun ExitPicker(
    label: String,
    exits: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = open,
            onClick = { open = true },
            label = { Text(label, fontSize = 11.sp) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                text = stringResource(R.string.domain_routing_pick_exit),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            exits.forEach { exitId ->
                DropdownMenuItem(
                    text = { Text(exitDisplayName(exitId), fontSize = 13.sp) },
                    onClick = {
                        open = false
                        onSelect(exitId)
                    },
                )
            }
        }
    }
}

/**
 * 名单 chip: 点一下进 / 出「待确认建议」。
 *
 * 视觉语言: 实心 = 已在名单或已挂待确认; 标签后缀 `+` / `−` = 待确认方向 (加入 / 移出),
 * `?` = 成员关系未知 (超预算 / 该行还没问过)。
 */
@Composable
private fun ListChip(
    label: String,
    inList: Boolean?,
    pending: SuggestionDirection?,
    onClick: () -> Unit,
) {
    val suffix =
        when {
            pending == SuggestionDirection.ADD -> " +"
            pending == SuggestionDirection.REMOVE -> " −"
            inList == null -> " ?"
            else -> ""
        }
    FilterChip(
        selected = pending != null || inList == true,
        onClick = onClick,
        label = { Text(label + suffix, fontSize = 11.sp) },
    )
}

/** 摘要行: `连接数 N · 上行 x · 下行 y · :port · 最近 <time> [· 应用 A, B]`。 */
@Composable
private fun summaryText(
    row: DomainStatSnapshot,
    appLabels: Map<Int, String>,
): String {
    val connections = stringResource(R.string.domain_stats_sort_connections)
    val up = stringResource(R.string.domain_stats_label_up)
    val down = stringResource(R.string.domain_stats_label_down)
    val recent = stringResource(R.string.domain_stats_sort_recent)
    val base =
        "$connections ${row.connCount} · $up ${MainViewModel.formatBytes(row.upBytes)} · " +
            "$down ${MainViewModel.formatBytes(row.downBytes)} · :${row.lastPort} · " +
            "$recent ${formatTime(row.lastSeenAt)}"
    val apps = appText(row, appLabels)
    return if (apps.isEmpty()) base else "$base · ${stringResource(R.string.domain_stats_label_apps)} $apps"
}

/**
 * 「应用」文本 (④ 按应用归属): 已知归属的应用名; 空串 = 尚未反查到 —— 折叠行**不显示占位**
 * (多数行会是空的, 占位只会变噪音), 详情里才用 `—` 明说"未知"。
 *
 * uid 解析不到名字 (已卸载 / 共享 uid / 超出本次解析预算) 时退化成 `uid N`: 机器事实比空白更有
 * 诊断价值 (真机排障时能直接看出"反查到了但认不出名字"还是"根本没反查到")。
 */
private fun appText(
    row: DomainStatSnapshot,
    labels: Map<Int, String>,
): String =
    listOf(row.appUid1, row.appUid2, row.appUid3)
        .filter { it >= 0 }
        .joinToString(", ") { labels[it] ?: "uid $it" }

/**
 * 真实 IP 展示 (设计 §7.3): 条目只留两槽, 多个地址时显示首个 + `+N` —— 让"截断"看得见。
 * `+N` 是语言无关的数字后缀, 不进 i18n。
 */
private fun ipText(row: DomainStatSnapshot): String {
    val first = row.realIp ?: return EMPTY_IP
    return if (row.realIpTotal > 1) "$first +${row.realIpTotal - 1}" else first
}

@Composable
private fun DetailContent(
    row: DomainStatSnapshot,
    appLabels: Map<Int, String>,
    resolving: Boolean,
    onResolve: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DetailLine(label = "IP", value = ipText(row))
        DetailLine(label = "IP2", value = row.realIp2 ?: EMPTY_IP)
        DetailLine(
            label = stringResource(R.string.domain_stats_label_apps),
            value = appText(row, appLabels).ifEmpty { EMPTY_IP },
        )
        DetailLine(
            label = stringResource(R.string.domain_stats_label_failed),
            value = row.failedConnCount.toString(),
        )
        DetailLine(label = stringResource(R.string.domain_stats_label_first), value = formatTime(row.firstSeenAt))
        DetailLine(label = stringResource(R.string.domain_stats_label_last), value = formatTime(row.lastSeenAt))
        // IP 字面量条目 (没有域名映射, key 就是 "ip:port") 不给解析入口: 解析的是域名, 点了必然空等超时
        if (!row.isIpLiteral) {
            TextButton(onClick = onResolve, enabled = !resolving) {
                Text(
                    text =
                        if (resolving) {
                            stringResource(R.string.domain_stats_resolving)
                        } else {
                            stringResource(R.string.domain_stats_resolve_ip)
                        },
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「UID 归属探针」结果弹窗: 文本**能选中也能一键复制** —— 构建机无 adb 时, 这是用户把结论带出来的
 * 唯一途径 (原来的 Toast 既不能选也不能复制, 弹完即消失)。
 */
@Composable
private fun AppUidProbeDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.copy)
    val closeLabel = stringResource(R.string.close)
    val copiedLabel = stringResource(R.string.domain_stats_app_uid_probe_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.domain_stats_app_uid_probe)) },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 走平台剪贴板 (与日志页/密钥页同一姿势), 不用已废弃的 LocalClipboardManager
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                    Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                },
            ) {
                Text(copyLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(closeLabel) }
        },
    )
}

/** 无真实 IP 时的占位符。 */
private const val EMPTY_IP = "—"

/** LazyColumn 里「路由建议」卡片的固定 key (与行 key 的空间隔开, 域名不会等于该值)。 */
private const val ROUTING_SUGGESTIONS_KEY = "routing-suggestions"
