package org.lsposed.lspatch.manager

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import org.matrix.vector.ipc.IProcessChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * The live patched host processes this manager can reach to drive a module hot reload -- the
 * manager-side stand-in for Vector's daemon registry.
 *
 * Each patched host hands the manager its [IProcessChannel] once while it bootstraps
 * ([org.lsposed.lspatch.manager.ManagerService.attachProcessChannel]), and asks for its modules once
 * they load ([org.lsposed.lspatch.manager.ManagerService.getModules]). Keyed by the calling
 * `(uid, pid)`, those two facts are the whole of what a reload needs: the channel to call in on, and
 * which modules that process is running. The entry is dropped when the channel dies, so a gone process
 * is never named as a target.
 *
 * Minimum-viable hot reload: when the user toggles a module scope in the UI, [notifyScopeChanged]
 * best-efforts a oneway [IProcessChannel.hotReload] into every live host of that patched app. If no
 * host is currently running (the common case for a not-yet-launched patched app), nothing happens and
 * the UI's existing "force-stop then relaunch" hint is what makes the change take effect -- a stale
 * channel or a reload the loader rejects never throws back into the binder/UI thread.
 */
object HotReloadRegistry {

    private const val TAG = "LSPatch-HotReload"

    class Target(
        val uid: Int,
        val pid: Int,
        val processName: String,
        val channel: IProcessChannel,
    ) {
        @Volatile var modules: List<String> = emptyList()
    }

    private fun idOf(uid: Int, pid: Int): Long =
        (uid.toLong() shl 32) or (pid.toLong() and 0xFFFFFFFFL)

    private val targets = ConcurrentHashMap<Long, Target>()

    fun attach(uid: Int, pid: Int, processName: String, channel: IProcessChannel) {
        val id = idOf(uid, pid)
        Log.i(TAG, "$processName (pid $pid) can be reached for a hot reload")
        targets[id] = Target(uid, pid, processName, channel)
        runCatching {
            channel.asBinder().linkToDeath({ targets.remove(id) }, 0)
        }.onFailure {
            Log.w(TAG, "Could not watch the channel for $processName; dropping it", it)
            targets.remove(id)
        }
    }

    /** Records which modules the caller's process loaded, so a reload knows which host to reach. */
    fun recordModules(uid: Int, pid: Int, modules: List<String>) {
        targets[idOf(uid, pid)]?.modules = modules
    }

    /**
     * Best-effort: called from the UI after a scope toggle for [targetPackage] flipped
     * [changedModule] to [enabled]. Builds a fresh [org.matrix.vector.ipc.LoadedModule] (when
     * enabling) and drives a oneway reload into every live host running that patched app. Returns
     * the number of hosts actually reached, so callers can decide whether the force-stop hint is
     * still the only path.
     */
    fun notifyScopeChanged(
        pm: PackageManager,
        targetPackage: String,
        changedModule: String,
        enabled: Boolean,
    ): Int {
        val live = targets.values.filter { it.processName == targetPackage }
        if (live.isEmpty()) {
            Log.i(TAG, "no live host for $targetPackage; scope change applies on next launch")
            return 0
        }
        val newModule = if (enabled) {
            runCatching { ModuleApkLoader.buildLoadedModuleAny(pm, changedModule) }.getOrNull()
        } else null
        var reached = 0
        for (host in live) {
            runCatching {
                host.channel.hotReload(changedModule, Bundle(), newModule, null)
                reached++
            }.onFailure {
                Log.w(TAG, "hotReload to ${host.processName} failed", it)
            }
        }
        Log.i(TAG, "scope change for $targetPackage ($changedModule enabled=$enabled) reached $reached live host(s)")
        return reached
    }
}
