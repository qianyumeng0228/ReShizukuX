package af.shizuku.manager.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import io.reshizukux.modules.core.ModuleInfo
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.execution.ActionRunner
import io.reshizukux.modules.execution.CommandFilter
import io.reshizukux.modules.execution.FilterResult
import io.reshizukux.modules.execution.ServiceRunner
import io.reshizukux.modules.permission.PermissionController
import io.reshizukux.modules.permission.PermissionFlag
import io.reshizukux.modules.permission.PermissionLevel
import io.reshizukux.modules.webui.ModuleWebUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块详情页（P6，设计方案 §4.3 ModulesScreen 详情子页）。
 *
 * 由 ModulesTab 通过 selectedModuleId 状态切换进入（不引入 NavHost）：
 * 非 null 时本 Composable 全屏替换列表，返回时置 null。
 *
 * 功能：
 *  - 基本信息卡片（版本/作者/描述/安装时间/权限档位/签名状态/服务 pid）
 *  - 操作区：启用/停用、运行 action（流式输出 + HIGH 风险确认）、打开 WebUI、查看日志、
 *    修复篡改（CORRUPTED 时）、卸载（确认对话框）
 *  - 权限设置卡片：SAFE/CUSTOM/FULL + CUSTOM 逐开关
 *  - 服务状态卡片（有 service.sh 时）：运行中/已停止 + pid + 重启按钮
 *
 * 所有模块操作在 Dispatchers.IO 执行；onOutput 回调追加到 SnapshotStateList。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleDetailScreen(
    moduleId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 每次操作后自增，触发重新拉取 ModuleInfo（DB → ModuleInfo 是即时读）。
    var refreshKey by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<ModuleInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 权限档位 + CUSTOM 逐开关（本地快照，切换即写 PermissionController）。
    var permLevel by remember { mutableStateOf(PermissionLevel.SAFE) }

    // 运行 action 对话框状态
    var showActionDialog by remember { mutableStateOf(false) }
    val actionLines = remember { mutableStateListOf<String>() }
    var actionRunning by remember { mutableStateOf(false) }
    var pendingHighRisk by remember { mutableStateOf<FilterResult?>(null) }
    var highRiskDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    // 日志对话框状态
    var showLogDialog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var logTitle by remember { mutableStateOf("") }

    // 卸载确认
    var showUninstallConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(moduleId, refreshKey) {
        info = withContext(Dispatchers.IO) { ModuleManager.getModule(moduleId) }
        permLevel = withContext(Dispatchers.IO) { PermissionController.getPermissionLevel(moduleId) }
    }

    val module = info
    if (module == null) {
        // 模块已被卸载（例如刚 uninstall 后返回）。
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.rsx_md_not_found), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.rsx_back)) }
        }
        return
    }

    val dir = ModuleManager.getModuleDir(moduleId)
    val hasActionScript = File(dir, "action.sh").exists()
    val hasServiceScript = File(dir, "service.sh").exists()
    val webUiAvailable = ModuleWebUi.canOpenWebUI(moduleId)
    val signed = !module.publicKey.isNullOrBlank()

    fun refresh() { refreshKey++ }

    fun toggleEnable(wantEnabled: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (wantEnabled) ModuleManager.enable(moduleId) else ModuleManager.disable(moduleId)
            }
            busy = false
            message = if (ok) null else if (wantEnabled) context.getString(R.string.rsx_md_enable_failed) else context.getString(R.string.rsx_md_disable_failed)
            refresh()
        }
    }

    fun startServiceRestart() {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                ServiceRunner.stopService(moduleId)
                if (PermissionController.canService(moduleId)) {
                    ServiceRunner.startService(moduleId)
                }
            }
            busy = false
            refresh()
        }
    }

    fun runAction() {
        if (actionRunning) return
        actionLines.clear()
        showActionDialog = true
        actionRunning = true
        scope.launch {
            // 预检 HIGH 风险（在 IO 读脚本 + 过滤），命中则切到 UI 弹确认并等待用户决定。
            val proceed = withContext(Dispatchers.IO) {
                val script = runCatching { File(dir, "action.sh").readText(Charsets.UTF_8) }.getOrDefault("")
                val filter = CommandFilter.filter(script)
                if (filter.hasHighRisk) {
                    val deferred = CompletableDeferred<Boolean>()
                    highRiskDeferred = deferred
                    pendingHighRisk = filter
                    deferred.await()
                } else true
            }
            if (!proceed) {
                actionRunning = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                ActionRunner.runAction(
                    moduleId = moduleId,
                    onOutput = { line -> actionLines.add(line) },
                    onHighRisk = { true } // 预检已确认过
                )
            }
            actionLines.add("——————————")
            actionLines.add(if (result.timedOut) context.getString(R.string.rsx_md_exit_code_timeout, result.exitCode) else context.getString(R.string.rsx_md_exit_code, result.exitCode))
            if (result.stderr.isNotBlank()) actionLines.add("stderr: ${result.stderr}")
            actionRunning = false
            refresh()
        }
    }

    fun showLog(isCustomize: Boolean) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                if (isCustomize) ModuleManager.getCustomizeLog(moduleId)
                else ModuleManager.getActionLog(moduleId)
            } ?: context.getString(R.string.rsx_md_no_logs)
            logTitle = if (isCustomize) context.getString(R.string.rsx_md_install_log_title) else context.getString(R.string.rsx_md_action_log_title)
            logText = text
            showLogDialog = true
        }
    }

    fun doUninstall() {
        showUninstallConfirm = false
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ModuleManager.uninstall(moduleId) }
            busy = false
            if (ok) {
                onBack()
            } else {
                message = context.getString(R.string.rsx_md_uninstall_failed)
            }
        }
    }

    fun doRepair() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ModuleManager.repair(moduleId) }
            busy = false
            message = if (ok) context.getString(R.string.rsx_md_repaired) else context.getString(R.string.rsx_md_repair_failed)
            refresh()
        }
    }

    // --- 运行 action 输出对话框 ---
    if (showActionDialog) {
        val listState = rememberLazyListState()
        LaunchedEffect(actionLines.size) {
            if (actionLines.isNotEmpty()) {
                delay(16)
                runCatching { listState.animateScrollToItem(actionLines.size - 1) }
            }
        }
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text(stringResource(R.string.rsx_md_run_action_sh)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 8.dp)
                    ) {
                        if (actionLines.isEmpty()) {
                            Text(stringResource(R.string.rsx_md_waiting_output), style = MaterialTheme.typography.bodySmall)
                        } else {
                            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(actionLines) { line ->
                                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (actionRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                            Text(stringResource(R.string.rsx_md_executing), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActionDialog = false }, enabled = !actionRunning) { Text(stringResource(R.string.rsx_md_close)) } },
            dismissButton = {}
        )
    }

    // --- HIGH 风险确认对话框 ---
    pendingHighRisk?.let { fr ->
        AlertDialog(
            onDismissRequest = {
                highRiskDeferred?.complete(false)
                pendingHighRisk = null
            },
            title = { Text(stringResource(R.string.rsx_md_high_risk_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.rsx_md_high_risk_msg), style = MaterialTheme.typography.bodyMedium)
                    fr.matchedLines.take(8).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    highRiskDeferred?.complete(true)
                    pendingHighRisk = null
                }) { Text(stringResource(R.string.rsx_md_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    highRiskDeferred?.complete(false)
                    pendingHighRisk = null
                }) { Text(stringResource(R.string.rsx_cancel)) }
            }
        )
    }

    // --- 日志对话框 ---
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text(logTitle) },
            text = {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(logText.ifBlank { context.getString(R.string.rsx_md_no_logs) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLogDialog = false }) { Text(stringResource(R.string.rsx_md_close)) } },
            dismissButton = {}
        )
    }

    // --- 卸载确认对话框 ---
    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.rsx_md_uninstall_confirm_title)) },
            text = { Text(stringResource(R.string.rsx_md_uninstall_confirm_msg, module.name)) },
            confirmButton = { TextButton(onClick = { doUninstall() }) { Text(stringResource(R.string.rsx_md_uninstall), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showUninstallConfirm = false }) { Text(stringResource(R.string.rsx_cancel)) } }
        )
    }

    // ============================ UI ============================
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(module.name, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(af.shizuku.manager.R.drawable.ic_back_24), contentDescription = stringResource(R.string.rsx_back))
                }
            },
            actions = {
                StatusBadge(module.state, modifier = Modifier.padding(end = 12.dp))
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- 基本信息卡片 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(module.name, style = MaterialTheme.typography.titleLarge)
                    }
                    InfoRow(stringResource(R.string.rsx_md_version), "${module.version} (${module.versionCode})")
                    InfoRow(stringResource(R.string.rsx_md_author), module.author)
                    InfoRow(stringResource(R.string.rsx_md_installed_time), SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(module.installTime)))
                    InfoRow(stringResource(R.string.rsx_md_permission_tier), permLevel.name)
                    InfoRow(stringResource(R.string.rsx_md_signature), if (signed) stringResource(R.string.rsx_md_status_verified) else stringResource(R.string.rsx_md_status_unverified))
                    if (hasServiceScript) {
                        val pid = ServiceRunner.getServicePid(moduleId)
                        InfoRow(stringResource(R.string.rsx_md_service_process), if (pid != null) stringResource(R.string.rsx_md_running_pid, pid) else stringResource(R.string.rsx_md_status_stopped))
                    }
                    if (module.description.isNotBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(module.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            message?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // --- 启用开关 + 操作区 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.rsx_md_enable_module), style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = module.state == ModuleState.ENABLED,
                            enabled = !busy && module.state != ModuleState.CORRUPTED,
                            onCheckedChange = { toggleEnable(it) }
                        )
                    }
                    if (module.state == ModuleState.CORRUPTED) {
                        Text(stringResource(R.string.rsx_md_tampered), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { doRepair() }, enabled = !busy) { Text(stringResource(R.string.rsx_md_fix_tamper)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasActionScript) {
                            OutlinedButton(onClick = { runAction() }, enabled = !busy && module.state == ModuleState.ENABLED && PermissionController.canAction(moduleId)) {
                                Text(stringResource(R.string.rsx_md_run_action))
                            }
                        }
                        if (webUiAvailable) {
                            OutlinedButton(onClick = { ModuleWebUi.start(context, moduleId) }) { Text(stringResource(R.string.rsx_md_open_webui)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showLog(isCustomize = false) }) { Text(stringResource(R.string.rsx_md_action_log)) }
                        TextButton(onClick = { showLog(isCustomize = true) }) { Text(stringResource(R.string.rsx_md_install_log)) }
                    }
                    HorizontalDivider()
                    Button(
                        onClick = { showUninstallConfirm = true },
                        enabled = !busy,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) { Text(stringResource(R.string.rsx_md_uninstall_module)) }
                }
            }

            // --- 权限设置卡片 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rsx_md_permission_settings), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.rsx_md_new_module_safe), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PermissionLevel.entries.forEach { level ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = permLevel == level,
                                onClick = {
                                    permLevel = level
                                    scope.launch(Dispatchers.IO) { PermissionController.setPermissionLevel(moduleId, level) }
                                }
                            )
                            Text(
                                when (level) {
                                    PermissionLevel.SAFE -> stringResource(R.string.rsx_md_safe)
                                    PermissionLevel.CUSTOM -> stringResource(R.string.rsx_md_custom)
                                    PermissionLevel.FULL -> stringResource(R.string.rsx_md_full)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (permLevel == PermissionLevel.CUSTOM) {
                        HorizontalDivider()
                        CustomSwitch(stringResource(R.string.rsx_md_run_action), PermissionFlag.ACTION, moduleId)
                        CustomSwitch(stringResource(R.string.rsx_md_background_service), PermissionFlag.SERVICE, moduleId)
                        CustomSwitch("WebUI JS Bridge", PermissionFlag.WEB_BRIDGE, moduleId)
                        CustomSwitch(stringResource(R.string.rsx_md_network_download), PermissionFlag.DOWNLOAD, moduleId)
                    }
                }
            }

            // --- 服务状态卡片 ---
            if (hasServiceScript) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.rsx_md_background_service), style = MaterialTheme.typography.titleMedium)
                        val pid = ServiceRunner.getServicePid(moduleId)
                        Text(
                            if (pid != null) stringResource(R.string.rsx_md_running_pid, pid) else stringResource(R.string.rsx_md_status_stopped),
                            color = if (pid != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { startServiceRestart() }, enabled = !busy && module.state == ModuleState.ENABLED) {
                            Text(stringResource(R.string.rsx_md_restart_service))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CustomSwitch(label: String, flag: Int, moduleId: String) {
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(PermissionController.getCustomFlags(moduleId) and flag != 0) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                scope.launch(Dispatchers.IO) { PermissionController.setCustomFlag(moduleId, flag, it) }
            }
        )
    }
}

@Composable
private fun StatusBadge(state: ModuleState, modifier: Modifier = Modifier) {
    val (text, color) = when (state) {
        ModuleState.ENABLED -> stringResource(R.string.rsx_md_status_enabled) to Color(0xFF4CAF50)
        ModuleState.DISABLED -> stringResource(R.string.rsx_md_status_disabled) to Color.Gray
        ModuleState.ERROR -> stringResource(R.string.rsx_md_error) to MaterialTheme.colorScheme.error
        ModuleState.CORRUPTED -> stringResource(R.string.rsx_md_status_corrupted) to Color(0xFFFF9800)
        ModuleState.UPDATING -> stringResource(R.string.rsx_md_updating) to Color(0xFF2196F3)
        ModuleState.NOT_INSTALLED -> stringResource(R.string.rsx_md_status_not_installed) to Color.Gray
    }
    AssistChip(
        onClick = {},
        label = { Text(text, color = color, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier
    )
}
