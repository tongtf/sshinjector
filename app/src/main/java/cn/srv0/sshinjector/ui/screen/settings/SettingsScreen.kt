package cn.srv0.sshinjector.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.ui.locale.LocaleManager
import cn.srv0.sshinjector.ui.viewmodel.dnsModeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateToDomainListSettings: () -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    onNavigateToServerManagement: () -> Unit = {},
    onNavigateToKeyManagement: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val biometricUnlock by viewModel.biometricUnlock.collectAsState()
    val mtu by viewModel.mtu.collectAsState()
    val keepAlive by viewModel.keepAlive.collectAsState()
    val enableIPv6 by viewModel.enableIPv6.collectAsState()
    val dnsMode by viewModel.dnsMode.collectAsState()
    val language by viewModel.language.collectAsState()
    var showDnsDialog by remember { mutableStateOf(false) }
    var showBiometricDialog by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val fragmentActivity = context as? androidx.fragment.app.FragmentActivity
    val biometricAuth = fragmentActivity?.let { cn.srv0.sshinjector.ui.biometric.BiometricAuth.from(it) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                    .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_management)) {
                SettingsRow(
                    title = stringResource(R.string.settings_server_management),
                    subtitle = stringResource(R.string.settings_server_management_desc),
                    trailing = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = onNavigateToServerManagement,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_key_management),
                    subtitle = stringResource(R.string.settings_key_management_desc),
                    trailing = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = onNavigateToKeyManagement,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_whitelist),
                    subtitle = stringResource(R.string.settings_whitelist_desc),
                    trailing = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = onNavigateToWhitelist,
                )
            }

            SettingsSection(stringResource(R.string.settings_network)) {
                Text(
                    stringResource(R.string.settings_mtu, mtu ?: 1500),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                Slider(
                    value = (mtu ?: 1500).toFloat(),
                    onValueChange = { viewModel.setMtu(it.toInt()) },
                    valueRange = 1280f..1500f,
                    steps = 54,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                )

                Text(
                    stringResource(R.string.settings_keepalive, keepAlive ?: 30),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                Slider(
                    value = (keepAlive ?: 30).toFloat(),
                    onValueChange = { viewModel.setKeepAlive(it.toInt()) },
                    valueRange = 10f..120f,
                    steps = 21,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                )

                SettingsRow(
                    title = stringResource(R.string.settings_ipv6),
                    subtitle = stringResource(R.string.settings_ipv6_desc),
                    trailing = {
                        Switch(
                            checked = enableIPv6 ?: true,
                            onCheckedChange = { viewModel.setEnableIPv6(it) },
                        )
                    },
                )

                SettingsRow(
                    title = stringResource(R.string.settings_connection_mode),
                    subtitle = dnsModeLabel(dnsMode, context),
                    trailing = {
                        Text(
                            text = dnsModeLabel(dnsMode, context),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { showDnsDialog = true },
                )

                SettingsRow(
                    title = stringResource(R.string.settings_domain_list),
                    subtitle = stringResource(R.string.settings_domain_list_desc),
                    trailing = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = onNavigateToDomainListSettings,
                )

                SettingsRow(
                    title = stringResource(R.string.settings_language),
                    subtitle = LocaleManager.getDisplayLabel(language, context),
                    trailing = {
                        Text(
                            text = LocaleManager.getDisplayLabel(language, context),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { showLangDialog = true },
                )
            }

            SettingsSection(stringResource(R.string.settings_security)) {
                SettingsRow(
                    title = stringResource(R.string.settings_biometric_unlock),
                    subtitle = stringResource(R.string.settings_biometric_unlock_desc),
                    trailing = {
                        Switch(
                            checked = biometricUnlock,
                            onCheckedChange = { enabled ->
                                if (!enabled && biometricUnlock) {
                                    showBiometricDialog = true
                                } else {
                                    viewModel.setBiometricUnlock(enabled)
                                }
                            },
                        )
                    },
                )
            }

            SettingsSection(stringResource(R.string.settings_about)) {
                SettingsRow(stringResource(R.string.settings_version), "1.0.2", trailing = {})
                SettingsRow(stringResource(R.string.settings_license), "MIT License", trailing = {})
                SettingsRow(
                    title = stringResource(R.string.settings_github),
                    subtitle = stringResource(R.string.settings_github_desc),
                    trailing = {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }

    if (showLangDialog) {
        val langOptions =
            listOf(
                LocaleManager.LANGUAGE_SYSTEM,
                LocaleManager.LANGUAGE_CHINESE,
                LocaleManager.LANGUAGE_ENGLISH,
                LocaleManager.LANGUAGE_RUSSIAN,
            )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(stringResource(R.string.language_dialog_title)) },
            text = {
                Column {
                    langOptions.forEach { code ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setLanguage(code)
                                        showLangDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(LocaleManager.getDisplayLabel(code, context), fontSize = 16.sp)
                            if (language == code) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.language_after_change),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDnsDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            title = { Text(stringResource(R.string.settings_connection_mode_title)) },
            text = {
                Column {
                    listOf(
                        0 to stringResource(R.string.dashboard_dns_remote) to
                            stringResource(R.string.dns_remote_desc),
                        1 to stringResource(R.string.dashboard_dns_direct) to
                            stringResource(R.string.dns_direct_desc),
                        2 to stringResource(R.string.dashboard_dns_whitelist) to
                            stringResource(R.string.dns_whitelist_desc),
                        3 to stringResource(R.string.dashboard_dns_domain) to
                            stringResource(R.string.dns_domain_desc),
                    ).forEach { (item, desc) ->
                        val (value, label) = item
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setDnsMode(value)
                                        showDnsDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, fontSize = 16.sp)
                                Text(
                                    desc,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (dnsMode == value) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDnsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showBiometricDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBiometricDialog = false },
            title = { Text(stringResource(R.string.settings_biometric_disable_title)) },
            text = { Text(stringResource(R.string.settings_biometric_disable_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBiometricDialog = false
                        val activity = fragmentActivity
                        if (activity != null && biometricAuth != null) {
                            biometricAuth.authenticate(
                                activity = activity,
                                title = context.getString(R.string.settings_verify_identity),
                                onSuccess = { viewModel.setBiometricUnlock(false) },
                                onCancelled = {},
                            )
                        }
                    },
                ) { Text(stringResource(R.string.settings_biometric_verify)) }
            },
            dismissButton = {
                TextButton(onClick = { showBiometricDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun TextButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.TextButton(onClick = onClick) { content() }
}
