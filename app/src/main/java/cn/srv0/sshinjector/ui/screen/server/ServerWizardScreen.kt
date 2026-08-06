package cn.srv0.sshinjector.ui.screen.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.domain.model.ServerProvisioning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerWizardScreen(
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerWizardViewModel = hiltViewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val loginUsername by viewModel.loginUsername.collectAsState()
    val loginPassword by viewModel.loginPassword.collectAsState()
    val currentProvisionStep by viewModel.currentProvisionStep.collectAsState()
    val result by viewModel.result.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIndicator(currentStep)

            when (currentStep) {
                WizardStep.SERVER_INFO ->
                    ServerInfoStep(
                        name = serverName,
                        host = host,
                        port = port,
                        onNameChange = viewModel::setServerName,
                        onHostChange = viewModel::setHost,
                        onPortChange = viewModel::setPort,
                        onNext = {
                            viewModel.submitServerInfo { msgRes ->
                                val msg = context.getString(msgRes)
                                scope.launch {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        },
                    )
                WizardStep.LOGIN_CREDENTIALS ->
                    LoginCredentialsStep(
                        username = loginUsername,
                        password = loginPassword,
                        onUsernameChange = viewModel::setLoginUsername,
                        onPasswordChange = viewModel::setLoginPassword,
                        onBack = { viewModel.toServerInfo() },
                        onNext = {
                            viewModel.submitCredentials { msgRes ->
                                val msg = context.getString(msgRes)
                                scope.launch {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        },
                    )
                WizardStep.PROVISIONING ->
                    ProvisioningStep(currentProvisionStep)
                WizardStep.RESULT ->
                    ResultStep(
                        result = result,
                        onSave = { viewModel.save(onFinish) },
                        onRetry = { viewModel.retry() },
                    )
            }
        }
    }
}

@Composable
private fun StepIndicator(current: WizardStep) {
    val steps = listOf(WizardStep.SERVER_INFO, WizardStep.LOGIN_CREDENTIALS, WizardStep.PROVISIONING, WizardStep.RESULT)
    val currentIndex = steps.indexOf(current)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, step ->
            val isDone = index < currentIndex
            val isCurrent = index == currentIndex
            val color =
                when {
                    isDone -> MaterialTheme.colorScheme.primary
                    isCurrent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = color,
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isDone) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(stepTitleRes(step)),
                    fontSize = 10.sp,
                    color =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun ServerInfoStep(
    name: String,
    host: String,
    port: String,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.wizard_server_info_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextFieldRow(
                stringResource(R.string.server_name),
                stringResource(R.string.server_placeholder_name),
                name,
                onNameChange,
            )
            OutlinedTextFieldRow(
                stringResource(R.string.server_host),
                stringResource(R.string.server_placeholder_host),
                host,
                onHostChange,
                singleLine = true,
            )
            OutlinedTextFieldRow(
                stringResource(R.string.server_port),
                stringResource(R.string.server_placeholder_port),
                port,
                onPortChange,
                singleLine = true,
                numeric = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onNext,
            ) {
                Text(stringResource(R.string.wizard_next))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LoginCredentialsStep(
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.wizard_login_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.wizard_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextFieldRow(
                stringResource(R.string.wizard_login_username),
                stringResource(R.string.wizard_placeholder_login_username),
                username,
                onUsernameChange,
                singleLine = true,
            )
            OutlinedTextFieldRow(
                stringResource(R.string.wizard_login_password),
                stringResource(R.string.wizard_placeholder_login_password),
                password,
                onPasswordChange,
                singleLine = true,
                password = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.wizard_back))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onNext,
                ) {
                    Text(stringResource(R.string.wizard_start))
                }
            }
        }
    }
}

@Composable
private fun ProvisioningStep(currentStep: ServerProvisioning.Step?) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.wizard_provisioning_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        ServerProvisioning.Step.entries.forEach { step ->
            val isActive = currentStep == step
            val isDone = step.ordinal < (currentStep?.ordinal ?: Int.MAX_VALUE)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (isDone) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Spacer(modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(stepLabelRes(step)),
                    fontSize = 14.sp,
                    color =
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun ResultStep(
    result: WizardResult,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (result) {
                is WizardResult.Success -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.wizard_success, result.account),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.wizard_success_detail, result.keyAlias),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSave,
                    ) {
                        Text(stringResource(R.string.wizard_save))
                    }
                }
                is WizardResult.LocalOnly -> {
                    Text(
                        stringResource(R.string.wizard_local_only, result.reason),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.wizard_local_only_detail, result.keyAlias),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSave,
                    ) {
                        Text(stringResource(R.string.wizard_save_local))
                    }
                }
                is WizardResult.Failed -> {
                    Text(
                        stringResource(R.string.wizard_failed, result.message),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetry,
                    ) {
                        Text(stringResource(R.string.wizard_retry))
                    }
                }
                is WizardResult.Tampered -> {
                    Text(
                        result.message,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is WizardResult.None -> {}
            }
        }
    }
}

@Composable
private fun stepTitleRes(step: WizardStep): Int {
    return when (step) {
        WizardStep.SERVER_INFO -> R.string.wizard_step_info
        WizardStep.LOGIN_CREDENTIALS -> R.string.wizard_step_login
        WizardStep.PROVISIONING -> R.string.wizard_step_provision
        WizardStep.RESULT -> R.string.wizard_step_result
    }
}

@Composable
private fun stepLabelRes(step: ServerProvisioning.Step): Int {
    return when (step) {
        ServerProvisioning.Step.DETECT_PRIVILEGE -> R.string.wizard_step_privilege
        ServerProvisioning.Step.UPLOAD_SCRIPT -> R.string.wizard_step_upload_script
        ServerProvisioning.Step.UPLOAD_PUBKEY -> R.string.wizard_step_upload_pubkey
        ServerProvisioning.Step.EXECUTE_SCRIPT -> R.string.wizard_step_execute
        ServerProvisioning.Step.VERIFY -> R.string.wizard_step_verify
        ServerProvisioning.Step.DONE -> R.string.wizard_step_done
    }
}
