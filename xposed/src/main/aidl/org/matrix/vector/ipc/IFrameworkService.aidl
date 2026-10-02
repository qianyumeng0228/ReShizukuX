// ReShizukuX: vendored from JingMatrix/Vector @ e00c5c5 (services/daemon-service/src/main/aidl).
//
// The precompiled loader.dex (xposed/src/main/assets/lspatch/loader.dex) was built against this
// exact interface, so the method ORDER below is the on-wire transaction order and must never be
// changed -- inserting/renumbering a method breaks every already-patched app. The upstream file
// carries this warning in its own comments.
package org.matrix.vector.ipc;

import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IProcessChannel;

/**
 * What an injected (patched) process asks the manager for, once it has bound to
 * org.lsposed.lspatch.manager.ModuleService.
 *
 * The ReShizukuX manager implements this on the manager side (ManagerService object); the
 * patched app holds a proxy over the same interface. Every call that answers with something
 * about the caller is authenticated by Binder.getCallingUid() against the scope table, so a
 * caller can only ever read its own modules.
 */
interface IFrameworkService {
    /** Whether the user has asked the framework to keep quiet in the log. */
    boolean isLogMuted();
    /** The legacy (de.robv) modules in scope for this process. */
    List<LoadedModule> getLegacyModules();
    /** The libxposed modules in scope for this process. */
    List<LoadedModule> getModules();
    /** Where this process should look for a module's XSharedPreferences files. */
    String getPrefsPath(String packageName);
    /** The manager APK opened read-only; null when missing. */
    ParcelFileDescriptor openManagerApk();
    /** The manager's own service binder; null for every process but the manager host. */
    IBinder requestManagerService();
    /**
     * Hands the manager the channel it needs to call back into this process for hot reload.
     * Not oneway, and must not become oneway.
     */
    void attachProcessChannel(IProcessChannel channel);
}
