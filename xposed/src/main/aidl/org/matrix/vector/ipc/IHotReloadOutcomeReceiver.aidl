// ReShizukuX: vendored from JingMatrix/Vector @ e00c5c5.
package org.matrix.vector.ipc;

import org.matrix.vector.ipc.HotReloadOutcome;

/**
 * How a patched process answers a hot reload request. Kept separate from the request so the
 * request itself can stay oneway.
 */
interface IHotReloadOutcomeReceiver {
    oneway void onOutcome(in HotReloadOutcome outcome);
}
