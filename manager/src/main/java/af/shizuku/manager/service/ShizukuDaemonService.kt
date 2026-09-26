package af.shizuku.manager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.starter.Starter
import com.topjohnwu.superuser.Shell
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.execution.ServiceRunner
import io.reshizukux.modules.watchdog.ModuleWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Dual-process guard daemon, running in the dedicated ":daemon" process.
 *
 * The privileged server (`shizuku_plus_server`, launched via `libshizuku.so --apk=...`) is a
 * native app_process process that the Android framework cannot see as part of this app, so a
 * foreground Service in the main process gets frozen/killed together with the rest of the app on
 * hostile ROMs. This Service lives in a separate process: every [POLL_INTERVAL_MS] it asks the
 * persistent root shell whether the server is still alive, and relaunches it through the same
 * root shell when it has died.
 *
 * Liveness is checked with `pgrep -f '[s]hizuku_plus_server'` (the bracket is the classic
 * self-match exclusion so the wrapping `sh -c` isn't counted). Restart reuses
 * [Starter.internalCommand] exactly like the original root start, so the SELinux / mount-namespace
 * / cgroup setup in the native starter runs identically.
 *
 * Crash-loop protection: at most [CIRCUIT_MAX_RESTARTS] relaunches within a [CIRCUIT_WINDOW_MS]
 * window; after that the loop opens the circuit and stops itself, letting the 15-min Alarm
 * backstop re-evaluate (and the user notice something is wrong) instead of burning the CPU.
 *
 * Started by PortableStartOrchestrator after the server binder comes up; stopped on the OFF path
 * and when the daemon setting is turned off. Under ABI/Dhizuku launch there is no root shell to
 * relaunch a native server, so the service self-stops immediately and the UI degrades to Alarm.
 */
class ShizukuDaemonService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var guardJob: Job? = null
    private val restartTimestamps = ArrayDeque<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("daemon created in process pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.tag(TAG).i("stop action received, self-stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startGuardLoop()
        // If the system kills this process there is nothing sticky about it: the 15-min
        // WatchdogAlarmReceiver re-arms a fresh daemon when the server is supposed to be up.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        guardJob?.cancel()
        scope.cancel()
        Timber.tag(TAG).i("daemon destroyed")
        super.onDestroy()
    }

    private fun startGuardLoop() {
        if (guardJob?.isActive == true) return
        guardJob = scope.launch {
            // Fail fast when this launch mode cannot be guarded: without a root shell the daemon
            // has no way to relaunch the native server, so degrade to the Alarm watchdog.
            if (!ensureRootAvailable()) {
                Timber.tag(TAG).w("no root shell; daemon cannot guard, stopping (Alarm fallback)")
                stopSelf()
                return@launch
            }
            if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ROOT) {
                Timber.tag(TAG).w("non-root launch mode; daemon stopping (Alarm fallback)")
                stopSelf()
                return@launch
            }

            // P3：模块后台保活。daemon 进程无 Shizuku binder（它自己就是拉起 server 的那一方），
            // 故把模块 service.sh 的 pgrep/kill/重启统一走已就绪的 libsu root shell。
            runCatching {
                ModuleManager.init(this@ShizukuDaemonService)
                ServiceRunner.installShell { cmd ->
                    runCatching { Shell.cmd(cmd).exec().out.joinToString("\n").trim() }
                        .getOrDefault("")
                }
                Timber.tag(TAG).i("module watchdog shell installed (libsu root)")
            }.onFailure { Timber.tag(TAG).w(it, "module watchdog init failed") }

            Timber.tag(TAG).i("guard loop started, polling every ${POLL_INTERVAL_MS}ms")
            while (isActive) {
                try {
                    if (!ShizukuSettings.isDaemonEnabled()) {
                        Timber.tag(TAG).i("daemon disabled in settings, stopping")
                        stopSelf()
                        break
                    }
                    val alive = isServerAlive()
                    if (!alive) {
                        Timber.tag(TAG).w("server not alive, attempting relaunch")
                        attemptRestart()
                    }
                    // P3：在原有 server 保活循环末尾「附加」模块 service.sh 存活轮询
                    // （不改 server pgrep/重启/熔断逻辑）。无后台模块时快速返回。
                    runCatching { ModuleWatchdog.checkAndRevive(this@ShizukuDaemonService) }
                        .onFailure { Timber.tag(TAG).w(it, "module watchdog iteration failed") }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "guard loop iteration failed")
                }
                delay(POLL_INTERVAL_MS)
            }
            Timber.tag(TAG).i("guard loop exited")
        }
    }

    /** Opens / reuses the libsu root shell; returns true when a root shell is actually available. */
    private fun ensureRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "root shell unavailable")
            false
        }
    }

    /** True when a process matching the server's `--nice-name=shizuku_plus_server` cmdline exists. */
    private fun isServerAlive(): Boolean {
        return try {
            // The [s] bracket stops pgrep from matching the wrapping `sh -c` command itself.
            val out = Shell.cmd("pgrep -f '[s]hizuku_plus_server'").exec()
                .out
            out.isNotEmpty()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "pgrep liveness check failed")
            // On error, assume alive rather than risk a spurious restart storm.
            true
        }
    }

    private suspend fun attemptRestart() {
        val now = System.currentTimeMillis()
        // Prune timestamps outside the circuit window.
        while (restartTimestamps.isNotEmpty() && now - restartTimestamps.first() > CIRCUIT_WINDOW_MS) {
            restartTimestamps.removeFirst()
        }
        if (restartTimestamps.size >= CIRCUIT_MAX_RESTARTS) {
            Timber.tag(TAG).e(
                "circuit breaker open: ${restartTimestamps.size} relaunches within " +
                    "${CIRCUIT_WINDOW_MS}ms; giving up and stopping"
            )
            stopSelf()
            return
        }
        restartTimestamps.addLast(now)

        try {
            val cmd = Starter.internalCommand
            Timber.tag(TAG).i("relaunching server via root: $cmd")
            val result = Shell.cmd(cmd).exec()
            Timber.tag(TAG).i("relaunch exec finished, code=${result.code}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "relaunch exec failed")
        }

        // Give the forked app_process time to bind before the next pgrep, so we don't count the
        // boot window as another death and burn through the circuit breaker.
        delay(RESTART_SETTLE_MS)
    }

    companion object {
        private const val TAG = "ShizukuDaemon"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val CIRCUIT_WINDOW_MS = 60_000L
        private const val CIRCUIT_MAX_RESTARTS = 5
        private const val RESTART_SETTLE_MS = 8_000L

        const val ACTION_STOP = "af.shizuku.manager.daemon.STOP"

        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, ShizukuDaemonService::class.java)
                context.startService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "start failed")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            try {
                val intent = Intent(context, ShizukuDaemonService::class.java).setAction(ACTION_STOP)
                context.startService(intent)
                context.stopService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "stop failed")
            }
        }
    }
}
