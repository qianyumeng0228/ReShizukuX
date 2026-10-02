// ReShizukuX: vendored from JingMatrix/Vector @ e00c5c5.
package org.matrix.vector.ipc;

/**
 * How the manager tells a patched process that a module's remote preferences changed.
 */
interface IRemotePreferenceCallback {
    oneway void onRemotePreferencesChanged(in Bundle diff);
}
