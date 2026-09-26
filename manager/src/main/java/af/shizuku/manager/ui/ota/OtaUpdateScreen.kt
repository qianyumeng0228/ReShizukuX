package af.shizuku.manager.ui.ota

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ota.OtaUpdateManager
import af.shizuku.manager.ota.OtaUpdateManager.CheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OTA 自动更新全屏页面（ReShizukuX beta1 组A）。
 *
 * 集成阶段由 SettingsTab "检查更新" 行导航到这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaUpdateScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf(context.getString(R.string.rsx_ota_not_checked)) }
    var remote by remember { mutableStateOf<OtaUpdateManager.RemoteVersion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (checking) return
        checking = true
        error = null
        statusText = context.getString(R.string.rsx_ota_checking2)
        remote = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { OtaUpdateManager.checkUpdate() }
            checking = false
            when (result) {
                is CheckResult.Available -> {
                    remote = result.remote
                    statusText = context.getString(R.string.rsx_ota_new, result.remote.latestVersion, result.remote.versionCode)
                }
                CheckResult.UpToDate -> statusText = context.getString(R.string.rsx_ota_latest)
                is CheckResult.Failed -> {
                    error = result.message
                    statusText = context.getString(R.string.rsx_ota_check_failed, result.message)
                }
            }
        }
    }

    fun downloadAndInstall() {
        val r = remote ?: return
        if (downloading) return
        downloading = true
        progress = 0f
        error = null
        scope.launch {
            try {
                val apk: File = withContext(Dispatchers.IO) {
                    OtaUpdateManager.downloadApk(context, r.downloadUrl) { p -> progress = p }
                }
                withContext(Dispatchers.Main) {
                    downloading = false
                    OtaUpdateManager.installApk(context, apk)
                }
            } catch (e: Exception) {
                downloading = false
                error = e.message ?: context.getString(R.string.rsx_ota_download_failed)
                statusText = context.getString(R.string.rsx_ota_download_failed_msg, error)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.rsx_ota_check)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(af.shizuku.manager.R.drawable.ic_back_24), contentDescription = stringResource(R.string.rsx_back))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.rsx_ota_current), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.rsx_ota_source, OtaUpdateManager.VERSION_JSON_URL),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (checking || downloading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                        }
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.rsx_ota_downloading, (progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            remote?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.rsx_ota_new_short, r.latestVersion), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.rsx_ota_version_code, r.versionCode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.changelog.isNotBlank()) {
                            Text(stringResource(R.string.rsx_ota_changelog), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(r.changelog, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { check() }, enabled = !checking && !downloading) {
                    Text(if (checking) stringResource(R.string.rsx_ota_checking) else stringResource(R.string.rsx_ota_check))
                }
                if (remote != null && !downloading) {
                    OutlinedButton(onClick = { downloadAndInstall() }) {
                        Text(stringResource(R.string.rsx_ota_download))
                    }
                }
            }

            Text(
                stringResource(R.string.rsx_ota_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
