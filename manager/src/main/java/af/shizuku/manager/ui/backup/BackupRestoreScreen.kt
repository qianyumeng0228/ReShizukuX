package af.shizuku.manager.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.backup.BackupManager
import af.shizuku.manager.backup.BackupManager.BackupEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复全屏页面（ReShizukuX beta1 组A）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showRestartHint by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BackupEntry?>(null) }

    fun refresh() {
        scope.launch {
            backups = withContext(Dispatchers.IO) { BackupManager.listBackups(context) }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = context.getString(R.string.rsx_backup_restoring)
        scope.launch {
            val result = withContext(Dispatchers.IO) { BackupManager.restoreFromUri(context, uri) }
            busy = false
            result.onSuccess {
                message = context.getString(R.string.rsx_backup_done_desc)
                showRestartHint = true
            }.onFailure {
                message = context.getString(R.string.rsx_backup_restore_failed, it.message)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.rsx_backup_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(af.shizuku.manager.R.drawable.ic_back_24), contentDescription = stringResource(R.string.rsx_back))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rsx_backup_content_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.rsx_backup_content1), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.rsx_backup_content2), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.rsx_backup_content3), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.rsx_backup_saved_to),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true
                        message = context.getString(R.string.rsx_backup_backing_up)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { BackupManager.createBackup(context) }
                            busy = false
                            result.onSuccess { entry ->
                                message = context.getString(R.string.rsx_backup_backed_up, entry.fileName)
                                refresh()
                            }.onFailure {
                                message = context.getString(R.string.rsx_backup_failed, it.message)
                            }
                        }
                    },
                    enabled = !busy
                ) { Text(stringResource(R.string.rsx_backup_create)) }

                OutlinedButton(
                    onClick = { pickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !busy
                ) { Text(stringResource(R.string.rsx_backup_restore)) }
            }

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Text(stringResource(R.string.rsx_backup_existing), style = MaterialTheme.typography.titleMedium)

            if (backups.isEmpty()) {
                Text(stringResource(R.string.rsx_backup_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(backups) { entry ->
                        BackupRow(
                            entry = entry,
                            onDelete = { pendingDelete = entry }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.rsx_backup_delete_title)) },
            text = { Text(stringResource(R.string.rsx_backup_delete_msg, entry.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { BackupManager.deleteBackup(entry) }
                        refresh()
                    }
                }) { Text(stringResource(R.string.rsx_backup_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.rsx_cancel)) } }
        )
    }

    if (showRestartHint) {
        AlertDialog(
            onDismissRequest = { showRestartHint = false },
            title = { Text(stringResource(R.string.rsx_backup_done)) },
            text = { Text(stringResource(R.string.rsx_backup_restore_desc)) },
            confirmButton = { TextButton(onClick = { showRestartHint = false }) { Text(stringResource(R.string.rsx_ok)) } },
            dismissButton = {}
        )
    }
}

@Composable
private fun BackupRow(entry: BackupEntry, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.fileName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "%.1f KB".format(entry.sizeBytes / 1024.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.rsx_backup_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
