package af.shizuku.manager.ui

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.settings.DeviceOwnerHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 状态 Tab（首页）：单开关驱动 ShizukuX Portable 的 11 步 ON / 5 步 OFF 全流程。
 *
 * - ON  -> [PortableStartOrchestrator.startService]（Root / ADB / Dhizuku 自动检测）
 * - OFF -> 确认对话框 -> [PortableStartOrchestrator.stopService]
 *
 * 状态来源复用全局 [ShizukuStateMachine]；步骤进度、错误提示、激活模式、守护模式均在本卡片渲染。
 * 新增权限卡：实时显示 ADB / 设备所有者(DO) / Root 三项权限可用性。
 * 启动过程中开关禁用，防止重复点击。
 */

// Root 权限三态：已授权 -> 可用；设备有 root 但未授予本应用 -> 未授权；无 root -> 不可用。
private enum class PermState { AVAILABLE, NOT_GRANTED, UNAVAILABLE }
private data class PermissionItem(@StringRes val labelRes: Int, val state: PermState)
@Composable
fun HomeTab(
    onPairingRequired: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ShizukuStateMachine 是全局单例，asFlow() 会立刻补发当前状态。
    val state by ShizukuStateMachine.asFlow()
        .collectAsState(initial = ShizukuStateMachine.get())

    // 每次进入页面时校正一次状态（捕获崩溃/掉线等边角情况）。
    LaunchedEffect(Unit) {
        ShizukuStateMachine.update()
    }

    val running = state == ShizukuStateMachine.State.RUNNING
    val statusText = when (state) {
        ShizukuStateMachine.State.RUNNING -> stringResource(R.string.rsx_home_running)
        ShizukuStateMachine.State.STARTING -> stringResource(R.string.rsx_home_starting)
        ShizukuStateMachine.State.STOPPING -> stringResource(R.string.rsx_home_stopping)
        ShizukuStateMachine.State.CRASHED -> stringResource(R.string.rsx_home_crashed)
        ShizukuStateMachine.State.STOPPED -> stringResource(R.string.rsx_home_not_running)
    }

    // --- orchestrator UI state ---
    var isWorking by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(0) }
    var stepTitle by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showStopConfirm by remember { mutableStateOf(false) }

    // Activation mode label, refreshed whenever the state settles to RUNNING/STOPPED.
    var activationMode by remember { mutableStateOf("") }
    LaunchedEffect(running, state) {
        activationMode = when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> "Root"
            ShizukuSettings.LaunchMethod.ADB -> context.getString(R.string.rsx_home_activation_adb)
            ShizukuSettings.LaunchMethod.DHIZUKU -> "Dhizuku"
            else -> context.getString(R.string.rsx_home_activation_none)
        }
    }

    // --- 权限卡：ADB / 设备所有者(DO) / Root 可用性检测（IO 线程）---
    fun isPackageInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    val permissions by produceState<List<PermissionItem>>(initialValue = emptyList(), Unit) {
        value = withContext(Dispatchers.IO) {
            val adb = runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 ||
                    Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 ||
                    EnvironmentUtils.getAdbTcpPort() > 0
            }.getOrDefault(false)
            val doOwner = runCatching { DeviceOwnerHelper.isDeviceOwner(context) }.getOrDefault(false)
            // Root 实测：libsu 检查本应用是否已被授予 root（KernelSU/Magisk 通用）。
            // KernelSU 的 su 是 overlay 挂载，普通应用 stat 不到，文件检测全部失效，
            // 故以「应用已授权」为主、以「root 管理器已安装」作为设备级 root 证据。
            val rootGranted = runCatching {
                com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
            }.getOrDefault(false)
            val rootMgrInstalled = listOf(
                "com.rosan.installer.x.revived", // KernelSU (Rosan)
                "me.weishu.kernelsu",            // 旧版 KernelSU
                "com.rifsxd.ksu.next",           // KernelSU-Next
                "com.topjohnwu.magisk",          // Magisk
                "me.bmax.apatch"                 // APatch
            ).any { isPackageInstalled(it) }
            val rootState = when {
                rootGranted -> PermState.AVAILABLE
                rootMgrInstalled -> PermState.NOT_GRANTED
                else -> PermState.UNAVAILABLE
            }
            listOf(
                PermissionItem(R.string.rsx_home_perm_adb, if (adb) PermState.AVAILABLE else PermState.UNAVAILABLE),
                PermissionItem(R.string.rsx_home_perm_do, if (doOwner) PermState.AVAILABLE else PermState.UNAVAILABLE),
                PermissionItem(R.string.rsx_home_perm_root, rootState)
            )
        }
    }

    fun onToggle(wantOn: Boolean) {
        if (isWorking) return
        errorMessage = null
        when {
            wantOn && !running -> {
                isWorking = true
                currentStep = 1
                scope.launch {
                    val result = PortableStartOrchestrator.startService(context) { step, title ->
                        currentStep = step
                        stepTitle = title
                    }
                    isWorking = false
                    currentStep = 0
                    stepTitle = ""
                    when {
                        result.success -> ShizukuStateMachine.update()
                        result.pairingRequired -> {
                            // No Root / never-paired ADB: hand off to the full Compose wizard.
                            errorMessage = null
                            onPairingRequired()
                        }
                        else -> errorMessage = result.error ?: context.getString(R.string.rsx_home_start_failed)
                    }
                }
            }
            !wantOn && running -> {
                showStopConfirm = true
            }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(stringResource(R.string.rsx_home_stop_confirm_title)) },
            text = { Text(stringResource(R.string.rsx_home_stop_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    isWorking = true
                    errorMessage = null
                    scope.launch {
                        PortableStartOrchestrator.stopService(context)
                        isWorking = false
                        ShizukuStateMachine.update()
                    }
                }) { Text(stringResource(R.string.rsx_home_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text(stringResource(R.string.rsx_home_cancel)) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.rsx_home_title),
            style = MaterialTheme.typography.headlineSmall
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = if (running) stringResource(R.string.rsx_home_ready)
                    else if (isWorking) stringResource(R.string.rsx_home_starting)
                    else stringResource(R.string.rsx_home_hint),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Step progress indicator.
                if (isWorking && currentStep in 1..11) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.rsx_home_step, currentStep),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (stepTitle.isNotEmpty()) {
                                Text(
                                    text = stepTitle,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Error message.
                errorMessage?.let { msg ->
                    Text(
                        text = stringResource(R.string.rsx_home_error, msg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(onClick = { errorMessage = null }) {
                        Text(stringResource(R.string.rsx_home_ok))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.rsx_home_start),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = running,
                        enabled = !isWorking,
                        onCheckedChange = { wantOn -> onToggle(wantOn) }
                    )
                }
            }
        }

        // Activation mode line.
        Text(
            text = stringResource(R.string.rsx_home_activation, activationMode),
            style = MaterialTheme.typography.bodySmall
        )
        // Guard mode line: root launch + daemon setting on => the :daemon process polls /proc and
        // relaunches the server; otherwise degrade to the Alarm/foreground watchdog.
        val guardLabel = when {
            running && ShizukuSettings.isDaemonEnabled() &&
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT ->
                stringResource(R.string.rsx_home_guard_dual)
            else -> stringResource(R.string.rsx_home_guard_selfheal)
        }
        Text(
            text = stringResource(R.string.rsx_home_guard, guardLabel),
            style = MaterialTheme.typography.bodySmall
        )

        // --- 权限卡：ADB / 设备所有者 / Root ---
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.rsx_home_permissions_title),
                    style = MaterialTheme.typography.titleMedium
                )
                if (permissions.isEmpty()) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                } else {
                    permissions.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(p.labelRes),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val (stateText, stateColor) = when (p.state) {
                                PermState.AVAILABLE ->
                                    stringResource(R.string.rsx_home_perm_available) to Color(0xFF4CAF50)
                                PermState.NOT_GRANTED ->
                                    stringResource(R.string.rsx_home_perm_not_granted) to Color(0xFFFF9800)
                                PermState.UNAVAILABLE ->
                                    stringResource(R.string.rsx_home_perm_unavailable) to
                                        MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = stateText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = stateColor
                            )
                        }
                    }
                }
            }
        }
    }
}
