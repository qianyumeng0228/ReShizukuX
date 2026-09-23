package af.shizuku.manager.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbInvalidPairingCodeException
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.AdbKeyException
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.adb.AdbPairingClient
import af.shizuku.manager.adb.AdbPairingAccessibilityService
import af.shizuku.manager.adb.AdbPairingService
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.adb.LocalNetworkPermission
import af.shizuku.manager.adb.PairingSessionHolder
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.AdbPortProbe
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "PairingWizard"

/**
 * Compose 逐步 ADB 无线调试配对向导（5 步），用于 Portable 版无 Root 手机的首次配对。
 *
 * 复用（绝不重写 adb 协议层）：
 *  - [AdbMdns]  — mDNS 发现 `_adb-tls-pairing._tcp` 配对端口（与 AdbPairingService 同一套）
 *  - [AdbKey] / [PreferenceAdbKeyStore] — 加载/生成 ADB RSA 密钥
 *  - [AdbPairingClient] — SPAKE2+ 配对执行（JNI PairingContext）
 *  - [AdbStarter.startAdb] — 配对后连接 adb 端口并拉起 server
 *  - [Starter.waitForBinder] — 等待 Shizuku binder 就绪
 *
 * 与原版 [AdbPairingService] 的区别：配对码直接由本向导的 TextField 采集，不依赖通知 RemoteInput。
 *
 * @param onFinished 配对 + server 启动全部成功后回调（宿主关闭向导并刷新首页）
 * @param onCancel   用户随时取消回调（返回首页）
 */
@Composable
fun AdbPairingWizard(
    onFinished: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- wizard state ---
    var step by remember { mutableIntStateOf(1) }
    var pairingPort by remember { mutableIntStateOf(0) }
    var pairingHost by remember { mutableStateOf("127.0.0.1") }
    var pairCode by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var wadbOn by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
            } catch (e: Exception) { false }
        )
    }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    // mDNS instance holder for pairing-port discovery.
    var pairingMdns by remember { mutableStateOf<AdbMdns?>(null) }

    // --- Local Network permission (Android 16+ gates mDNS / local sockets) ---
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied; the ContentObserver / retry flow handles the rest */ }

    LaunchedEffect(Unit) {
        val perm = LocalNetworkPermission.required()
        if (perm != null && !LocalNetworkPermission.granted(context)) {
            permLauncher.launch(perm)
        }
    }

    // --- ContentObserver: watch adb_wifi_enabled so Step 1 auto-advances when user flips it ---
    DisposableEffect(Unit) {
        val resolver = context.contentResolver
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                wadbOn = try {
                    Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
                } catch (e: Exception) { false }
                // Refresh accessibility state too (user may have toggled it in system settings).
                accessibilityOn = isAccessibilityEnabled(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor("adb_wifi_enabled"), false, observer
        )
        onDispose {
            try { resolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        }
    }

    // --- Initialize PairingSessionHolder callback (service starts after port discovery) ---
    DisposableEffect(Unit) {
        PairingSessionHolder.portableMode = true
        PairingSessionHolder.resultCallback = { success, port, error ->
            scope.launch(Dispatchers.Main) {
                if (success) {
                    Timber.tag(TAG).i("Pairing succeeded via service, port=$port")
                    step = 5
                } else {
                    Timber.tag(TAG).w(error, "Pairing failed via service")
                    errorMessage = when (error) {
                        is AdbInvalidPairingCodeException -> "配对码错误，请重试"
                        is java.net.ConnectException -> "无法连接配对端口，请确认配对窗口仍打开"
                        else -> "配对失败：${error?.message ?: "未知错误"}"
                    }
                    pairCode = ""
                    step = 3
                }
            }
        }
        onDispose {
            PairingSessionHolder.clear()
            try {
                context.startService(
                    Intent(context, AdbPairingService::class.java).setAction("stop")
                )
            } catch (_: Exception) {}
        }
    }

    // --- Step 2: wizard's own mDNS discovers the pairing port first (avoids dual-mDNS conflict).
    //     Once found, start AdbPairingService so its notification RemoteInput appears. ---
    LaunchedEffect(step) {
        if (step == 2) {
            errorMessage = null
            pairingMdns = startPairingDiscovery(context) { port, host ->
                if (port > 0 && pairingPort <= 0) {
                    pairingPort = port
                    pairingHost = host
                    PairingSessionHolder.onPairingPortFound(port, host)
                    try {
                        context.startForegroundService(AdbPairingService.startIntent(context))
                    } catch (e: Throwable) {
                        Timber.tag(TAG).w(e, "Failed to start AdbPairingService")
                    }
                    step = 3
                }
            }
        }
    }

    // --- Cleanup pairing mDNS when leaving step 2/3/4 or on dispose ---
    DisposableEffect(step) {
        onDispose {
            if (step == 5 || step == 1) {
                pairingMdns?.stop()
                pairingMdns = null
            }
        }
    }

    // --- Step 1: when wadb turns on, first check if already paired (skip straight to start),
    //     otherwise advance to step 2 for pairing. ---
    LaunchedEffect(wadbOn) {
        if (step == 1 && wadbOn) {
            delay(300)
            // Fast-path: if the device was already paired (e.g. loopback probe failed on
            // Android 16 non-loopback binding but the key is authorized), discover the
            // connect port and jump straight to Step 5 — no need to re-pair.
            val alreadyPairedPort = discoverConnectPort(context)
            if (alreadyPairedPort != null && testAdbConnection(context, alreadyPairedPort)) {
                Timber.tag(TAG).i("Already paired on port $alreadyPairedPort, skipping pairing")
                step = 5
                return@LaunchedEffect
            }
            step = 2
        }
    }

    // --- Step 3: when 6 digits entered, send code to AdbPairingService for pairing ---
    LaunchedEffect(pairCode) {
        if (step == 3 && pairCode.length == 6 && !isBusy) {
            delay(200)
            isBusy = true
            errorMessage = null
            step = 4 // show "pairing in progress" UI while service works
            // Hand the code to the service — it runs AdbPairingClient and calls back via
            // PairingSessionHolder.resultCallback (set up above).
            try {
                val intent = AdbPairingService.dialogReplyIntent(
                    context, pairingPort, pairCode
                )
                context.startService(intent)
            } catch (e: Throwable) {
                // Service unavailable — fall back to in-app pairing.
                Timber.tag(TAG).w(e, "dialogReplyIntent failed, falling back to in-app pairing")
                val ok = runPairing(context, pairingHost, pairingPort, pairCode)
                isBusy = false
                if (ok) { step = 5 } else {
                    errorMessage = "配对失败，请确认配对码正确后重试"
                    pairCode = ""
                    step = 3
                }
            }
        }
    }

    // --- Step 5: pairing succeeded -> discover connect port -> start server -> finish ---
    LaunchedEffect(step) {
        if (step == 5 && !isBusy) {
            isBusy = true
            errorMessage = null
            successMessage = "配对成功，正在连接 ADB 并启动服务…"
            val ok = runPostPairingStart(context)
            isBusy = false
            if (ok) {
                ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                ShizukuStateMachine.update()
                onFinished()
            } else {
                errorMessage = "配对成功但启动服务失败，请重试"
                successMessage = null
            }
        }
    }

    // --- UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header: step progress
        Text(
            text = "无线调试配对向导",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "第 $step / 5 步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        LinearProgressIndicator(
            progress = { step / 5f },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Step content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (step) {
                1 -> Step1Content(
                    wadbOn = wadbOn,
                    accessibilityOn = accessibilityOn,
                    onOpenWirelessSettings = { openWirelessDebuggingSettings(context) },
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) }
                )
                2 -> Step2Content()
                3 -> Step3Content(
                    pairCode = pairCode,
                    onCodeChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pairCode = it }
                )
                4 -> Step4Content(accessibilityOn = accessibilityOn)
                5 -> Step5Content(
                    successMessage = successMessage,
                    errorMessage = errorMessage,
                    isBusy = isBusy
                )
            }

            // Error banner
            errorMessage?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "错误：$msg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Cancel always available.
            OutlinedButton(onClick = {
                pairingMdns?.stop(); pairingMdns = null
                PairingSessionHolder.clear()
                try {
                    context.startService(
                        Intent(context, AdbPairingService::class.java).setAction("stop")
                    )
                } catch (_: Exception) {}
                onCancel()
            }) { Text("取消") }

            Spacer(modifier = Modifier.weight(1f))

            // Back button (step 2+).
            if (step >= 2 && !isBusy) {
                OutlinedButton(onClick = {
                    if (step == 3 || step == 4) pairCode = ""
                    step = (step - 1).coerceAtLeast(1)
                }) { Text("上一步") }
            }

            // Step-specific primary action.
            when (step) {
                1 -> {
                    Button(onClick = { step = 2 }) { Text("已开启，下一步") }
                }
                2 -> {
                    Button(onClick = {
                        // User tapped "I've opened the pairing dialog" — restart discovery.
                        pairingMdns?.stop()
                        errorMessage = null
                        pairingMdns = startPairingDiscovery(context) { port, host ->
                            if (port > 0 && pairingPort <= 0) {
                                pairingPort = port
                                pairingHost = host
                                step = 3
                            }
                        }
                    }) { Text("已打开配对码界面") }
                }
                3 -> {
                    // Auto-submits at 6 digits; manual fallback button.
                    Button(
                        onClick = { if (pairCode.length == 6) step = 4 },
                        enabled = pairCode.length == 6
                    ) { Text("配对") }
                }
                4 -> {
                    // busy — no button
                }
                5 -> {
                    // busy / auto-finish
                }
            }
        }
    }
}

// ======================================================================
// Step contents
// ======================================================================

@Composable
private fun Step1Content(
    wadbOn: Boolean,
    accessibilityOn: Boolean,
    onOpenWirelessSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("前置检查", style = MaterialTheme.typography.titleMedium)

            // Wireless debugging check
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (wadbOn) "✓" else "○",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (wadbOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("无线调试已开启", style = MaterialTheme.typography.bodyMedium)
            }
            if (!wadbOn) {
                Text(
                    "请前往：设置 → 开发者选项 → 无线调试，打开开关。向导会自动检测并进入下一步。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onOpenWirelessSettings) { Text("打开开发者选项") }
            }

            // Accessibility check (optional)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (accessibilityOn) "✓" else "○",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (accessibilityOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "配对辅助无障碍服务${if (accessibilityOn) "（已开启，自动确认弹窗）" else "（未开启，可跳过）"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!accessibilityOn) {
                Text(
                    "开启后可自动确认系统配对弹窗。不开也没关系，配对时手动点击「允许」即可。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onOpenAccessibilitySettings) { Text("打开无障碍设置") }
            }
        }
    }
}

@Composable
private fun Step2Content() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("获取配对信息", style = MaterialTheme.typography.titleMedium)
            Text(
                "请在「无线调试」界面点击「使用配对码配对设备」。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "系统会弹出一个窗口，显示 6 位配对码和 IP:端口。",
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(20.dp).height(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在等待配对端口…", style = MaterialTheme.typography.bodySmall)
            }
            // MIUI-specific guidance: pulling down the shade keeps the pairing dialog alive.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "MIUI / HyperOS 用户推荐：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "下拉通知栏，在 Shizuku 配对通知中直接输入配对码发送，避免切换应用导致配对中断。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "打开配对码窗口后会自动进入下一步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Step3Content(
    pairCode: String,
    onCodeChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输入配对码", style = MaterialTheme.typography.titleMedium)
            Text(
                "请输入系统配对窗口中显示的 6 位数字配对码。输入满 6 位后自动开始配对。",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = pairCode,
                onValueChange = onCodeChange,
                label = { Text("配对码（6 位数字）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "已输入 ${pairCode.length} / 6 位",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "也可以下拉通知栏，在 Shizuku 配对通知中输入配对码发送（MIUI 推荐）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun Step4Content(accessibilityOn: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(24.dp).height(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("配对中…", style = MaterialTheme.typography.titleMedium)
            }
            if (!accessibilityOn) {
                Text(
                    "如果系统弹出「允许无线调试配对？」确认框，请点击「允许」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    "无障碍服务已开启，系统配对确认框将被自动点击。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun Step5Content(
    successMessage: String?,
    errorMessage: String?,
    isBusy: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("配对成功", style = MaterialTheme.typography.titleMedium)
            if (isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(20.dp).height(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(successMessage ?: "正在启动服务…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ======================================================================
// Helpers
// ======================================================================

private fun isAccessibilityEnabled(context: Context): Boolean {
    return try {
        val expected = "${context.packageName}/${AdbPairingAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        enabled?.split(":")?.any { it.equals(expected, ignoreCase = true) } == true
    } catch (e: Exception) {
        false
    }
}

private fun openWirelessDebuggingSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            }
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {}
}

/**
 * Start mDNS discovery for the pairing port (`_adb-tls-pairing._tcp`). The system only
 * advertises this service while the "pair with pairing code" dialog is on screen, so the
 * callback fires once the user opens that dialog. Returns the [AdbMdns] instance so the
 * caller can stop it when leaving the pairing flow.
 */
private fun startPairingDiscovery(
    context: Context,
    onPortFound: (port: Int, host: String) -> Unit
): AdbMdns? {
    return try {
        var mdnsRef: AdbMdns? = null
        val mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_PAIRING) { port ->
            if (port > 0) {
                onPortFound(port, mdnsRef?.resolvedHost ?: "127.0.0.1")
            }
        }
        mdnsRef = mdns
        mdns.start()
        mdns
    } catch (e: Throwable) {
        Timber.tag(TAG).w(e, "mDNS pairing discovery failed to start")
        null
    }
}

/** Quick test: can we connect to the adb connect port with the stored key (already paired)? */
private suspend fun testAdbConnection(context: Context, port: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
        af.shizuku.manager.adb.AdbClient("127.0.0.1", port, key).use { it.connect() }
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Execute the actual pairing: connect to the pairing port, send the code, exchange SPAKE2 keys.
 * Reuses [AdbPairingClient] unchanged (JNI PairingContext is never touched).
 */
private suspend fun runPairing(
    context: Context,
    host: String,
    port: Int,
    code: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
        AdbPairingClient(host, port, code, key).use { client ->
            client.start()
        }
    } catch (e: AdbInvalidPairingCodeException) {
        Timber.tag(TAG).w("Invalid pairing code")
        false
    } catch (e: AdbKeyException) {
        Timber.tag(TAG).e(e, "AdbKey error")
        false
    } catch (e: ConnectException) {
        Timber.tag(TAG).w(e, "Cannot connect to pairing port")
        false
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Pairing failed")
        false
    }
}

/**
 * After pairing succeeds: discover the connect port, connect with the now-authorized key,
 * start the Shizuku server, and wait for the binder.
 *
 * Reuses [AdbPortProbe] (loopback fast path), [AdbMdns] (TLS_CONNECT mDNS fallback),
 * [AdbStarter.startAdb] and [Starter.waitForBinder] — all existing, unchanged.
 */
private suspend fun runPostPairingStart(context: Context): Boolean {
    val ctx = context.applicationContext

    // 1. Find the connect port: loopback probe first, then mDNS TLS_CONNECT.
    val port = discoverConnectPort(ctx) ?: run {
        Timber.tag(TAG).w("Could not discover connect port after pairing")
        return false
    }

    // 2. Connect and start the server.
    return try {
        AdbStarter.startAdb(ctx, port) { Timber.tag(TAG).d(it) }
        // 3. Wait for binder.
        Starter.waitForBinder { Timber.tag(TAG).d(it) }
        ShizukuSettings.setLastPort(port)
        true
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Post-pairing server start failed")
        false
    }
}

/** Probe loopback first, then mDNS-discover the TLS connect port (10s budget). */
private suspend fun discoverConnectPort(context: Context): Int? {
    // Fast path: loopback.
    val live = AdbPortProbe.getLiveAdbTcpPort(context)
    if (live > 0) return live

    // Fallback: mDNS _adb-tls-connect._tcp.
    return withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            var mdns: AdbMdns? = null
            mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { p ->
                if (p in 1..65535 && done.compareAndSet(false, true)) {
                    mdns?.stop()
                    runCatching { cont.resume(p) }
                }
            }
            try {
                mdns.start()
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "TLS_CONNECT mDNS start failed")
                runCatching { cont.resume(null) }
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) mdns.stop()
            }
        }
    }
}
