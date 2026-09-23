package af.shizuku.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings

/**
 * 设置 Tab（Compose 轻量版）：
 *
 * 只迁移 Portable 入口最常用的核心开关，全部读写复用 [ShizukuSettings] 的现有静态方法，
 * 不引入新的偏好键。完整设置项仍由旧版 SettingsActivity 承载，本 Tab 不替代它。
 *
 * - 开机自启：[ShizukuSettings.getStartOnBoot] / [ShizukuSettings.setStartOnBoot]
 * - 守护模式（看门狗）：[ShizukuSettings.getWatchdog] / [ShizukuSettings.setWatchdog]
 * - 敌意 ROM 重断言：[ShizukuSettings.isWifiDebugReassertEnabled] / [ShizukuSettings.setWifiDebugReassertEnabled]
 * - TCP 模式：[ShizukuSettings.getTcpMode] / [ShizukuSettings.setTcpMode]
 * - 强制无线 ADB：[ShizukuSettings.isForceStartWadbEnabled] / [ShizukuSettings.setForceStartWadbEnabled]
 *
 * 版本信息来自 [BuildConfig]。
 */
@Composable
fun SettingsTab() {
    val context = LocalContext.current

    // 初始值一次性读取；之后由 Switch 直接驱动写入并即时更新本地状态。
    var startOnBoot by remember { mutableStateOf(ShizukuSettings.getStartOnBoot(context)) }
    var watchdog by remember { mutableStateOf(ShizukuSettings.getWatchdog()) }
    var wifiReassert by remember { mutableStateOf(ShizukuSettings.isWifiDebugReassertEnabled()) }
    var tcpMode by remember { mutableStateOf(ShizukuSettings.getTcpMode()) }
    var forceWadb by remember { mutableStateOf(ShizukuSettings.isForceStartWadbEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
        )

        SectionHeader("服务")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingSwitch(
                    title = "开机自启",
                    subtitle = "设备启动后自动拉起 Shizuku 服务",
                    checked = startOnBoot,
                    onCheckedChange = {
                        startOnBoot = it
                        ShizukuSettings.setStartOnBoot(context, it)
                    }
                )
                SettingSwitch(
                    title = "守护模式",
                    subtitle = "看门狗服务与 Alarm 保活，掉线后自动恢复",
                    checked = watchdog,
                    onCheckedChange = {
                        watchdog = it
                        ShizukuSettings.setWatchdog(context, it)
                    }
                )
                SettingSwitch(
                    title = "敌意 ROM 重断言",
                    subtitle = "ROM 在开机/网络切换后清除无线调试时自动重写",
                    checked = wifiReassert,
                    onCheckedChange = {
                        wifiReassert = it
                        ShizukuSettings.setWifiDebugReassertEnabled(it)
                    }
                )
            }
        }

        SectionHeader("连接")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingSwitch(
                    title = "TCP 模式",
                    subtitle = "通过 TCP/ADB 端口而非 local abstract socket 连接",
                    checked = tcpMode,
                    onCheckedChange = {
                        tcpMode = it
                        ShizukuSettings.setTcpMode(it)
                    }
                )
                SettingSwitch(
                    title = "强制无线 ADB",
                    subtitle = "启动时强制走无线调试通道（wadb）",
                    checked = forceWadb,
                    onCheckedChange = {
                        forceWadb = it
                        ShizukuSettings.setForceStartWadbEnabled(it)
                    }
                )
            }
        }

        SectionHeader("关于")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "ShizukuX Portable",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "便携版入口：状态 / 授权 / 自动化 / 设置。完整设置仍可从旧版设置页进入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
