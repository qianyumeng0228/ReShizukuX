package af.shizuku.manager.xposed

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
import java.io.File

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

    /** Runs the engine on the calling coroutine's IO dispatcher. Callers switch context first. */
    fun runPatch() {
        val target = target ?: run {
            error = "No target app selected"
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

        val useManager = mode == PatchMode.MANAGER
        val spec = try {
            val builder = PatchSpec.builder()
                .apk(File(target.apkPath))
                .outputDir(outDir)
                .useManager(useManager)
                .forceOverwrite(true)
            if (!useManager) {
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
     * Silent-ish install via PackageInstaller.Session: when the caller holds install privilege
     * (Shizuku/shell context) the commit lands without user interaction; otherwise the system shows
     * its own confirmation. Failure is reported, never thrown.
     */
    private fun install(apk: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setSize(apk.length()) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("lspatch", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val installIntent = Intent("${context.packageName}.lspatch.INSTALL_COMMITTED").apply {
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
            installMessage = "已提交安装：${apk.name}"
            step = PatchStep.DONE
        } catch (e: Throwable) {
            installMessage = "生成成功，但静默安装失败：${e.message ?: e.javaClass.simpleName}"
            step = PatchStep.DONE
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
