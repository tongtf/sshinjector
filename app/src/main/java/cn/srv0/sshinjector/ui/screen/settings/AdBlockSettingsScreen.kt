package cn.srv0.sshinjector.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.AdBlockRemoteState
import cn.srv0.sshinjector.ui.component.BackNavIcon
import cn.srv0.sshinjector.ui.component.StatusRow
import cn.srv0.sshinjector.ui.component.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AdBlockSettingsViewModel = hiltViewModel(),
) {
    val rules by viewModel.rulesText.collectAsState()
    var draft by remember { mutableStateOf(rules) }
    LaunchedEffect(rules) {
        draft = rules
    }
    val query by viewModel.query.collectAsState()
    val visibleCount by viewModel.visibleRuleCount.collectAsState()
    val filteredLines by viewModel.filteredLines.collectAsState()

    val remoteUrl by viewModel.remoteUrl.collectAsState()
    val refreshInterval by viewModel.refreshInterval.collectAsState()
    val remoteState by viewModel.remoteState.collectAsState()
    val remoteText by viewModel.remoteText.collectAsState()
    var urlInput by remember { mutableStateOf(remoteUrl) }
    LaunchedEffect(remoteUrl) {
        urlInput = remoteUrl
    }
    var intervalInput by remember { mutableStateOf(refreshInterval.toString()) }
    LaunchedEffect(refreshInterval) {
        intervalInput = refreshInterval.toString()
    }

    val totalRules =
        remember(rules, remoteText) { countVisibleRules(rules) + countVisibleRules(remoteText) }

    var showHelp by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_ad_block)) },
                navigationIcon = {
                    BackNavIcon(onNavigateBack)
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
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    if (query.isBlank()) {
                        stringResource(R.string.settings_ad_block_count, totalRules)
                    } else {
                        stringResource(R.string.settings_ad_block_filtered, visibleCount, totalRules)
                    },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_ad_block_remote_url),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_ad_block_remote_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_ad_block_remote_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { viewModel.saveRemoteUrlAndRefresh(urlInput) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.settings_ad_block_remote_save))
                        }
                        TextButton(onClick = { viewModel.resetRemoteUrlToDefault() }) {
                            Text(stringResource(R.string.settings_ad_block_reset_default))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { newValue ->
                            val digits = newValue.filter { it.isDigit() }
                            if (digits != newValue) {
                                intervalInput = digits
                            } else {
                                viewModel.setRefreshInterval(digits.toIntOrNull() ?: refreshInterval)
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_ad_block_refresh_interval_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.refreshNow() },
                        enabled = remoteState !is AdBlockRemoteState.Loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_ad_block_refresh_now))
                    }
                    Spacer(Modifier.height(12.dp))
                    when (val s = remoteState) {
                        AdBlockRemoteState.Idle ->
                            if (remoteUrl.isBlank()) {
                                Text(
                                    stringResource(R.string.settings_ad_block_remote_desc),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        AdBlockRemoteState.Loading ->
                            StatusRow(
                                stringResource(R.string.settings_ad_block_status),
                                stringResource(R.string.settings_ad_block_refresh_now),
                            )
                        is AdBlockRemoteState.Ready ->
                            StatusRow(
                                stringResource(R.string.settings_ad_block_status),
                                stringResource(
                                    R.string.settings_ad_block_status_loaded,
                                    s.updatedAt?.let(::formatTime) ?: "?",
                                ),
                            )
                        is AdBlockRemoteState.Error ->
                            StatusRow(
                                stringResource(R.string.settings_ad_block_status),
                                stringResource(R.string.settings_ad_block_status_failed, s.message),
                            )
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                label = { Text(stringResource(R.string.settings_ad_block_search)) },
                placeholder = { Text(stringResource(R.string.settings_ad_block_search_hint)) },
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showHelp = !showHelp }) {
                    Text(stringResource(R.string.settings_ad_block_help_toggle))
                }
            }
            if (showHelp) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_ad_block_editor_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        HelpLine(stringResource(R.string.settings_ad_block_help_suffix))
                        HelpLine(stringResource(R.string.settings_ad_block_help_comment))
                        HelpLine(stringResource(R.string.settings_ad_block_help_exception))
                    }
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.settings_ad_block_editor_label)) },
                minLines = 10,
                maxLines = 20,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.setRules(draft) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_ad_block_save))
                }
                TextButton(onClick = { viewModel.resetToDefault() }) {
                    Text(stringResource(R.string.settings_ad_block_reset_default))
                }
            }

            if (query.isNotBlank() && filteredLines.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.settings_ad_block_matches),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 随外层主可滚Column整体滚动; 内部不再嵌套verticalScroll(否则触发 infinite-height crash)
                        Column {
                            filteredLines.forEach { line ->
                                Text(
                                    text = line,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun countVisibleRules(text: String): Int {
    var n = 0
    for (line in text.lineSequence()) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("!") || t.startsWith("[")) continue
        n++
    }
    return n
}

@Composable
private fun HelpLine(text: String) {
    Text(
        text = "• $text",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
