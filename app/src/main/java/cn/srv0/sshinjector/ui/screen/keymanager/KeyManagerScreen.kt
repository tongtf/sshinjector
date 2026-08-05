package cn.srv0.sshinjector.ui.screen.keymanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.data.remote.ssh.KeyKind
import cn.srv0.sshinjector.ui.component.rememberClickGuard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeyManagerViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keys by viewModel.keys.collectAsState()
    val activeKey by viewModel.activeKey.collectAsState()

    var showGenerateDialog by remember { mutableStateOf(false) }
    var generateAlgorithm by remember { mutableIntStateOf(0) }
    var importedKey by remember { mutableStateOf("") }
    var importIsPublic by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(Unit) {
        viewModel.error.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showGenerateDialog = true }) {
                        Icon(Icons.Default.Add,
                            contentDescription = stringResource(R.string.key_generate))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (activeKey != null) {
                val key = activeKey!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    stringResource(R.string.key_current),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    key.algorithm,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            KeyKindIcon(
                                kind = key.kind,
                                isBiometricProtected = key.isBiometricProtected,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            stringResource(R.string.key_public),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.3f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    key.publicKey,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
Button(
                                    onClick = {
                                        val success =
                                            viewModel.copyPublicKey(
                                                key.publicKey)
                                        val ok = context.resources.getString(
                                            R.string.dashboard_copy_success)
                                        val ko = context.resources.getString(
                                            R.string.dashboard_copy_failed)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (success) ok else ko
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(stringResource(R.string.copy),
                                        fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                            .copy(alpha = 0.5f)
                    ),
                    onClick = { showGenerateDialog = true }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.key_generate),
                            fontSize = 14.sp)
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                            .copy(alpha = 0.5f)
                    ),
                    onClick = { showImportDialog = true }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.key_import),
                            fontSize = 14.sp)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.key_saved),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (keys.isNotEmpty()) {
                            TextButton(onClick = { showDeleteAllDialog = true }) {
                                Text(stringResource(R.string.key_clear_all),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    if (keys.isEmpty()) {
                        Text(
                            stringResource(R.string.key_no_keys),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        keys.forEach { key ->
                            KeyListItem(
                                alias = key.alias,
                                algorithm = key.algorithm,
                                createdAt = key.createdAt,
                                kind = key.kind,
                                 isBiometricProtected = key.isBiometricProtected,
                                onCopy = {
                                    val success =
                                        viewModel.copyPublicKey(
                                            key.publicKey)
                                    val ok = context.resources.getString(
                                        R.string.dashboard_copy_success)
                                     val ko = context.resources.getString(
                                        R.string.dashboard_copy_failed)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (success) ok else ko
                                        )
                                    }
                                },
                                onDelete = {
                                    keyToDelete = key.alias
                                    showDeleteDialog = true
                                }
                            )
                            if (key != keys.last()) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            onConfirm = { algo ->
                showGenerateDialog = false
                viewModel.generateKeyPair(
                    "key_${System.currentTimeMillis()}", algo, false)
            },
            onDismiss = { showGenerateDialog = false },
            selectedAlgorithm = generateAlgorithm,
            onAlgorithmChange = { generateAlgorithm = it }
        )
    }

    if (showImportDialog) {
        ImportKeyDialog(
            onConfirm = { isPublic, keyContent ->
                showImportDialog = false
                val alias = "imported_${System.currentTimeMillis()}"
                if (isPublic) {
                    viewModel.importPublicKey(alias, keyContent)
                } else {
                    viewModel.importPrivateKey(alias, keyContent)
                }
            },
            onDismiss = { showImportDialog = false },
            isPublic = importIsPublic,
            onIsPublicChange = { importIsPublic = it },
            keyContent = importedKey,
            onKeyContentChange = { importedKey = it }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.key_delete_title)) },
            text = { Text(stringResource(R.string.key_delete_msg,
                keyToDelete)) },
            confirmButton = {
                val guard = rememberClickGuard()
                Button(
                    onClick = {
                        guard {
                            viewModel.deleteKey(keyToDelete)
                            showDeleteDialog = false
                            val msg = context.resources.getString(
                                R.string.key_deleted)
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.key_clear_all_title)) },
            text = { Text(stringResource(R.string.key_clear_all_msg)) },
            confirmButton = {
                val guard = rememberClickGuard()
                Button(
                    onClick = {
                        guard {
                            viewModel.deleteAllKeys()
                            showDeleteAllDialog = false
                            val msg = context.resources.getString(
                                R.string.key_cleared_all)
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun KeyListItem(
    alias: String,
    algorithm: String,
    createdAt: String,
    kind: KeyKind,
    isBiometricProtected: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically) {
                KeyKindIcon(
                    kind = kind,
                    isBiometricProtected = isBiometricProtected,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(alias, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$algorithm • $createdAt",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(
                        horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.copy), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.delete), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun KeyKindIcon(
    kind: KeyKind,
    isBiometricProtected: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val imageVector: androidx.compose.ui.graphics.vector.ImageVector
    val color: Color
    when {
        kind == KeyKind.GENERATED && isBiometricProtected -> {
            imageVector = Icons.Default.Lock
            color = MaterialTheme.colorScheme.primary
        }
        kind == KeyKind.GENERATED -> {
            imageVector = Icons.Default.Lock
            color = tint
        }
        kind == KeyKind.IMPORTED_PRIVATE -> {
            imageVector = Icons.Default.Edit
            color = MaterialTheme.colorScheme.tertiary
        }
        else -> {
            imageVector = Icons.Default.Send
            color = tint
        }
    }
    Icon(imageVector = imageVector, contentDescription = null,
        modifier = modifier, tint = color)
}

@Composable
fun GenerateKeyDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    selectedAlgorithm: Int,
    onAlgorithmChange: (Int) -> Unit
) {
    val algorithms = listOf("ECDSA P-256", "RSA 2048", "ECDSA P-384",
        "Ed25519")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_generate)) },
        text = {
            Column {
                algorithms.forEachIndexed { index, name ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedAlgorithm == index)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.3f),
                        onClick = { onAlgorithmChange(index) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, fontSize = 14.sp)
                            if (selectedAlgorithm == index) {
                                Text("✓",
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val guard = rememberClickGuard()
            Button(onClick = {
                guard { onConfirm(selectedAlgorithm) } }) {
                Text(stringResource(R.string.generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ImportKeyDialog(
    onConfirm: (isPublic: Boolean, keyContent: String) -> Unit,
    onDismiss: () -> Unit,
    isPublic: Boolean,
    onIsPublicChange: (Boolean) -> Unit,
    keyContent: String,
    onKeyContentChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_import)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(false to stringResource(R.string.key_private),
                        true to stringResource(R.string.key_public))
                        .forEach { (value, label) ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onIsPublicChange(value) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPublic == value)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.3f)
                        ) {
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                fontWeight = if (isPublic == value)
                                    FontWeight.Bold else FontWeight.Normal,
                                color = if (isPublic == value)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = keyContent,
                    onValueChange = onKeyContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    placeholder = {
                        Text(
                            if (isPublic) "ssh-rsa AAA..." else "..."
                        )
                    }
                )
            }
        },
        confirmButton = {
            val guard = rememberClickGuard()
            Button(
                onClick = { guard { onConfirm(isPublic, keyContent) } },
                enabled = keyContent.isNotBlank()
            ) {
                Text(stringResource(R.string.import_))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}}

@Composable
fun KeyListItem(
    alias: String,
    algorithm: String,
    createdAt: String,
    kind: KeyKind,
    isBiometricProtected: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                KeyKindIcon(
                    kind = kind,
                    isBiometricProtected = isBiometricProtected,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(alias, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$algorithm • $createdAt",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.copy), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.delete), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 密钥加密状态图标:
 * - 本地生成 + 生物识别: 锁 (主色)
 * - 本地生成 (无生物识别): 开锁
 * - 导入私钥 (AES 加密存储): 编辑
 * - 仅导入公钥: 公开
 */
@Composable
fun KeyKindIcon(
    kind: KeyKind,
    isBiometricProtected: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val imageVector: androidx.compose.ui.graphics.vector.ImageVector
    val color: Color
    when {
        kind == KeyKind.GENERATED && isBiometricProtected -> {
            imageVector = Icons.Default.Lock
            color = MaterialTheme.colorScheme.primary
        }
        kind == KeyKind.GENERATED -> {
            imageVector = Icons.Default.Lock
            color = tint
        }
        kind == KeyKind.IMPORTED_PRIVATE -> {
            imageVector = Icons.Default.Edit
            color = MaterialTheme.colorScheme.tertiary
        }
        else -> {
            imageVector = Icons.Default.Send
            color = tint
        }
    }
    Icon(imageVector = imageVector, contentDescription = null, modifier = modifier, tint = color)
}

@Composable
fun GenerateKeyDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    selectedAlgorithm: Int,
    onAlgorithmChange: (Int) -> Unit
) {
    val algorithms = listOf("ECDSA P-256", "RSA 2048", "ECDSA P-384", "Ed25519")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_generate)) },
        text = {
            Column {
                algorithms.forEachIndexed { index, name ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedAlgorithm == index)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        onClick = { onAlgorithmChange(index) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, fontSize = 14.sp)
                            if (selectedAlgorithm == index) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val guard = rememberClickGuard()
            Button(onClick = { guard { onConfirm(selectedAlgorithm) } }) {
                Text(stringResource(R.string.generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ImportKeyDialog(
    onConfirm: (isPublic: Boolean, keyContent: String) -> Unit,
    onDismiss: () -> Unit,
    isPublic: Boolean,
    onIsPublicChange: (Boolean) -> Unit,
    keyContent: String,
    onKeyContentChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_import)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(false to stringResource(R.string.key_private), true to stringResource(R.string.key_public)).forEach { (value, label) ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onIsPublicChange(value) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPublic == value)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                fontWeight = if (isPublic == value) FontWeight.Bold else FontWeight.Normal,
                                color = if (isPublic == value)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = keyContent,
                    onValueChange = onKeyContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    placeholder = {
                        Text(
                            if (isPublic) "ssh-rsa AAA..." else "..."
                        )
                    }
                )
            }
        },
        confirmButton = {
            val guard = rememberClickGuard()
            Button(
                onClick = { guard { onConfirm(isPublic, keyContent) } },
                enabled = keyContent.isNotBlank()
            ) {
                Text(stringResource(R.string.import_))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
