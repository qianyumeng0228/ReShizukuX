package af.shizuku.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.settings.DeviceOwnerHelper
import af.shizuku.manager.settings.DeviceOwnerProvisioner
import kotlinx.coroutines.launch

/**
 * 设置 Tab（ReShizukuX beta2 多语言版）：
 *
 * 四组布局：常规 / 安全 / 界面 / 高级。
 * 每组包含核心开关 + 12 项回归功能的入口行。
 * 入口行点击后导航到对应全屏页面（由各功能实现）。
 * 所有文案走 stringResource，随应用语言切换。
 */
@Composable
fun SettingsTab(
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var startOnBoot by remember { mutableStateOf(ShizukuSettings.getStartOnBoot(context)) }
    var watchdog by remember { mutableStateOf(ShizukuSettings.getWatchdog()) }
    var wifiReassert by remember { mutableStateOf(ShizukuSettings.isWifiDebugReassertEnabled()) }
    var forceWadb by remember { mutableStateOf(ShizukuSettings.isForceStartWadbEnabled()) }

    // DO provisioning state
    var showDoConfirm by remember { mutableStateOf(false) }
    var doBusy by remember { mutableStateOf(false) }
    var doResult by remember { mutableStateOf<DeviceOwnerProvisioner.Result?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.rsx_settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
        )

        // === 常规 ===
        SectionHeader(stringResource(R.string.rsx_settings_group_general))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingSwitch(
                    title = stringResource(R.string.rsx_settings_start_on_boot),
                    subtitle = stringResource(R.string.rsx_settings_start_on_boot_desc),
                    checked = startOnBoot,
                    onCheckedChange = { startOnBoot = it; ShizukuSettings.setStartOnBoot(context, it) }
                )
                SettingSwitch(
                    title = stringResource(R.string.rsx_settings_watchdog),
                    subtitle = stringResource(R.string.rsx_settings_watchdog_desc),
                    checked = watchdog,
                    onCheckedChange = { watchdog = it; ShizukuSettings.setWatchdog(context, it) }
                )
                SettingSwitch(
                    title = stringResource(R.string.rsx_settings_wifi_reassert),
                    subtitle = stringResource(R.string.rsx_settings_wifi_reassert_desc),
                    checked = wifiReassert,
                    onCheckedChange = { wifiReassert = it; ShizukuSettings.setWifiDebugReassertEnabled(it) }
                )
                SettingSwitch(
                    title = stringResource(R.string.rsx_settings_force_wadb),
                    subtitle = stringResource(R.string.rsx_settings_force_wadb_desc),
                    checked = forceWadb,
                    onCheckedChange = { forceWadb = it; ShizukuSettings.setForceStartWadbEnabled(it) }
                )
                SettingRow(title = stringResource(R.string.rsx_settings_check_update), subtitle = stringResource(R.string.rsx_settings_check_update_desc), onClick = { onNavigate("ota") })
                SettingRow(title = stringResource(R.string.rsx_settings_language), subtitle = stringResource(R.string.rsx_settings_language_desc), onClick = { onNavigate("language") })
            }
        }

        // === 安全 ===
        SectionHeader(stringResource(R.string.rsx_settings_group_security))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(title = stringResource(R.string.rsx_settings_biometric), subtitle = stringResource(R.string.rsx_settings_biometric_desc), onClick = { onNavigate("biometric") })
                SettingRow(title = stringResource(R.string.rsx_settings_crash_report), subtitle = stringResource(R.string.rsx_settings_crash_report_desc), onClick = { onNavigate("sentry") })
                SettingRow(title = stringResource(R.string.rsx_settings_accessibility), subtitle = stringResource(R.string.rsx_settings_accessibility_desc), onClick = { onNavigate("accessibility") })
            }
        }

        // === 界面 ===
        SectionHeader(stringResource(R.string.rsx_settings_group_appearance))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(title = stringResource(R.string.rsx_settings_theme), subtitle = stringResource(R.string.rsx_settings_theme_desc), onClick = { onNavigate("theme") })
                SettingRow(title = stringResource(R.string.rsx_settings_lottie), subtitle = stringResource(R.string.rsx_settings_lottie_desc), onClick = { onNavigate("lottie") })
                SettingRow(title = stringResource(R.string.rsx_settings_widget), subtitle = stringResource(R.string.rsx_settings_widget_desc), onClick = { onNavigate("widget") })
            }
        }

        // === 高级 ===
        SectionHeader(stringResource(R.string.rsx_settings_group_advanced))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(title = stringResource(R.string.rsx_settings_backup), subtitle = stringResource(R.string.rsx_settings_backup_desc), onClick = { onNavigate("backup") })
                SettingRow(title = stringResource(R.string.rsx_settings_ai), subtitle = stringResource(R.string.rsx_settings_ai_desc), onClick = { onNavigate("ai") })
                SettingRow(title = stringResource(R.string.rsx_settings_automation), subtitle = stringResource(R.string.rsx_settings_automation_desc), onClick = { onNavigate("automation") })
                SettingRow(title = stringResource(R.string.rsx_settings_changelog), subtitle = stringResource(R.string.rsx_settings_changelog_desc), onClick = { onNavigate("changelog") })
                SettingRow(title = stringResource(R.string.rsx_settings_old), subtitle = stringResource(R.string.rsx_settings_old_desc), onClick = { onNavigate("old_settings") })
                val doActive = remember { mutableStateOf(DeviceOwnerHelper.isDeviceOwner(context)) }
                SettingRow(
                    title = stringResource(R.string.rsx_settings_do_activate) +
                        if (doActive.value) " ✓" else "",
                    subtitle = stringResource(R.string.rsx_settings_do_activate_desc),
                    onClick = { if (!doBusy) showDoConfirm = true }
                )
            }
        }

        // === 关于 ===
        SectionHeader(stringResource(R.string.rsx_settings_group_about))
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = stringResource(R.string.rsx_settings_about_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.rsx_settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.rsx_settings_about_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // === DO provisioning confirm dialog ===
    if (showDoConfirm) {
        AlertDialog(
            onDismissRequest = { showDoConfirm = false },
            title = { Text(stringResource(R.string.rsx_do_confirm_title)) },
            text = { Text(stringResource(R.string.rsx_do_confirm_msg)) },
            confirmButton = {
                Button(onClick = {
                    showDoConfirm = false
                    doBusy = true
                    scope.launch {
                        val r = DeviceOwnerProvisioner.provision(context)
                        doResult = r
                        doBusy = false
                    }
                }) { Text(stringResource(R.string.rsx_do_proceed)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDoConfirm = false }) {
                    Text(stringResource(R.string.rsx_do_cancel))
                }
            }
        )
    }

    // === DO result dialog ===
    doResult?.let { r ->
        AlertDialog(
            onDismissRequest = { doResult = null },
            title = { Text(stringResource(R.string.rsx_do_result_title)) },
            text = { Text(r.message) },
            confirmButton = {
                Button(onClick = { doResult = null }) { Text(stringResource(R.string.rsx_do_ok)) }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(text = "›", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
