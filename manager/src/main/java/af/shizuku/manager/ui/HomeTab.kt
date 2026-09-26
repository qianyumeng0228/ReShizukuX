package af.shizuku.manager.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.launch

/**
 * 状态 Tab（首页）：单开关驱动 ShizukuX Portable 的 11 步 ON / 5 步 OFF 全流程。
 *
 * - ON  -> [PortableStartOrchestrator.startService]（Root / ADB / Dhizuku 自动检测）
 * - OFF -> 确认对话框 -> [PortableStartOrchestrator.stopService]
 *
 * 状态来源复用全局 [ShizukuStateMachine]；步骤进度、错误提示、激活模式、守护模式均在本卡片渲染。
 * 启动过程中开关禁用，防止重复点击。
 */
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
    }
}
