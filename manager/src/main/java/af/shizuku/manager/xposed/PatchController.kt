package af.shizuku.manager.xposed

import af.shizuku.manager.R
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.reshizukux.xposed.patcher.ApkPatcher
import io.reshizukux.xposed.patcher.PatchSpec
import io.reshizukux.xposed.patcher.util.Logger
import io.reshizukux.xposed.scan.XposedModuleInfo
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One candidate target application for a patch job.
 *
 * Built from PackageManager on a background thread: system apps and the manager itself are
 * filtered out up front, because patching them makes no sense here.
 */
data class PatchTargetApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val apkPath: String,
)

/**
 * Which way the patched app resolves its modules at runtime.
 *
 * [MANAGER]   — the running ReShizukuX manager serves modules live; nothing is baked into the apk.
 * [INTEGRATED] — each chosen module apk is embedded inside the patched apk (standalone patch).
 */
enum class PatchMode { MANAGER, INTEGRATED }

/** The wizard's coarse position, rendered one step per screen. */
enum class PatchStep { SELECT_TARGET, SELECT_MODULES, SELECT_MODE, RUNNING, DONE, FAILED }

/**
 * Coordinates a whole patch job: picks a target app, picks modules, picks a mode, drives the real
 * [ApkPatcher] on a background thread and then installs the produced apk silently through the
 * platform [PackageInstaller].
 *
 * State is plain Compose mutable state on purpose: there is no ViewModel store in this tab, and the
 * wizard lives entirely in a full-screen overlay — holding state here keeps every step's selections
 * alive across recomposition and survives the back navigation between steps.
 *
 * Patching itself may still fail on device (the native hook engine is out of scope for this phase):
 * every failure is captured into [error] and rendered by the progress screen rather than crashing.
 */
class PatchController(private val context: Context) {

    var step by mutableStateOf(PatchStep.SELECT_TARGET)
        private set

    var targets by mutableStateOf<List<PatchTargetApp>>(emptyList())
        private set

    var target by mutableStateOf<PatchTargetApp?>(null)

    var availableModules by mutableStateOf<List<XposedModuleInfo>>(emptyList())

    /** Multi-selection of module apks (Integrated mode bakes these in). */
    val selectedModules = mutableStateListOf<XposedModuleInfo>()

    var mode by mutableStateOf(PatchMode.INTEGRATED)

    var progress by mutableIntStateOf(0)
        private set

    /** Rolling tail of patch log lines, newest last. */
    val logLines = mutableStateListOf<String>()

    var error by mutableStateOf<String?>(null)
        private set

    var outputApk by mutableStateOf<File?>(null)
        private set

    var installMessage by mutableStateOf<String?>(null)
        private set

    /** Target apps scanned off the package manager, system apps and self excluded. */
    fun loadTargets(): List<PatchTargetApp> {
        val pm = context.packageManager
        val self = context.packageName
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Throwable) {
            emptyList()
        }
        targets = apps.filter { app ->
            // Skip obvious system apps, updates of system apps, and the manager itself.
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            !isSystem && app.packageName != self && app.packageName != "$self.debug"
        }.map { app ->
            PatchTargetApp(
                packageName = app.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault(app.packageName),
                icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                apkPath = app.sourceDir ?: "",
            )
        }.sortedBy { it.label.lowercase() }
        return targets
    }

    fun selectTarget(app: PatchTargetApp) {
        target = app
        step = PatchStep.SELECT_MODULES
    }

    fun toggleModule(module: XposedModuleInfo) {
        if (selectedModules.any { it.packageName == module.packageName }) {
            selectedModules.removeAll { it.packageName == module.packageName }
        } else {
            selectedModules.add(module)
        }
    }

    fun goToMode() {
        step = PatchStep.SELECT_MODE
    }

    fun back() {
        step = when (step) {
            PatchStep.SELECT_MODULES -> PatchStep.SELECT_TARGET
            PatchStep.SELECT_MODE -> PatchStep.SELECT_MODULES
            else -> step
        }
    }

    /** patch 任务绑定的协程 job，onCleared 时取消（configuration change / 向导关闭）。 */
    private var job: kotlinx.coroutines.Job? = null

    fun attachJob(job: kotlinx.coroutines.Job?) {
        this.job = job
    }

    /** UI 在 onDispose 时调用：取消仍在跑的 patch 协程。 */
    fun onCleared() {
        job?.cancel()
        job = null
    }

    fun runPatch() {
        val target = target ?: run {
            error = "No target app selected"
            step = PatchStep.FAILED
            return
        }
        // 问题 #17：目标 App 在扫描后、patch 前被卸载时，apk 文件已不存在，
        // ApkPatcher 会抛出难懂的底层错误；这里提前给出明确提示。
        if (!File(target.apkPath).exists()) {
            error = "目标应用已卸载，请重新选择"
            step = PatchStep.FAILED
            return
        }
        val outDir = File(context.cacheDir, "lspatch-out").apply { mkdirs() }
        logLines.clear()
        progress = 0
        error = null
        outputApk = null
        installMessage = null
        step = PatchStep.RUNNING

        // 问题 #1：patch 结束（成功或失败）统一清理临时目录。install() 在此之前已把
        // apk 字节推到 /data/local/tmp 或写入 PackageInstaller session，删除 outDir 不影响安装。
        try {
            runPatchInternal(target, outDir)
        } finally {
            outDir.deleteRecursively()
        }
    }

    private fun runPatchInternal(target: PatchTargetApp, outDir: File) {

        val useManager = mode == PatchMode.MANAGER
        val spec = try {
            val builder = PatchSpec.builder()
                .apk(File(target.apkPath))
                .outputDir(outDir)
                .useManager(useManager)
                .forceOverwrite(true)
            if (useManager) {
                // The patched app's metaloader reads config.json, sees useManager=true, and binds
                // to this package for its modules. Without this it would fall back to the upstream
                // default "org.lsposed.lspatch" -- a package that does not exist on this device, so
                // the app would start unhooked. context.packageName IS moe.shizuku.privileged.api.
                builder.managerPackageName(context.packageName)
            } else {
                selectedModules.forEach { builder.module(File(it.apkPath)) }
            }
            builder.build()
        } catch (e: Exception) {
            error = e.message ?: "Invalid patch request"
            step = PatchStep.FAILED
            return
        }

        val logger = object : Logger() {
            override fun d(msg: String) { logLines.add("[d] $msg"); trim() }
            override fun i(msg: String) { logLines.add(msg); trim() }
            override fun e(msg: String) { logLines.add("[!] $msg"); trim() }
            override fun stage(s: Stage, index: Int, total: Int) {
                progress = when (s) {
                    Stage.READING -> 8
                    Stage.SIGNING -> 22
                    Stage.REWRITING -> 45
                    Stage.INJECTING -> 62
                    Stage.EMBEDDING -> 76
                    Stage.PACKING_SPLIT -> 70
                    Stage.WRITING -> 90
                    Stage.DONE -> 100
                }
                logLines.add("── ${s.name} ($index/$total) ──")
                trim()
            }

            private fun trim() {
                while (logLines.size > 200) logLines.removeAt(0)
            }
        }

        try {
            val outputs = ApkPatcher(logger, spec).patch()
            outputApk = outputs.firstOrNull()
            progress = 100
            outputApk?.let { install(it) } ?: run {
                error = "Patch finished but produced no apk"
                step = PatchStep.FAILED
            }
        } catch (e: Throwable) {
            // Log the full chain to logcat; the UI only shows the top message.
            android.util.Log.e("LSPatch-Patch", "Patch failed", e)
            // Build a multi-line message that walks the cause chain so the root
            // cause is visible in the progress screen without needing logcat.
            val sb = StringBuilder(e.message ?: e.javaClass.simpleName)
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                sb.append("\n  ↳ ").append(cause.message ?: cause.javaClass.simpleName)
                cause = cause.cause
                depth++
            }
            error = sb.toString()
            step = PatchStep.FAILED
        }
    }

    /**
     * Install the produced apk, preferring a Shizuku shell route that can *replace* the original
     * package across signature mismatch.
     *
     * The patched apk is signed with LSPatch's own debug keystore, which never matches the target's
     * original signature, so a plain `pm install -r` (or PackageInstaller.Session commit) over an
     * already-installed original package fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. The user saw
     * "patch 成功" while the phone kept launching the original app. The shell route fixes this:
     *
     *   1. push the apk into /data/local/tmp (shell-readable, app-cache is not)
     *   2. if the target package is currently installed: `pm uninstall -k <pkg>` (keep user data)
     *   3. `pm install -r /data/local/tmp/...apk` -> fresh install under the patched signature
     *
     * Any failure along the way (Shizuku down, shell denied, pm rejected) falls back to the legacy
     * [fallbackInstall] Session path, which still works when signatures happen to agree.
     */
    private fun install(apk: File) {
        val pkg = target?.packageName
        if (pkg != null) {
            try {
                val ok = installViaShizukuShell(apk, pkg)
                if (ok) return
            } catch (fatal: FatalInstallException) {
                // The original package was already uninstalled (-k) and the patched apk then
                // failed to land. The PackageInstaller session path cannot recover a
                // signature-mismatch state and would just mislead the user into thinking the app
                // is still there — report the failure loudly and stop.
                android.util.Log.e("LSPatch-Install", fatal.message, fatal)
                installMessage = fatal.message
                step = PatchStep.FAILED
                return
            } catch (e: Throwable) {
                // Any other shell-route error (Shizuku down, push failed, ...) → fall through.
                android.util.Log.w("LSPatch-Install", "shell route failed, falling back", e)
            }
        }
        fallbackInstall(apk)
    }

    /**
     * Thrown when the original package has already been uninstalled (-k) but the patched apk then
     * failed to install. The caller must NOT fall back to [fallbackInstall] in this state.
     */
    private class FatalInstallException(message: String) : Exception(message)

    /**
     * Shell-route install. Returns true only when `pm install` reported Success.
     *
     * Order matters: we never uninstall the original package before we know a fresh install can
     * land. Steps:
     *   1. push the apk to /data/local/tmp
     *   2. if the target is installed, first try `pm install -r` (overwrite) — when the signing
     *      key already matches (e.g. re-patching with the same LSPatch key) this succeeds and user
     *      data is untouched.
     *   3. only if that overwrite fails (INSTALL_FAILED_UPDATE_INCOMPATIBLE / signature mismatch)
     *      do we `pm uninstall -k <pkg>` and retry the install.
     *   4. if that retry also fails, the original package is gone — throw [FatalInstallException]
     *      so the UI tells the user to reinstall manually instead of silently falling back.
     */
    private fun installViaShizukuShell(apk: File, pkg: String): Boolean {
        // 问题 #6：先确认 Shizuku binder 可用且本应用已获授权，再执行任何 pm 命令；
        // 不可用直接返回 false 走 session fallback，避免在未授权状态下静默失败。
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!Shizuku.pingBinder() || !granted) {
            android.util.Log.w("LSPatch-Install", "Shizuku not ready / permission not granted, shell route unavailable")
            return false
        }
        val tmpPath = "/data/local/tmp/lspatch-patched-${System.currentTimeMillis()}.apk"
        return try {
            // 1. push apk bytes to a shell-readable path via stdin (app cache dir is off-limits to uid 2000).
            val push = shExec("cat > '$tmpPath'", stdinBytes = apk.readBytes(), timeoutSec = 180)
            if (push.first != 0) {
                installMessage = context.getString(R.string.xposed_push_apk_failed, push.second.trim().take(200))
                return false
            }

            fun installOk(result: Pair<Int, String>): Boolean =
                result.first == 0 && result.second.trim().contains("Success", ignoreCase = true)

            // 2. is the target already installed?
            val installed = try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

            if (!installed) {
                // Fresh target: just install.
                val ins = shExec("pm install -r '$tmpPath'", timeoutSec = 300)
                shExec("rm -f '$tmpPath'", timeoutSec = 10)
                return if (installOk(ins)) {
                    installMessage = context.getString(R.string.xposed_install_shell_success)
                    step = PatchStep.DONE
                    true
                } else {
                    installMessage = context.getString(
                        R.string.xposed_install_shell_failed, ins.first, ins.second.trim().take(200)
                    )
                    false
                }
            }

            // 3a. Target is installed: try an overwrite first, WITHOUT removing the original.
            val first = shExec("pm install -r '$tmpPath'", timeoutSec = 300)
            if (installOk(first)) {
                shExec("rm -f '$tmpPath'", timeoutSec = 10)
                installMessage = context.getString(R.string.xposed_install_shell_success)
                step = PatchStep.DONE
                return true
            }
            logLines.add("[i] pm install -r -> exit ${first.first} ${first.second.trim().take(120)}")

            // 3b. Overwrite failed (almost always INSTALL_FAILED_UPDATE_INCOMPATIBLE: patched key
            //     != original key). Only now remove the original package (keeping user data).
            val un = shExec("pm uninstall -k '$pkg'", timeoutSec = 120)
            logLines.add("[i] pm uninstall -k $pkg -> exit ${un.first} ${un.second.trim().take(80)}")

            // 3c. Install the patched apk under the patched signature.
            val second = shExec("pm install -r '$tmpPath'", timeoutSec = 300)
            shExec("rm -f '$tmpPath'", timeoutSec = 10)
            if (installOk(second)) {
                installMessage = context.getString(R.string.xposed_install_shell_success) +
                    context.getString(R.string.xposed_install_shell_success_keep)
                step = PatchStep.DONE
                return true
            }

            // 4. The original package is gone but the patched apk did not land. Do NOT fall back:
            //    surface this loudly so the user knows their app was uninstalled.
            throw FatalInstallException(
                "原包 $pkg 已卸载（保留用户数据），但 patched apk 安装失败：" +
                    "exit=${second.first} out=${second.second.trim().take(200)}。" +
                    "请手动重装原应用后再试。"
            )
        } catch (fatal: FatalInstallException) {
            // Re-throw untouched so the caller does not fall back to the session installer.
            throw fatal
        } catch (e: Throwable) {
            android.util.Log.w("LSPatch-Install", "shell route failed, falling back", e)
            installMessage = context.getString(
                R.string.xposed_install_shell_exception, e.message ?: e.javaClass.simpleName
            )
            false
        }
    }

    /**
     * Run `sh -c <script>` through Shizuku, optionally feeding [stdinBytes] to the remote process's
     * stdin (used to upload the apk). stdout/stderr are pumped on daemon threads to avoid pipe-buffer
     * deadlock, mirroring modules ModuleExecutor. Working dir is /data/local/tmp per project policy.
     *
     * @return Pair(exitCode, combined output); exitCode = -1 when the process could not start.
     */
    private fun shExec(script: String, stdinBytes: ByteArray? = null, timeoutSec: Long = 120): Pair<Int, String> {
        val process = try {
            Shizuku.newProcess(arrayOf("sh", "-c", script), null, "/data/local/tmp")
        } catch (e: Exception) {
            return -1 to ("newProcess failed: ${e.message}")
        }
        if (stdinBytes != null) {
            runCatching {
                process.outputStream.use { os ->
                    os.write(stdinBytes)
                    os.flush()
                }
            }
        }
        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val tOut = pump(process.inputStream, stdoutBuf)
        val tErr = pump(process.errorStream, stderrBuf)
        val code = waitForProcess(process, timeoutSec)
        tOut.join(1000)
        tErr.join(1000)
        return code to (stdoutBuf.toString(Charsets.UTF_8.name()) + stderrBuf.toString(Charsets.UTF_8.name()))
    }

    /**
     * Waits for [process] to exit, enforcing [timeoutSec]. Returns the exit code, or 124 when the
     * process outlived the timeout and was forcibly killed.
     *
     * [Process.waitFor(long, TimeUnit)] / [Process.destroyForcibly] only exist on API 26+; the
     * project minSdk is 24, so on older devices we park a daemon thread on the no-timeout
     * [Process.waitFor] and join it with the timeout — if it hasn't finished we destroy (the best
     * available kill on API 24/25) and report 124.
     */
    private fun waitForProcess(process: Process, timeoutSec: Long): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (finished) {
                    process.exitValue()
                } else {
                    android.util.Log.w("LSPatch-shExec", "timed out after ${timeoutSec}s; killing process")
                    process.destroyForcibly()
                    process.waitFor()
                    124
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                runCatching { process.destroyForcibly() }
                -1
            }
        }
        // API 24-25 fallback: daemon thread blocks on the unbounded waitFor, we join with a timeout.
        val waiter = Thread({
            runCatching { process.waitFor() }
        }, "lspatch-sh-waiter").apply { isDaemon = true; start() }
        return try {
            waiter.join(timeoutSec * 1000)
            if (waiter.isAlive) {
                android.util.Log.w("LSPatch-shExec", "timed out after ${timeoutSec}s; killing process")
                runCatching { process.destroy() }
                waiter.join(2000)
                124
            } else {
                runCatching { process.exitValue() }.getOrDefault(-1)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { process.destroy() }
            -1
        }
    }

    private fun pump(input: java.io.InputStream, sink: ByteArrayOutputStream): Thread {
        val t = Thread({
            try {
                input.use { src ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        synchronized(sink) { sink.write(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
            }
        }, "lspatch-sh-pump")
        t.isDaemon = true
        t.start()
        return t
    }

    /**
     * Silent-ish install via PackageInstaller.Session: when the caller holds install privilege
     * (Shizuku/shell context) the commit lands without user interaction; otherwise the system shows
     * its own confirmation.
     *
     * commit() is asynchronous and only *enqueues* the session: the real result (STATUS_SUCCESS vs
     * STATUS_FAILURE + message) is delivered back through the PendingIntent broadcast. Previously we
     * set [PatchStep.DONE] immediately and even on the exception path, so a failed session was reported
     * as success. Now a one-shot [BroadcastReceiver] observes the broadcast and flips DONE/FAILED on
     * the actual status; any synchronous throw abandons the session and reports FAILED.
     */
    private fun fallbackInstall(apk: File) {
        // 问题 #2：split apk（splitSourceDirs 非空）目前无法被 LSPatch 正确重打包/安装，
        // 静默失败会让用户以为 patch 成功；这里给出明确提示。
        val targetPkg = target?.packageName
        if (targetPkg != null) {
            val splits = runCatching {
                context.packageManager.getPackageInfo(targetPkg, 0)
                    .applicationInfo?.splitSourceDirs
            }.getOrNull()
            if (!splits.isNullOrEmpty()) {
                installMessage = "暂不支持 split apk，请选单 apk 应用"
                step = PatchStep.FAILED
                return
            }
        }
        val action = "${context.packageName}.lspatch.INSTALL_COMMITTED"
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                runCatching { context.unregisterReceiver(this) }
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                ) ?: PackageInstaller.STATUS_FAILURE
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    installMessage = context.getString(R.string.xposed_install_session_commit_success)
                    step = PatchStep.DONE
                } else {
                    val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: status.toString()
                    installMessage = context.getString(R.string.xposed_install_session_commit_failed, msg)
                    step = PatchStep.FAILED
                }
            }
        }
        try {
            val filter = android.content.IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setSize(apk.length()) }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("lspatch", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val installIntent = Intent(action).apply {
                    setPackage(context.packageName)
                }
                // PackageInstaller.commit() requires a *mutable* PendingIntent so the system can
                // fill in the result extras; Android 14+ only forbids mutable *implicit* intents,
                // so pinning the package above makes FLAG_MUTABLE legal again.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                     else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, installIntent, flags)
                session.commit(pi.intentSender)
            }
            // commit() only enqueued the session; the receiver below (or its failure path) sets
            // the final DONE/FAILED. We surface "submitted" as an intermediate message only.
            installMessage = context.getString(R.string.xposed_install_session_submitted, apk.name)
        } catch (e: Throwable) {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { if (sessionId != -1) installer.abandonSession(sessionId) }
            android.util.Log.e("LSPatch-Install", "session install failed", e)
            installMessage = context.getString(
                R.string.xposed_install_session_failed, e.message ?: e.javaClass.simpleName
            )
            step = PatchStep.FAILED
        }
    }

    fun reset() {
        step = PatchStep.SELECT_TARGET
        target = null
        selectedModules.clear()
        mode = PatchMode.INTEGRATED
        logLines.clear()
        progress = 0
        error = null
        outputApk = null
        installMessage = null
    }
}
