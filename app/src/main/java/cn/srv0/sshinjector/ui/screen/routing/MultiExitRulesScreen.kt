package cn.srv0.sshinjector.ui.screen.routing

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.vpn.routing.RoutingProbe
import cn.srv0.sshinjector.domain.vpn.routing.RuleIssue
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.screen.whitelist.InstalledApp
import kotlinx.coroutines.launch

/**
 * 规则编辑器 (设计 §13.3): 文本 + 实时校验 + 测试框 + 应用选择器。
 *
 * 校验与匹配都直接调 `domain/vpn/routing` 的实现 —— UI 不复制任何判定逻辑, 否则
 * "UI 说命中、数据面走 default" 这类分歧无法靠肉眼发现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiExitRulesScreen(
    onBack: () -> Unit,
    viewModel: MultiExitRulesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var testQuery by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<RoutingProbe.Evaluation?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multi_exit_rules)) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    TextButton(onClick = { viewModel.save() }, enabled = state.canSave) {
                        Text(stringResource(R.string.multi_exit_save))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            if (state.tooManyRules) {
                Text(
                    stringResource(R.string.multi_exit_rule_too_many),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::updateText,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(stringResource(R.string.multi_exit_rules_placeholder)) },
            )

            if (state.hasNetworkRule) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.multi_exit_ip_rule_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.issues.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.multi_exit_rule_issues, state.issues.size),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                state.issues.take(MAX_ISSUES_SHOWN).forEach { issue ->
                    Text(
                        "· ${stringResource(R.string.multi_exit_rule_line, issue.lineNo)} ${issueText(issue.issue)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state.unresolvedExits.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.multi_exit_rule_unresolved, state.unresolvedExits.joinToString(", ")),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showAppPicker = true }) {
                    Text(stringResource(R.string.multi_exit_app_picker))
                }
            }

            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.multi_exit_test), fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = testQuery,
                    onValueChange = { testQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.multi_exit_test_hint)) },
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        scope.launch { testResult = viewModel.test(testQuery) }
                    },
                ) { Text(stringResource(R.string.multi_exit_test_run)) }
            }
            testResult?.let { result ->
                val text =
                    when {
                        result.error != null -> result.error
                        result.exitId == null -> stringResource(R.string.multi_exit_test_default)
                        else -> stringResource(R.string.multi_exit_test_result, result.exitId)
                    }
                Text(
                    text ?: "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = state.apps,
            exits = listOf("default") + state.knownExits,
            onDismiss = { showAppPicker = false },
            onPick = { app, exitId ->
                viewModel.appendAppRule(app.packageName, exitId)
                showAppPicker = false
            },
        )
    }
}

private const val MAX_ISSUES_SHOWN = 12

@Composable
private fun issueText(issue: RuleIssue): String =
    stringResource(
        when (issue) {
            RuleIssue.INVALID_TARGET -> R.string.multi_exit_issue_invalid_target
            RuleIssue.INVALID_PORT -> R.string.multi_exit_issue_invalid_port
            RuleIssue.INVALID_CIDR -> R.string.multi_exit_issue_invalid_cidr
            RuleIssue.INVALID_PACKAGE -> R.string.multi_exit_issue_invalid_package
            RuleIssue.INVALID_EXIT -> R.string.multi_exit_issue_invalid_exit
            RuleIssue.DEFAULT_NOT_LAST -> R.string.multi_exit_issue_default_not_last
            RuleIssue.DUPLICATE_TARGET -> R.string.multi_exit_issue_duplicate
            RuleIssue.DEFAULT_ROUTE_REJECTED -> R.string.multi_exit_issue_default_route
            RuleIssue.TOO_MANY_RULES -> R.string.multi_exit_issue_too_many
        },
    )

@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>,
    exits: List<String>,
    onDismiss: () -> Unit,
    onPick: (InstalledApp, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var exitId by remember { mutableStateOf(exits.firstOrNull() ?: "default") }
    var expanded by remember { mutableStateOf(false) }
    // 共享 uid: 多个包共用一个 uid 时 getNameForUid 只返回其中一个包名, 规则会命中整组
    val sharedUids =
        remember(apps) {
            apps
                .filter { it.uid >= 0 }
                .groupBy { it.uid }
                .filterValues { it.size > 1 }
                .keys
        }
    val filtered =
        remember(apps, query) {
            if (query.isBlank()) {
                apps.take(MAX_APPS_SHOWN)
            } else {
                apps
                    .filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }.take(MAX_APPS_SHOWN)
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multi_exit_app_picker)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.multi_exit_app_target_exit),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { expanded = true }) { Text(exitId) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        exits.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id) },
                                onClick = {
                                    exitId = id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.multi_exit_app_search)) },
                )
                if (sharedUids.isNotEmpty()) {
                    Text(
                        stringResource(R.string.multi_exit_shared_uid_desc),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .height(280.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { app ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.name, fontSize = 14.sp)
                                    if (app.uid in sharedUids) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.multi_exit_shared_uid),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                Text(
                                    app.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onPick(app, exitId) }) {
                                Text(stringResource(R.string.multi_exit_app_add))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.multi_exit_cancel)) }
        },
    )
}

private const val MAX_APPS_SHOWN = 60
