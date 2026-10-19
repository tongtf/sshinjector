package cn.srv0.sshinjector.ui.screen.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.vpn.Suggestion
import cn.srv0.sshinjector.domain.vpn.SuggestionDirection
import cn.srv0.sshinjector.domain.vpn.SuggestionNote
import cn.srv0.sshinjector.domain.vpn.SuggestionTarget
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.viewmodel.MainViewModel

/**
 * 「待确认建议」复核页 (P1 设计 §9.3): 逐条显示"域名 + 方向 + 目标名单 + 依据", 默认全选,
 * 可单删 / 全部丢弃, 底部「应用 N 条」。
 *
 * 应用后 Snackbar 提供**撤销** (精确回写应用前快照) —— 名单写入是"一次点击就改过滤行为",
 * 没有后悔药的入口不敢给。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionReviewScreen(
    onBack: () -> Unit = {},
    viewModel: SuggestionReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val appliedTemplate = stringResource(R.string.domain_stats_suggestion_applied)
    val undoneText = stringResource(R.string.domain_stats_suggestion_undone)
    val failedText = stringResource(R.string.domain_stats_suggestion_apply_failed)
    val undoLabel = stringResource(R.string.domain_stats_suggestion_undo)

    LaunchedEffect(state.appliedCount) {
        val count = state.appliedCount ?: return@LaunchedEffect
        val result =
            snackbarHost.showSnackbar(
                message = appliedTemplate.format(count),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
        viewModel.consumeTransient()
        if (result == SnackbarResult.ActionPerformed) viewModel.undo()
    }
    LaunchedEffect(state.undone) {
        if (!state.undone) return@LaunchedEffect
        snackbarHost.showSnackbar(undoneText)
        viewModel.consumeTransient()
    }
    LaunchedEffect(state.failed) {
        if (!state.failed) return@LaunchedEffect
        snackbarHost.showSnackbar(failedText)
        viewModel.consumeTransient()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.domain_stats_suggestion_title)) },
                navigationIcon = { BackNavIcon(onBack) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    TextButton(onClick = { viewModel.discardAll() }) {
                        Text(stringResource(R.string.domain_stats_suggestion_discard_all), fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text =
                            stringResource(
                                R.string.domain_stats_suggestion_selected,
                                state.selected.size,
                                state.items.size,
                            ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.applySelected() },
                        enabled = state.selected.isNotEmpty() && !state.applying,
                    ) {
                        Text(
                            text =
                                stringResource(
                                    R.string.domain_stats_suggestion_apply,
                                    state.selected.size,
                                ),
                            fontSize = 13.sp,
                        )
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
            if (state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.domain_stats_suggestion_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.domain_stats_suggestion_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(state.items, key = { it.domain + "/" + it.target.name }) { item ->
                        SuggestionRow(
                            item = item,
                            checked = item.key in state.selected,
                            onCheck = { viewModel.setSelected(item.key, it) },
                            onDiscard = { viewModel.discard(item.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    item: Suggestion,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onCheck(!checked) }
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.domain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text =
                    directionLabel(item.direction) + " · " + targetLabel(item.target),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = noteText(item.note),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDiscard) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.domain_stats_suggestion_discard),
            )
        }
    }
}

@Composable
private fun directionLabel(direction: SuggestionDirection): String =
    when (direction) {
        SuggestionDirection.ADD -> stringResource(R.string.domain_stats_suggestion_direction_add)
        SuggestionDirection.REMOVE -> stringResource(R.string.domain_stats_suggestion_direction_remove)
    }

@Composable
private fun targetLabel(target: SuggestionTarget): String =
    when (target) {
        SuggestionTarget.AD_BLOCK -> stringResource(R.string.domain_stats_suggestion_target_adblock)
        SuggestionTarget.DOMAIN_LIST -> stringResource(R.string.domain_stats_suggestion_target_domainlist)
    }

@Composable
private fun noteText(note: SuggestionNote): String {
    val base =
        stringResource(
            R.string.domain_stats_suggestion_note,
            note.connCount,
            MainViewModel.formatBytes(note.downBytes),
        )
    if (note.failedConnCount <= 0) return base
    return base + stringResource(R.string.domain_stats_suggestion_note_failed, note.failedConnCount)
}
