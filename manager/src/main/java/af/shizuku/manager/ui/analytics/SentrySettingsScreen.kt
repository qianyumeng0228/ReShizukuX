package af.shizuku.manager.ui.analytics

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.analytics.SentryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 组C - Sentry 崩溃上报设置页。
 *
 * UI 入口：集成阶段由 SettingsTab 导航进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentrySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SentryManager.isEnabled(context)) }
    var showAdvanced by remember { mutableStateOf(false) }
    var dsn by remember { mutableStateOf(SentryManager.getDsn(context)) }
    val lastCrash = remember { SentryManager.getLastCrashTime(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rsx_sentry_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.rsx_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 总开关
            Card {
                Column(Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.rsx_sentry_enable), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.rsx_sentry_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                SentryManager.setEnabled(context, it)
                            }
                        )
                    }
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))

            // 上次崩溃
            Text(
                if (lastCrash > 0) {
                    stringResource(R.string.rsx_sentry_last_crash, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastCrash)))
                } else {
                    stringResource(R.string.rsx_sentry_last_crash_none)
                },
                style = MaterialTheme.typography.bodyMedium
            )

            androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))

            // 高级选项
            OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) stringResource(R.string.rsx_sentry_hide_advanced) else stringResource(R.string.rsx_sentry_advanced))
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = dsn,
                    onValueChange = { dsn = it },
                    label = { Text(stringResource(R.string.rsx_sentry_dsn)) },
                    modifier = Modifier.fillMaxSize()
                )
                OutlinedButton(onClick = {
                    SentryManager.setDsn(context, dsn)
                    Toast.makeText(context, context.getString(R.string.rsx_sentry_dsn_saved), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.rsx_sentry_save_dsn))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // 测试按钮：构造一个异常并走上报链路（不真正崩溃进程）
            OutlinedButton(onClick = {
                val test = RuntimeException("Sentry test @ ${System.currentTimeMillis()}")
                SentryManager.captureException(test)
                Toast.makeText(context, if (enabled) context.getString(R.string.rsx_sentry_test_sent) else context.getString(R.string.rsx_sentry_disabled), Toast.LENGTH_SHORT).show()
            }, enabled = enabled) {
                Text(stringResource(R.string.rsx_sentry_send_test))
            }
        }
    }
}
