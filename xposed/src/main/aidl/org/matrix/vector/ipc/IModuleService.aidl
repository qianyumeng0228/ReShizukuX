// ReShizukuX: vendored from JingMatrix/Vector @ e00c5c5.
package org.matrix.vector.ipc;

import org.matrix.vector.ipc.IRemotePreferenceCallback;

/**
 * A module's own service, as seen from inside a process the module was injected into.
 *
 * Transaction order is on-wire; append new methods, do not insert.
 */
interface IModuleService {
    /** The framework capability bits, as XposedInterface#getFrameworkProperties. */
    long getFrameworkProperties();
    /** Reads a preference group, optionally subscribing to changes. */
    Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback);
    /** Opens one of the module's remote files read-only, or null. */
    ParcelFileDescriptor openRemoteFile(String path);
    /** Names of the module's remote files. */
    String[] getRemoteFileNames();
}
