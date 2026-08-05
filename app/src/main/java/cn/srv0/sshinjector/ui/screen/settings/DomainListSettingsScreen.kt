package cn.srv0.sshinjector.ui.screen.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.local.DomainListSource
import cn.srv0.sshinjector.data.local.DomainListState
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import cn.srv0.sshinjector.ui.component.rememberClickGuard
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
                title = { Text(stringResource(R.string.domain_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back))
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
                    Text(stringResource(R.string.domain_list_url_label),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.domain_list_url_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(
                            R.string.domain_list_url_hint)) }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { guard {
                                viewModel.setDomainListUrl(
                                    urlInput.trim()) } },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(
                            R.string.domain_list_save_url)) }
                        TextButton(onClick = {
                            urlInput = SettingsDataStore.DEFAULT_DOMAIN_LIST_URL
                            viewModel.resetToDefault()
                        }) { Text(stringResource(
                            R.string.domain_list_reset_default)) }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.domain_list_status),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    when (val s = state) {
                        is DomainListState.Idle -> StatusRow(
                            stringResource(R.string.domain_list_status),
                            stringResource(R.string.domain_list_status_unloaded))
                        is DomainListState.Loading -> StatusRow(
                            stringResource(R.string.domain_list_status),
                            stringResource(R.string.domain_list_status_loading))
                        is DomainListState.Ready -> {
                            StatusRow(
                                stringResource(R.string.domain_list_status),
                                stringResource(
                                    R.string.domain_list_status_loaded))
                            StatusRow(
                                stringResource(R.string.domain_list_rule_count),
                                s.matcher.ruleCount.toString())
                            StatusRow(
                                stringResource(R.string.domain_list_source),
                                when (s.source) {
                                    DomainListSource.BUILTIN ->
                                        stringResource(
                                            R.string.domain_list_source_builtin)
                                    DomainListSource.DISK ->
                                        stringResource(
                                            R.string.domain_list_source_disk)
                                    DomainListSource.URL ->
                                        stringResource(
                                            R.string.domain_list_source_url)
                                })
                            StatusRow(
                                stringResource(
                                    R.string.domain_list_update_time),
                                s.updatedAt?.let(::formatTime) ?: "?")
                        }
                        is DomainListState.Error -> {
                            StatusRow(
                                stringResource(R.string.domain_list_status),
                                stringResource(
                                    R.string.domain_list_status_failed))
                            StatusRow(
                                stringResource(R.string.domain_list_status),
                                s.message)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { guard { viewModel.updateList() } },
                        enabled = state !is DomainListState.Loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(
                        if (state is DomainListState.Loading)
                            stringResource(R.string.domain_list_updating)
                        else
                            stringResource(R.string.domain_list_update_now)) }
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
        Text(label, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm",
        Locale.getDefault()).format(Date(timestamp))
}