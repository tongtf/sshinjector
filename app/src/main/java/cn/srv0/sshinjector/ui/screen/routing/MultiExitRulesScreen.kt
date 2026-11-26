package cn.srv0.sshinjector.ui.screen.routing

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.vpn.exit.ExitId
import cn.srv0.sshinjector.domain.vpn.routing.RoutingProbe
import cn.srv0.sshinjector.domain.vpn.routing.RuleIssue
import cn.srv0.sshinjector.domain.vpn.routing.SimpleRoutingPolicyCodec
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.screen.whitelist.InstalledApp
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则编辑器 (设计 §13.3 + 2026-11-26 §B)。
 *
 * 两种视图共用同一份文本:
 * - **简单**: 默认出口 + 应用/域名例外, 每次编辑序列化回文本 (文本仍是唯一存储);
 * - **高级**: 原始文本 + 实时校验 + 测试框 + 应用选择器。
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testQuery by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<RoutingProbe.Evaluation?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showDomainDialog by remember { mutableStateOf(false) }

    // 一次性提示: ViewModel 只存**资源键名**, 文案在这里解析 (i18n 硬要求: 不许硬编码中文)
    val savedLabel = stringResource(R.string.routing_saved)
    val unsupportedLabel = stringResource(R.string.routing_simple_unsupported)
    val domainInvalidLabel = stringResource(R.string.routing_domain_invalid)
    val importOkLabel = stringResource(R.string.routing_import_ok)
    val importFailedLabel = stringResource(R.string.routing_import_failed)
    val exportOkLabel = stringResource(R.string.routing_export_ok)
    val exportFailedLabel = stringResource(R.string.routing_export_failed)

    LaunchedEffect(state.message) {
        val code = state.message ?: return@LaunchedEffect
        val text =
            when (code) {
                MultiExitRulesViewModel.MSG_SAVED -> savedLabel
                MultiExitRulesViewModel.MSG_SIMPLE_UNSUPPORTED -> unsupportedLabel
                MultiExitRulesViewModel.MSG_DOMAIN_INVALID -> domainInvalidLabel
                MultiExitRulesViewModel.MSG_IMPORT_OK -> importOkLabel
                else -> null
            }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    // 导出: SAF 让用户自己选目录/文件名 (names 里的字面量不能进 SimpleDateFormat 模式,
    // 否则 `sshinjector` 中的 `i` 会被当模式字母抛 IllegalArgumentException)
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val ok =
                runCatching {
                    val text = viewModel.exportText()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream returned null")
                }.isSuccess
            Toast.makeText(context, if (ok) exportOkLabel else exportFailedLabel, Toast.LENGTH_SHORT).show()
        }

    // 导入: 上限 64KB, 超出视为失败 (规则文本正常只有几 KB, 大文件一定是选错了)
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text = runCatching { readTextLimited(context, uri, MAX_IMPORT_BYTES) }.getOrNull()
            if (text == null) {
                Toast.makeText(context, importFailedLabel, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.applyImportedText(text)
            }
        }

    val appNames = remember(state.apps) { state.apps.associate { it.packageName to it.name } }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = state.mode == RulesEditorMode.SIMPLE,
                    onClick = { viewModel.setMode(RulesEditorMode.SIMPLE) },
                    label = { Text(stringResource(R.string.routing_mode_simple), fontSize = 12.sp) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = state.mode == RulesEditorMode.ADVANCED,
                    onClick = { viewModel.setMode(RulesEditorMode.ADVANCED) },
                    label = { Text(stringResource(R.string.routing_mode_advanced), fontSize = 12.sp) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { importLauncher.launch(arrayOf("text/plain")) }) {
                    Text(stringResource(R.string.routing_import), fontSize = 12.sp)
                }
                TextButton(onClick = { exportLauncher.launch(routingFileName()) }) {
                    Text(stringResource(R.string.routing_export), fontSize = 12.sp)
                }
            }

            // 只有高级模式才可能"表达不了": 简单模式下文本必然来自 SimpleRoutingPolicyCodec
            if (state.mode == RulesEditorMode.ADVANCED && !state.simpleSupported) {
                Text(
                    stringResource(R.string.routing_simple_unsupported),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(8.dp))

            when (state.mode) {
                RulesEditorMode.SIMPLE ->
                    SimpleRulesEditor(
                        state = state,
                        appNames = appNames,
                        onDefaultExit = viewModel::setDefaultExit,
                        onAppExit = viewModel::setAppExit,
                        onRemoveApp = viewModel::removeAppRoute,
                        onDomainExit = viewModel::setDomainExit,
                        onRemoveDomain = viewModel::removeDomainRoute,
                        onAddApp = { showAppPicker = true },
                        onAddDomain = { showDomainDialog = true },
                    )
                RulesEditorMode.ADVANCED ->
                    AdvancedRulesEditor(
                        state = state,
                        testQuery = testQuery,
                        testResult = testResult,
                        onTextChange = viewModel::updateText,
                        onTestQueryChange = { testQuery = it },
                        onRunTest = { scope.launch { testResult = viewModel.test(testQuery) } },
                        onOpenAppPicker = { showAppPicker = true },
                    )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = state.apps,
            exits = state.exitOptions,
            onDismiss = { showAppPicker = false },
            onPick = { app, exitId ->
                if (state.mode == RulesEditorMode.SIMPLE) {
                    viewModel.addAppRoute(app.packageName, exitId)
                } else {
                    viewModel.appendAppRule(app.packageName, exitId)
                }
                showAppPicker = false
            },
        )
    }

    if (showDomainDialog) {
        AddDomainDialog(
            exits = state.exitOptions,
            onDismiss = { showDomainDialog = false },
            onConfirm = { domain, exitId ->
                viewModel.addDomainRoute(domain, exitId)
                showDomainDialog = false
            },
        )
    }
}

private const val MAX_ISSUES_SHOWN = 12

/** 导入上限 (字节): 规则文本正常只有几 KB, 超过一定是选错了文件。 */
private const val MAX_IMPORT_BYTES = 64 * 1024

/**
 * 简单模式: 默认出口 + 应用例外 + 域名例外。
 *
 * 行序即优先级 (`应用 > 域名(最长后缀) > 默认`), 所以列表**不出排序控件** —— 让用户拖优先级
 * 就得再解释"为什么拖了没用" (文本序列化时按固定规则重排), 小字说明比控件更诚实。
 */
@Composable
private fun SimpleRulesEditor(
    state: MultiExitRulesUiState,
    appNames: Map<String, String>,
    onDefaultExit: (String) -> Unit,
    onAppExit: (String, String) -> Unit,
    onRemoveApp: (String) -> Unit,
    onDomainExit: (String, String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onAddApp: () -> Unit,
    onAddDomain: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.routing_default_exit), fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        ExitDropdown(current = state.defaultExit, options = state.exitOptions, onPick = onDefaultExit)
    }
    Text(
        stringResource(R.string.routing_priority_note),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SectionHeader(
        title = stringResource(R.string.routing_apps),
        addLabel = stringResource(R.string.routing_add_app),
        onAdd = onAddApp,
    )
    if (state.appRoutes.isEmpty()) {
        EmptyHint(stringResource(R.string.routing_no_apps))
    } else {
        state.appRoutes.forEach { route ->
            RouteRow(
                title = appNames[route.packageName] ?: route.packageName,
                subtitle = appNames[route.packageName]?.let { route.packageName },
                exitId = route.exitId,
                options = state.exitOptions,
                onExit = { onAppExit(route.packageName, it) },
                onRemove = { onRemoveApp(route.packageName) },
            )
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SectionHeader(
        title = stringResource(R.string.routing_domains),
        addLabel = stringResource(R.string.routing_add_domain),
        onAdd = onAddDomain,
    )
    if (state.domainRoutes.isEmpty()) {
        EmptyHint(stringResource(R.string.routing_no_domains))
    } else {
        state.domainRoutes.forEach { route ->
            RouteRow(
                title = route.suffix,
                subtitle = null,
                exitId = route.exitId,
                options = state.exitOptions,
                onExit = { onDomainExit(route.suffix, it) },
                onRemove = { onRemoveDomain(route.suffix) },
            )
        }
    }
}

/** 高级模式: 文本框 + 校验 + 测试框 + 应用选择器 (保持改造前的能力与提示)。 */
@Composable
private fun AdvancedRulesEditor(
    state: MultiExitRulesUiState,
    testQuery: String,
    testResult: RoutingProbe.Evaluation?,
    onTextChange: (String) -> Unit,
    onTestQueryChange: (String) -> Unit,
    onRunTest: () -> Unit,
    onOpenAppPicker: () -> Unit,
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
        onValueChange = onTextChange,
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
        TextButton(onClick = onOpenAppPicker) {
            Text(stringResource(R.string.multi_exit_app_picker))
        }
    }

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.multi_exit_test), fontSize = 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = testQuery,
            onValueChange = onTestQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(stringResource(R.string.multi_exit_test_hint)) },
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRunTest) { Text(stringResource(R.string.multi_exit_test_run)) }
    }
    testResult?.let { result ->
        val text =
            when {
                result.error != null -> result.error
                result.exitId == null -> stringResource(R.string.multi_exit_test_default)
                else -> stringResource(R.string.multi_exit_test_result, result.exitId)
            }
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 小节标题 + 右侧"添加"按钮。 */
@Composable
private fun SectionHeader(
    title: String,
    addLabel: String,
    onAdd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text(addLabel, fontSize = 12.sp) }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** 一条例外: 标题 (+可选副标题) + 出口下拉 + 删除。 */
@Composable
private fun RouteRow(
    title: String,
    subtitle: String?,
    exitId: String,
    options: List<String>,
    onExit: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 规则引用的出口可能已被删除/禁用: 当前值不在候选里也要能显示 (并允许改选有效出口)
        ExitDropdown(current = exitId, options = (options + exitId).distinct(), onPick = onExit)
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.routing_remove), fontSize = 12.sp)
        }
    }
}

/** 出口下拉: `default` 显示为本地化的"主出口" (规则文本里仍是 `default`)。 */
@Composable
private fun ExitDropdown(
    current: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(exitLabel(current), fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { id ->
                DropdownMenuItem(
                    text = { Text(exitLabel(id)) },
                    onClick = {
                        expanded = false
                        onPick(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun exitLabel(exitId: String): String =
    if (exitId == ExitId.DEFAULT) {
        stringResource(R.string.routing_main_exit)
    } else {
        exitId
    }

/** 添加域名例外的输入框 (出口默认为主出口)。 */
@Composable
private fun AddDomainDialog(
    exits: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var exitId by remember { mutableStateOf(ExitId.DEFAULT) }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_add_domain)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = invalid,
                    label = { Text(stringResource(R.string.routing_domain_hint)) },
                )
                if (invalid) {
                    Text(
                        stringResource(R.string.routing_domain_invalid),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.multi_exit_app_target_exit), fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    ExitDropdown(current = exitId, options = exits, onPick = { exitId = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 与 ViewModel 同一个校验器 (normalizeDomain), UI 不复制规则
                    val suffix = SimpleRoutingPolicyCodec.normalizeDomain(input)
                    if (suffix == null) {
                        invalid = true
                    } else {
                        onConfirm(suffix, exitId)
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

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
    var exitId by remember { mutableStateOf(exits.firstOrNull() ?: ExitId.DEFAULT) }
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
                    TextButton(onClick = { expanded = true }) { Text(exitLabel(exitId)) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        exits.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(exitLabel(id)) },
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

/** 导出文件名: 时间戳模式里**不放任何字面量** (SimpleDateFormat 会把字母当模式)。 */
private fun routingFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "sshinjector-routing-$stamp.txt"
}

/** 读入 UTF-8 文本, 超过 [limit] 字节返回 null (导入失败)。 */
private fun readTextLimited(
    context: Context,
    uri: Uri,
    limit: Int,
): String? {
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    return stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}

private const val READ_BUFFER_BYTES = 8 * 1024
