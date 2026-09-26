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
 * 设置 Tab（ReShizukuX beta1 功能优先版）：
 *
 * 四组布局：常规 / 安全 / 界面 / 高级。
 * 每组包含核心开关 + 12 项回归功能的入口行。
 * 入口行点击后导航到对应全屏页面（由各功能实现）。
 */
@Composable
fun SettingsTab(
    onNavigate: (String) -> Unit = {}
) {
    val context = LocalContext.current

    var startOnBoot by remember { mutableStateOf(ShizukuSettings.getStartOnBoot(context)) }
    var watchdog by remember { mutableStateOf(ShizukuSettings.getWatchdog()) }
    var wifiReassert by remember { mutableStateOf(ShizukuSettings.isWifiDebugReassertEnabled()) }
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

        // === 常规 ===
        SectionHeader("常规")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingSwitch(
                    title = "开机自启",
                    subtitle = "设备启动后自动拉起 Shizuku 服务",
                    checked = startOnBoot,
                    onCheckedChange = { startOnBoot = it; ShizukuSettings.setStartOnBoot(context, it) }
                )
                SettingSwitch(
                    title = "守护模式",
                    subtitle = "服务掉线后定时检查自动恢复",
                    checked = watchdog,
                    onCheckedChange = { watchdog = it; ShizukuSettings.setWatchdog(context, it) }
                )
                SettingSwitch(
                    title = "无线调试守护",
                    subtitle = "部分系统开机后会关闭无线调试，开启后自动恢复",
                    checked = wifiReassert,
                    onCheckedChange = { wifiReassert = it; ShizukuSettings.setWifiDebugReassertEnabled(it) }
                )
                SettingSwitch(
                    title = "强制无线 ADB",
                    subtitle = "启动时强制走无线调试通道（wadb）",
                    checked = forceWadb,
                    onCheckedChange = { forceWadb = it; ShizukuSettings.setForceStartWadbEnabled(it) }
                )
                SettingRow(title = "检查更新", subtitle = "OTA 自动更新", onClick = { onNavigate("ota") })
                SettingRow(title = "语言", subtitle = "应用显示语言", onClick = { onNavigate("language") })
            }
        }

        // === 安全 ===
        SectionHeader("安全")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(title = "生物识别锁", subtitle = "启动应用时需要指纹/面容验证", onClick = { onNavigate("biometric") })
                SettingRow(title = "崩溃上报", subtitle = "匿名上报崩溃帮助改进（Sentry）", onClick = { onNavigate("sentry") })
                SettingRow(title = "无障碍保活", subtitle = "通过无障碍服务保持后台活跃", onClick = { onNavigate("accessibility") })
            }
        }

        // === 界面 ===
        SectionHeader("界面")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(title = "主题个性化", subtitle = "自定义配色与外观", onClick = { onNavigate("theme") })
                SettingRow(title = "动画效果", subtitle = "Lottie 动画与过渡效果", onClick = { onNavigate("lottie") })
                SettingRow(title = "桌面小部件", subtitle = "添加 Shizuku 状态小部件到桌面", onClick = { onNavigate("widget") })
            }
        }

        // === 高级 ===
        SectionHeader("高级")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingRow(title = "备份与恢复", subtitle = "备份设置与模块数据", onClick = { onNavigate("backup") })
                SettingRow(title = "AI 助手", subtitle = "脚本追踪与智能解释", onClick = { onNavigate("ai") })
                SettingRow(title = "自动化规则", subtitle = "管理自动化规则与触发器", onClick = { onNavigate("automation") })
                SettingRow(title = "更新日志", subtitle = "查看版本更新内容（Markdown）", onClick = { onNavigate("changelog") })
                SettingRow(title = "旧版完整设置", subtitle = "进入传统设置页面", onClick = { onNavigate("old_settings") })
            }
        }

        // === 关于 ===
        SectionHeader("关于")
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "ReShizukuX", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "免 Root 模块系统 · 功能优先版",
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
