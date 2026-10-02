// ReShizukuX: vendored from JingMatrix/Vector @ e00c5c5.
package org.matrix.vector.ipc;

import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver;

/**
 * The one thing the manager calls <i>into</i> a patched process for: driving a module's hot reload.
 *
 * Handed to the manager by IFrameworkService.attachProcessChannel while the patched app bootstraps.
 * Oneway on purpose: the reload runs arbitrary module code with no time bound.
 */
interface IProcessChannel {
    oneway void hotReload(String modulePackageName, in Bundle extras, in LoadedModule module,
                          IHotReloadOutcomeReceiver receiver);
}
