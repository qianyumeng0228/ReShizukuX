package af.shizuku.manager.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import af.shizuku.manager.ShizukuSettings
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Hostile-ROM wireless-debugging flag re-assertion.
 *
 * Some OEM skins (observed on Realme RUI, aggressive on HyperOS) clear
 * `Settings.Global.adb_wifi_enabled` at boot and never restore it, so even when the user
 * previously enabled wireless debugging the ADB start worker finds no mDNS service and gives
 * up. This helper, gated behind an opt-in advanced-settings switch, re-writes the flag 0→1
 * so the worker's ContentObserver / mDNS path can proceed.
 *
 * Safety contract:
 *  - Only ever writes 1, never 0. We never disable wireless debugging on the user's behalf.
 *  - Waits ~3s after the trigger before writing, so the ROM's own boot-time clear has a
 *    chance to land first (otherwise our write races its erase and wins/loses nondeterministically).
 *  - Reads the value back; if it's still 0 after the write, retries exactly once.
 *
 * Privilege ladder (best effort, first that works wins):
 *  1. WRITE_SECURE_SETTINGS held by the app -> direct Settings.Global.putInt.
 *  2. Shizuku (shell UID 2000) running -> Shizuku.newProcess("settings put global ...").
 *  3. Root available -> libsu `settings put global ...`.
 * If none are available this is a no-op (logged), so it's safe to call from boot/network paths.
 */
object WifiDebugReassert {

    private const val TAG = "WifiDebugReassert"
    private const val KEY = "adb_wifi_enabled"
    private const val SETTLE_DELAY_MS = 3_000L

    /** Entry point: honours the opt-in switch, then re-asserts the flag 0→1. */
    suspend fun reassertIfEnabled(context: Context) {
        if (!ShizukuSettings.isWifiDebugReassertEnabled()) {
            return
        }
        try {
            // Already on? Nothing to do — we never downgrade.
            if (readFlag(context) == 1) {
                Timber.tag(TAG).d("adb_wifi_enabled already 1, nothing to reassert")
                return
            }
            // Let the hostile ROM's own boot/network churn settle before we write, otherwise
            // our write can be immediately overwritten by its erase.
            delay(SETTLE_DELAY_MS)

            var ok = writeFlagOne(context)
            if (!ok) {
                // Read-back verify; retry exactly once.
                delay(1_000)
                if (readFlag(context) != 1) {
                    Timber.tag(TAG).w("first reassert write did not stick, retrying once")
                    ok = writeFlagOne(context)
                }
            }
            Timber.tag(TAG).i("reassert adb_wifi_enabled -> 1, success=$ok")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "reassert failed (non-fatal)")
        }
    }

    private fun readFlag(context: Context): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY, 0)
        } catch (e: Exception) {
            0
        }
    }

    /** Tries each privilege ladder rung; returns true if a write actually executed. */
    private fun writeFlagOne(context: Context): Boolean {
        // 1. Direct WRITE_SECURE_SETTINGS.
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return try {
                Settings.Global.putInt(context.contentResolver, KEY, 1)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "direct putInt failed")
                false
            }
        }

        // 2. Shizuku shell UID (server must already be running — best effort).
        if (Shizuku.pingBinder()) {
            try {
                val p = Shizuku.newProcess(
                    arrayOf("sh", "-c", "settings put global $KEY 1"),
                    null, null
                )
                val exited = p.waitFor() == 0
                if (exited) return true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Shizuku newProcess settings put failed")
            }
        }

        // 3. Root.
        if (EnvironmentUtils.isRooted()) {
            return try {
                val result = com.topjohnwu.superuser.Shell.cmd("settings put global $KEY 1").exec()
                result.isSuccess
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "root settings put failed")
                false
            }
        }

        Timber.tag(TAG).d("no privilege rung available to reassert flag")
        return false
    }
}
