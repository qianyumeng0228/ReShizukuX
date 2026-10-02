package org.lsposed.lspatch.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * The exported service a manager-mode patched app binds to.
 *
 * The patched app's loader builds
 * `Intent().setComponent(ComponentName("<manager pkg>", "org.lsposed.lspatch.manager.ModuleService"))`
 * with a `packageName` extra and binds with BIND_AUTO_CREATE. That constant
 * (`Constants.MANAGER_SERVICE_NAME`) matches this class name on purpose -- the precompiled
 * loader.dex was built against it.
 *
 * onBind cannot establish the caller here: it does not run inside the binder transaction that
 * triggered the bind, so [Binder.getCallingUid] would answer with the manager's own uid. Identity
 * is resolved on every call against [ManagerService], which serves only the calling uid's modules.
 */
class ModuleService : Service() {

    companion object {
        private const val TAG = "LSPatch-ModuleService"
    }

    override fun onCreate() {
        super.onCreate()
        // The manager process is created on demand by the bind; hand the singleton the Context it
        // needs to read PackageManager / the scope table before the first call lands.
        ManagerService.init(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        val packageName = intent.getStringExtra("packageName")
        Log.i(TAG, "bind request from $packageName")
        return ManagerService.asBinder()
    }
}
