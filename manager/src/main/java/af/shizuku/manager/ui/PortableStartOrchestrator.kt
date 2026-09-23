package af.shizuku.manager.ui

import android.content.Context
import android.provider.Settings
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.receiver.WatchdogAlarmReceiver
import af.shizuku.manager.settings.DeviceOwnerHelper
import af.shizuku.manager.utils.AdbPortProbe
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.WifiDebugReassert
import af.shizuku.manager.worker.AdbStartWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import timber.log.Timber

/**
 * Result of [PortableStartOrchestrator.startService].
 */
data class StartResult(
    val success: Boolean,
    /** One of [ShizukuSettings.LaunchMethod]. */
    val method: Int,
    val error: String? = null,
    /** Keep-alive mode actually in effect (native daemon deferred to Phase 8 -> "Alarm"). */
    val guardMode: String = "Alarm",
    /** Step-11 wake-lock / battery-optimization whitelist is a non-blocking note. */
    val noted: Boolean = false,
    /** True when the user still needs to complete first-time wireless-debugging pairing. */
    val pairingRequired: Boolean = false
)

/**
 * Single-switch, 11-step ON orchestration for ShizukuX Portable.
 *
 * Reuses (never rewrites) [ShizukuReceiverStarter.rootStart], [AdbStartWorker],
 * [Starter.waitForBinder], [WatchdogAlarmReceiver], [AdbPortProbe] and [WifiDebugReassert].
 * Every step reports progress through [onStep] so the HomeTab card can render a live
 * "正在执行第 N 步" indicator. Any step that fails returns a failed [StartResult] with a
 * human-readable error; the switch is re-enabled by the caller.
 *
 * Native dual-process daemon (Phase 8) is NOT implemented; step ⑨ degrades to Alarm.
 */
object PortableStartOrchestrator {

    private const val TAG = "PortableOrchestrator"
    private const val BINDER_TIMEOUT_MS = 20_000L

    /**
     * @param onStep called before each step runs with (stepNumber 1..11, title).
     */
    suspend fun startService(
        context: Context,
        onStep: (suspend (Int, String) -> Unit)? = null
    ): StartResult {
        val ctx = context.applicationContext

        // Clear any prior user-stop latch so the watchdog is allowed to manage this run.
        ShizukuSettings.setUserInitiatedStop(false)

        // Already running? Treat as success immediately.
        if (ShizukuStateMachine.isRunning()) {
            return StartResult(success = true, method = ShizukuSettings.getLastLaunchMode(), noted = true)
        }

        // ---------------------------------------------------------------- ① detect method
        onStep?.invoke(1, "检测激活方式")
        val method = when {
            EnvironmentUtils.isRooted() -> ShizukuSettings.LaunchMethod.ROOT
            isDhizukuActive(ctx) -> ShizukuSettings.LaunchMethod.DHIZUKU
            else -> ShizukuSettings.LaunchMethod.ADB
        }
        Timber.tag(TAG).d("detected launch method=$method")

        // Dhizuku (device owner) is already "activated" by virtue of holding DO; no server
        // start sequence needed. Skip ②③④⑤⑥⑨⑪ per spec.
        if (method == ShizukuSettings.LaunchMethod.DHIZUKU) {
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.DHIZUKU)
            return StartResult(success = true, method = method, guardMode = "DeviceOwner", noted = true)
        }

        // ---------------------------------------------------------------- ② ADB pairing check
        if (method == ShizukuSettings.LaunchMethod.ADB) {
            onStep?.invoke(2, "检查无线调试配对")
            val livePort = AdbPortProbe.getLiveAdbTcpPort(ctx)
            val wadbOn = try {
                Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
            } catch (e: Exception) {
                false
            }
            when {
                livePort > 0 -> {
                    // Already paired and listening — fast path, nothing to do.
                    Timber.tag(TAG).d("live ADB port $livePort, already paired")
                }
                wadbOn -> {
                    // Wireless debugging on but adbd hasn't bound a reachable loopback port yet;
                    // the worker's mDNS discovery will find it. Proceed.
                    Timber.tag(TAG).d("adb_wifi_enabled on but no live loopback port yet")
                }
                else -> {
                    // Try to flip wireless debugging on directly (WRITE_SECURE_SETTINGS or DO).
                    val enabled = tryEnableWirelessDebugging(ctx)
                    if (!enabled) {
                        return StartResult(
                            success = false,
                            method = method,
                            error = "未检测到无线调试端口，请先在开发者选项中开启无线调试并完成配对",
                            pairingRequired = true
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- ③ lock screen gate (ADB)
        if (method == ShizukuSettings.LaunchMethod.ADB) {
            onStep?.invoke(3, "等待屏幕解锁")
            val unlocked = awaitUserUnlocked(ctx)
            if (!unlocked) {
                return StartResult(
                    success = false,
                    method = method,
                    error = "等待屏幕解锁超时（30s），请解锁后重试"
                )
            }
        }

        // ---------------------------------------------------------------- ④ hostile ROM reassert (ADB, opt-in)
        if (method == ShizukuSettings.LaunchMethod.ADB) {
            onStep?.invoke(4, "重断言无线调试开关")
            // Best effort; never fails the flow.
            runCatching { WifiDebugReassert.reassertIfEnabled(ctx) }
                .onFailure { Timber.tag(TAG).w(it, "reassert step failed (non-fatal)") }
        }

        // ---------------------------------------------------------------- ⑤ execute start
        onStep?.invoke(5, if (method == ShizukuSettings.LaunchMethod.ROOT) "Root 启动服务" else "ADB 启动服务")
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        when (method) {
            ShizukuSettings.LaunchMethod.ROOT -> ShizukuReceiverStarter.rootStart(ctx)
            ShizukuSettings.LaunchMethod.ADB -> AdbStartWorker.enqueue(ctx)
        }

        // ---------------------------------------------------------------- ⑥ wait for binder
        onStep?.invoke(6, "等待 Binder 就绪")
        val binderOk = withTimeoutOrNull(BINDER_TIMEOUT_MS) {
            while (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) {
                delay(500)
            }
            true
        } ?: false
        if (!binderOk) {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
            return StartResult(
                success = false,
                method = method,
                error = "等待服务就绪超时（20s）。ADB 模式下可能需要完成无线调试配对。",
                pairingRequired = method == ShizukuSettings.LaunchMethod.ADB
            )
        }

        // ---------------------------------------------------------------- ⑦ record launch method
        onStep?.invoke(7, "记录启动方式")
        ShizukuSettings.setLastLaunchMode(method)

        // ---------------------------------------------------------------- ⑧ boot-start + network callback
        onStep?.invoke(8, "开启开机自启")
        runCatching {
            ShizukuSettings.setStartOnBoot(ctx, true)
        }.onFailure { Timber.tag(TAG).w(it, "setStartOnBoot failed") }
        // NetworkCallback is registered process-wide in ShizukuApplication.onCreate; nothing
        // extra to do here beyond ensuring the setting that gates it is on.

        // ---------------------------------------------------------------- ⑨ dual-process guard (degraded)
        onStep?.invoke(9, "守护模式")
        // Phase 8 native daemon NOT implemented; degrade to Alarm + foreground service.
        val guardMode = "Alarm"

        // ---------------------------------------------------------------- ⑩ schedule 15-min alarm
        onStep?.invoke(10, "调度看门狗 Alarm")
        runCatching {
            WatchdogAlarmReceiver.schedule(ctx)
        }.onFailure { Timber.tag(TAG).w(it, "alarm schedule failed") }

        // ---------------------------------------------------------------- ⑪ wake-lock / battery whitelist
        onStep?.invoke(11, "唤醒跟随白名单")
        // Non-blocking: we do not open the battery-optimization whitelist dialog here.
        // Flag it as a noted follow-up for the UI.
        val noted = true

        return StartResult(
            success = true,
            method = method,
            guardMode = guardMode,
            noted = noted
        )
    }

    // ------------------------------------------------------------------ OFF flow

    /**
     * 5-step OFF: confirm handled by caller; this runs the programmatic stop.
     * 1. (UI) confirm dialog
     * 2. stop server via existing [ShizukuReceiverStarter.stop]
     * 3. cancel watchdog alarm
     * 4. set userInitiatedStop latch (so the watchdog doesn't immediately re-arm)
     * 5. reset state to STOPPED
     */
    suspend fun stopService(context: Context) {
        val ctx = context.applicationContext
        // Step 4: latch FIRST so the binder-dead callback that killServerProcess() triggers
        // (RUNNING -> CRASHED) is ignored by the watchdog.
        ShizukuSettings.setUserInitiatedStop(true)
        // Step 2: stop the server process + disconnect.
        runCatching { ShizukuReceiverStarter.stop() }
            .onFailure { Timber.tag(TAG).w(it, "ShizukuReceiverStarter.stop failed") }
        // Step 3: cancel the external 15-min alarm so it doesn't cold-start a restart.
        runCatching { WatchdogAlarmReceiver.cancel(ctx) }
            .onFailure { Timber.tag(TAG).w(it, "alarm cancel failed") }
        // Let the state machine settle to STOPPED.
        delay(500)
        ShizukuStateMachine.update()
        Timber.tag(TAG).i("stopService complete, state=${ShizukuStateMachine.get()}")
    }

    // ------------------------------------------------------------------ helpers

    private fun isDhizukuActive(context: Context): Boolean {
        return try {
            ShizukuSettings.isDhizukuModeEnabled() && DeviceOwnerHelper.isDeviceOwner(context)
        } catch (e: Exception) {
            false
        }
    }

    /** Best-effort flip of adb_wifi_enabled when we hold WRITE_SECURE_SETTINGS or are device owner. */
    private fun tryEnableWirelessDebugging(context: Context): Boolean {
        return try {
            val hasSecure = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasSecure) {
                Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
                return true
            }
            if (DeviceOwnerHelper.isDeviceOwner(context)) {
                return DeviceOwnerHelper.enableWirelessDebugging(context).isEmpty()
            }
            false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "tryEnableWirelessDebugging failed")
            false
        }
    }

    /** Wait up to 30s for keyguard to dismiss; true when unlocked. */
    private suspend fun awaitUserUnlocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            ?: return true
        if (km.isKeyguardLocked.not()) return true
        return withTimeoutOrNull(30_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var receiver: android.content.BroadcastReceiver? = null
                receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: android.content.Intent) {
                        if (intent.action == android.content.Intent.ACTION_USER_PRESENT) {
                            try { c.unregisterReceiver(this) } catch (_: Exception) {}
                            receiver = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                val filter = android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT)
                androidx.core.content.ContextCompat.registerReceiver(
                    context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation {
                    try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
                }
            }
        } != null
    }
}
