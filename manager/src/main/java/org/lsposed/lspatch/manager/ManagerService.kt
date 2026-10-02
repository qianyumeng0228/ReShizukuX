package org.lsposed.lspatch.manager

import android.content.Context
import android.os.Binder
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import af.shizuku.manager.xposed.ScopeStore
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.LoadedModule
import java.io.File

/**
 * The manager-side half of the Manager-mode IPC, handed out by [ModuleService].
 *
 * A patched app's loader (the precompiled loader.dex inside this manager's assets) binds to
 * `org.lsposed.lspatch.manager.ModuleService`, receives [asBinder], and calls through the
 * [IFrameworkService] proxy. Every call that answers something about the caller resolves the
 * caller from [Binder.getCallingUid] (onBind itself does not run inside the calling transaction,
 * which is why identity is established here, not in onBind).
 *
 * Real module delivery: `getModules()` / `getLegacyModules()` resolve the patched app from the
 * calling uid, read its row out of [ScopeStore], and for each scoped module package build a fresh
 * [LoadedModule] -- the module apk's dexes mapped into shared memory by [ModuleApkLoader] -- exactly
 * like LSPatch's `ConfigManager.getModuleFilesForApp`. A caller can only ever read its own modules,
 * and a fresh generation is built per request because the framework consumes the shared memory.
 */
object ManagerService : IFrameworkService.Stub() {

    private const val TAG = "LSPatch-ManagerSvc"

    /** Set once when [ModuleService] is created in the (main) manager process. Never null on a live call. */
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The package that owns the uid on the other end of this binder transaction, or null. */
    private fun callerPackage(): String? {
        val uid = Binder.getCallingUid()
        return runCatching {
            appContext.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull().also {
            Log.d(TAG, "calling uid=$uid -> package=$it")
        }
    }

    /**
     * Whitelist gate: only patched apps that actually live in [ScopeStore] may talk to this
     * service. Without it any app could bind [org.lsposed.lspatch.manager.ModuleService] and
     * call [openManagerApk] to read the manager's own apk (code + resources), or enumerate
     * modules via [getModules]/[getLegacyModules].
     */
    private fun isAllowedCaller(pkg: String?): Boolean {
        if (pkg == null) return false
        return runCatching { ScopeStore.targets(appContext).contains(pkg) }.getOrDefault(false)
    }

    /**
     * The [LoadedModule]s the caller's patched app has scoped, split modern vs legacy. Each scoped
     * module package is read out of its installed apk and only served when its kind matches
     * [legacy]; a module of the other kind is closed and skipped.
     */
    private fun callerModules(legacy: Boolean): List<LoadedModule> {
        val app = callerPackage() ?: return emptyList()
        val scoped = ScopeStore.modulesFor(appContext, app)
        if (scoped.isEmpty()) {
            Log.d(TAG, "no modules scoped for $app (legacy=$legacy)")
            return emptyList()
        }
        val pm = appContext.packageManager
        val served = scoped.mapNotNull { modulePkg ->
            runCatching { ModuleApkLoader.buildLoadedModule(pm, modulePkg, legacy) }
                .onFailure { Log.w(TAG, "failed to build module $modulePkg for $app", it) }
                .getOrNull()
        }
        Log.d(TAG, "serving ${served.size}/${scoped.size} module(s) to $app (legacy=$legacy): ${served.map { it.packageName }}")
        return served
    }

    override fun isLogMuted(): Boolean = false

    override fun getLegacyModules(): List<LoadedModule> {
        if (!isAllowedCaller(callerPackage())) {
            Log.w(TAG, "getLegacyModules: caller not a patched app, returning empty (uid=${Binder.getCallingUid()})")
            return emptyList()
        }
        val list = callerModules(legacy = true)
        Log.d(TAG, "getLegacyModules: ${list.map { it.packageName }}")
        return list
    }

    override fun getModules(): List<LoadedModule> {
        if (!isAllowedCaller(callerPackage())) {
            Log.w(TAG, "getModules: caller not a patched app, returning empty (uid=${Binder.getCallingUid()})")
            return emptyList()
        }
        val list = callerModules(legacy = false)
        Log.d(TAG, "getModules: ${list.map { it.packageName }}")
        // Record which modules this host process runs, so a later scope toggle can find it as a
        // hot-reload target.
        HotReloadRegistry.recordModules(Binder.getCallingUid(), Binder.getCallingPid(), list.map { it.packageName })
        return list
    }

    override fun getPrefsPath(packageName: String): String =
        File(Environment.getDataDirectory(), "data/$packageName/shared_prefs/").absolutePath

    override fun openManagerApk(): ParcelFileDescriptor? {
        // Hard gate: handing out the manager apk's PFD lets the caller read the manager's entire
        // code/resources. Only whitelisted patched apps may ask for it.
        val pkg = callerPackage()
        if (!isAllowedCaller(pkg)) {
            Log.w(TAG, "openManagerApk denied for uid=${Binder.getCallingUid()} pkg=$pkg")
            throw SecurityException("caller not a patched app")
        }
        return runCatching {
            ParcelFileDescriptor.open(
                File(appContext.applicationInfo.sourceDir), ParcelFileDescriptor.MODE_READ_ONLY
            )
        }.onFailure { Log.e(TAG, "openManagerApk failed", it) }.getOrNull()
    }

    override fun requestManagerService(): IBinder? = null

    override fun attachProcessChannel(channel: IProcessChannel?) {
        if (channel == null) return
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val name = callerPackage() ?: "uid$uid"
        // The host's way back in, kept in the registry so the manager can drive a hot reload into
        // it; dropped when the channel dies.
        HotReloadRegistry.attach(uid, pid, name, channel)
    }
}
