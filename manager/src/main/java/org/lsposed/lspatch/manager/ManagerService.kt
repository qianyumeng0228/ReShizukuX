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
 * Minimum-viable scope: the connection comes up and `getModules()` answers an empty list. The
 * scope table ([ScopeStore]) is read and logged so the plumbing is observable; actually handing a
 * real [LoadedModule] -- mapping the module apk's dex into shared memory and building its
 * [org.matrix.vector.ipc.ModuleCode] -- is the daemon's job and lands in a later phase. An empty
 * list is what the loader expects when no module is scoped anyway, so a patched app starts clean.
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

    /** The module packages the user scoped to [app], split modern vs legacy. */
    private fun scopedModules(app: String, legacy: Boolean): Set<String> {
        val all = ScopeStore.modulesFor(appContext, app)
        // The modern/legacy split needs XposedModuleScanner to classify each package; for the
        // minimum-viable connection we just report the whole set the user scoped, and serve no
        // concrete LoadedModule yet. Both directions return an empty list below.
        Log.d(TAG, "scoped for $app (legacy=$legacy): $all")
        return emptySet()
    }

    override fun isLogMuted(): Boolean = false

    override fun getLegacyModules(): List<LoadedModule> {
        val app = callerPackage() ?: return emptyList()
        scopedModules(app, legacy = true)
        return emptyList()
    }

    override fun getModules(): List<LoadedModule> {
        val app = callerPackage() ?: return emptyList()
        scopedModules(app, legacy = false)
        return emptyList()
    }

    override fun getPrefsPath(packageName: String): String =
        File(Environment.getDataDirectory(), "data/$packageName/shared_prefs/").absolutePath

    override fun openManagerApk(): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(
            File(appContext.applicationInfo.sourceDir), ParcelFileDescriptor.MODE_READ_ONLY
        )
    }.onFailure { Log.e(TAG, "openManagerApk failed", it) }.getOrNull()

    override fun requestManagerService(): IBinder? = null

    override fun attachProcessChannel(channel: IProcessChannel?) {
        // Hot reload target registration. The channel the patched app hands back is kept so the
        // manager could later drive a reload into it; for the minimum-viable connection we only
        // record that it attached. Dropping it here is fine -- the app rebinds and re-attaches on
        // its next launch.
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        Log.d(TAG, "process channel attached by uid=$uid pid=$pid (hot reload not driven yet)")
    }
}
