package af.shizuku.manager.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.automation.AppProfilesActivity

/**
 * 自动化 Tab（Compose 实现）：规则列表 + 启用开关 + 应用配置文件入口。
 *
 * 复用关系（不改动 AutomationEngine / AutomationRules / AppProfilesActivity 现有逻辑）：
 *  - 规则名称与 [af.shizuku.manager.automation.AutomationRule.name] 对齐：
 *    NetworkFirewallRule / AppSpecificProfileRule，二者由
 *    [af.shizuku.manager.automation.registerDefaultRules] 在 ShizukuApplication 启动时
 *    注册进 AutomationEngine。
 *  - AutomationEngine 当前未暴露运行时启用/禁用 API，开关状态由本界面用
 *    [mutableStateMapOf] 本地管理（仅作展示）；事件派发仍由引擎在 Shizuku 服务
 *    运行时统一进行，本 Tab 不触碰引擎。
 *  - 「管理应用配置文件」按钮跳转至 View 体系的 [AppProfilesActivity]。
 *  - locale/ 下的 Tasker 插件（Action/Condition 编辑 Activity）由 Tasker 侧调用，
 *    本界面不提供入口。
 */
@Composable
fun AutomationTab() {
    val context = LocalContext.current

    // 规则启用状态：默认全部启用（与默认注册一致）。引擎无运行时禁用 API，故仅 UI 层记录。
    val enabledMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            defaultRules.forEach { put(it.registeredName, true) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "自动化",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "自动化规则在 Shizuku 服务运行时生效。规则引擎监听网络变化和前台应用切换。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        item {
            SectionHeader("规则")
        }

        if (defaultRules.isEmpty()) {
            item {
                Text(
                    text = "暂无规则",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
            }
        } else {
            items(defaultRules, key = { it.registeredName }) { rule ->
                val enabled = enabledMap[rule.registeredName] ?: true
                RuleCard(
                    title = rule.title,
                    description = rule.description,
                    status = if (enabled) "已启用" else "已禁用",
                    registeredName = rule.registeredName,
                    checked = enabled,
                    onCheckedChange = { enabledMap[rule.registeredName] = it }
                )
            }
        }

        item {
            SectionHeader("应用配置文件")
        }

        item {
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, AppProfilesActivity::class.java))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("管理应用配置文件")
            }
        }

        item {
            Text(
                text = "默认规则在应用启动时注册进 AutomationEngine；开关为界面状态，事件派发由 Shizuku 服务驱动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
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
private fun RuleCard(
    title: String,
    description: String,
    status: String,
    registeredName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
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
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$status · $registeredName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private data class AutomationRuleUi(
    val registeredName: String,
    val title: String,
    val description: String
)

/**
 * 与 AutomationRules.kt 中注册的默认规则一一对应（registeredName 必须与
 * [af.shizuku.manager.automation.AutomationRule.name] 完全一致）。
 */
private val defaultRules = listOf(
    AutomationRuleUi(
        registeredName = "Network Firewall Rule",
        title = "网络防火墙规则",
        description = "安全网络时关闭 Binder 防火墙，不可信网络时开启"
    ),
    AutomationRuleUi(
        registeredName = "App Profile Rule",
        title = "应用配置文件规则",
        description = "前台应用变化时应用对应配置文件"
    )
)
