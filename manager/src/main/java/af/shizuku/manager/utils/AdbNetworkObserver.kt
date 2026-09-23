package af.shizuku.manager.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.ExistingWorkPolicy
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.worker.AdbStartWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Network-state watcher that re-triggers the ADB start worker when Wi-Fi comes back.
 *
 * Problem it solves: on boot, the device may only have cellular data for the first few seconds.
 * AdbStartWorker (which requires UNMETERED/Wi-Fi in TCP-disabled mode) fails its constraint or
 * times out, and — without this observer — nothing re-runs it once Wi-Fi finally associates,
 * so Shizuku stays dead until the user opens the app.
 *
 * Behaviour:
 *  - Registered once from ShizukuApplication.onCreate (process-lifetime).
 *  - On a validated, unmetered (Wi-Fi) network becoming available, debounce 5s, then re-enqueue
 *    [AdbStartWorker]. If a worker is already RUNNING we use KEEP (don't yank it out); otherwise
 *    REPLACE.
 *  - 3s after the re-enqueue, fire [WifiDebugReassert.reassertIfEnabled] so hostile-ROM flag
 *    clearing (which often coincides with network churn) is corrected.
 */
object AdbNetworkObserver {

    private const val TAG = "AdbNetworkObserver"
    private const val DEBOUNCE_MS = 5_000L
    private const val REASSERT_DELAY_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register(context: Context) {
        if (callback != null) return // already registered
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()

        val appContext = context.applicationContext
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps == null ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    return
                }
                // Don't re-trigger when the service is already up — the watchdog owns that path.
                if (ShizukuStateMachine.isRunning()) return
                // Only meaningful for ADB launch; root/dhizuku don't care about Wi-Fi.
                if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB) return

                Timber.tag(TAG).d("unmetered network available, scheduling ADB worker re-trigger")
                scope.launch {
                    delay(DEBOUNCE_MS)
                    // Re-check after debounce: user may have stopped it, or it may have come up.
                    if (ShizukuStateMachine.isRunning()) return@launch
                    if (ShizukuSettings.isUserInitiatedStop()) return@launch
                    try {
                        AdbStartWorker.enqueue(appContext, ExistingWorkPolicy.KEEP)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "re-enqueue failed")
                    }
                    delay(REASSERT_DELAY_MS)
                    try {
                        WifiDebugReassert.reassertIfEnabled(appContext)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "reassert after network restore failed")
                    }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback!!)
            Timber.tag(TAG).d("network observer registered")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "failed to register network callback")
            callback = null
        }
    }
}
