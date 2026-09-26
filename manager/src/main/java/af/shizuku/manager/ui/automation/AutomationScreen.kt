package af.shizuku.manager.ui.automation

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.automation.AppProfilesActivity
import kotlinx.coroutines.launch

/**
 * 自动化 Tab 独立全屏页面（ReShizukuX beta1 组B）。
 *
 * 从原 [af.shizuku.manager.ui.AutomationTab] 升级为独立路由页面：
 *  - 规则列表（网络防火墙规则 / 应用配置文件规则），启用状态由 remember mutableStateMapOf 本地管理。
 *  - 「管理应用配置文件」跳转 View 体系的 [AppProfilesActivity]。
 *  - 「新建规则」占位按钮，提示规则引擎扩展开发中。
 *
 * 集成阶段：底部导航不再包含自动化 Tab，SettingsTab「自动化规则」行 onNavigate("automation")
 * 路由到本 Screen；[af.shizuku.manager.ui.AutomationTab] 保留不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val enabledMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            defaultRules.forEach { put(it.registeredName, true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动化") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        snackbarHostState.showSnackbar("规则引擎扩展开发中")
                    }
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建规则") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
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
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp)
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
