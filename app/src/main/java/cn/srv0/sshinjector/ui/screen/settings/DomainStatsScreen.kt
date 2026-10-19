package cn.srv0.sshinjector.ui.screen.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.component.formatTime
import cn.srv0.sshinjector.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 域名访问统计 (P0 只读展示): 本会话访问过的域名 / 真实 IP / 连接数 / 上下行 / 最近访问 / 端口,
 * 支持搜索 (300ms debounce)、排序、筛选、单域名手动解析真实 IP、导出 CSV、清空。
 *
 * 排序/筛选/快照全在 ViewModel 的 `Dispatchers.Default` 里做; 本文件只做展示与交互分发。
 * 不涉及 P1 的「广告」「代理」chip 与待确认建议列表 —— 不做任何名单写入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainStatsScreen(
    onBack: () -> Unit = {},
    viewModel: DomainStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }

    // 一次性结果文案在组合作用域解析 (lint: 不允许用 LocalContext 查资源)
    val msgCleared = stringResource(R.string.domain_stats_cleared)
    val msgExportOk = stringResource(R.string.domain_stats_export_ok)
    val msgExportFailed = stringResource(R.string.domain_stats_export_failed)
    val msgResolveFailed = stringResource(R.string.domain_stats_resolve_failed)
    val msgIpUnavailable = stringResource(R.string.domain_stats_ip_unavailable)

    LaunchedEffect(state.message) {
        val code = state.message ?: return@LaunchedEffect
        val text =
            when (code) {
                DomainStatsViewModel.MSG_CLEARED -> msgCleared
                DomainStatsViewModel.MSG_EXPORT_OK -> msgExportOk
                DomainStatsViewModel.MSG_EXPORT_FAILED -> msgExportFailed
                DomainStatsViewModel.MSG_RESOLVE_FAILED -> msgResolveFailed
                DomainStatsViewModel.MSG_IP_UNAVAILABLE -> msgIpUnavailable
                else -> null
            }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
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
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
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
                    items(state.rows, key = { it.domain }) { row ->
                        DomainStatCard(
                            row = row,
                            expanded = expanded == row.domain,
                            resolving = state.resolving == row.domain,
                            onToggle = {
                                expanded = if (expanded == row.domain) null else row.domain
                            },
                            onResolve = { viewModel.resolveRealIp(row.domain) },
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
    expanded: Boolean,
    resolving: Boolean,
    onToggle: () -> Unit,
    onResolve: () -> Unit,
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
                text = row.realIp ?: EMPTY_IP,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = summaryText(row),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            DetailContent(row = row, resolving = resolving, onResolve = onResolve)
        }
    }
}

/** 摘要行: `连接数 N · 上行 x · 下行 y · :port · 最近 <time>`。 */
@Composable
private fun summaryText(row: DomainStatSnapshot): String {
    val connections = stringResource(R.string.domain_stats_sort_connections)
    val up = stringResource(R.string.domain_stats_label_up)
    val down = stringResource(R.string.domain_stats_label_down)
    val recent = stringResource(R.string.domain_stats_sort_recent)
    return "$connections ${row.connCount} · $up ${MainViewModel.formatBytes(row.upBytes)} · " +
        "$down ${MainViewModel.formatBytes(row.downBytes)} · :${row.lastPort} · " +
        "$recent ${formatTime(row.lastSeenAt)}"
}

@Composable
private fun DetailContent(
    row: DomainStatSnapshot,
    resolving: Boolean,
    onResolve: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DetailLine(label = "IP", value = row.realIp ?: EMPTY_IP)
        DetailLine(label = "IP2", value = row.realIp2 ?: EMPTY_IP)
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

/** 无真实 IP 时的占位符。 */
private const val EMPTY_IP = "—"
