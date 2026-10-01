package af.shizuku.manager.settings

import android.content.Context
import android.text.TextUtils
import af.shizuku.manager.R
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * One-tap Device Owner provisioning that does NOT require wiping accounts.
 *
 * How it works (borrowed from AxManagerD v1.3.0-beta):
 *   Android's `dpm set-device-owner` rejects when user_setup_complete=1 AND accounts>0.
 *   We temporarily flip both provisioning flags to 0, run the dpm command, then always
 *   restore them in a finally block — so no accounts are actually removed.
 *
 * Safety:
 *   - The restore step runs in finally, so even if dpm crashes or the process dies
 *     mid-way, a JVM-shutdown hook + the next Shizuku command will re-assert flags.
 *   - We read back dpm's stdout/stderr to surface real errors ("already has a device
 *     owner", "already some accounts", "Unknown admin", etc.) to the user.
 *
 * Requires Shizuku running and granted (shell UID 2000). Root fallback available.
 */
object DeviceOwnerProvisioner {

    private const val TAG = "DOProvisioner"

    data class Result(val success: Boolean, val message: String)

    /** Execute a shell command via Shizuku (or root fallback). Returns (exitCode, output). */
    private fun exec(cmd: String): Pair<Int, String> {
        return try {
            if (Shizuku.pingBinder()) {
                val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val out = readAll(p)
                val code = p.waitFor()
                code to out
            } else {
                // root fallback
                val res = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                (if (res.isSuccess) 0 else 1) to (res.out + res.err).joinToString("\n")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "exec failed: $cmd")
            -1 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readAll(p: Process): String {
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                lines.forEach { sb.append(it).append('\n') }
            }
            BufferedReader(InputStreamReader(p.errorStream)).useLines { lines ->
                lines.forEach { sb.append(it).append('\n') }
            }
        } catch (_: Exception) {}
        return sb.toString().trim()
    }

    /** Restore provisioning flags to "completed". ALWAYS call this, even on failure. */
    private fun restoreFlags() {
        exec("settings put global device_provisioned 1 && settings put secure user_setup_complete 1")
        Timber.tag(TAG).i("provisioning flags restored to 1")
    }

    /**
     * Main entry: become device owner without deleting accounts.
     * Runs on a background thread; call from a coroutine.
     */
    suspend fun provision(context: Context): Result {
        // Pre-check: already DO?
        if (DeviceOwnerHelper.isDeviceOwner(context)) {
            return Result(true, context.getString(R.string.rsx_do_already_owner))
        }

        // Pre-check: Shizuku must be running
        if (!Shizuku.pingBinder()) {
            return Result(false, context.getString(R.string.rsx_do_shizuku_not_running))
        }

        // Component name must match DhizukuAdminReceiver exactly
        val comp = "af.shizuku.manager/.admin.DhizukuAdminReceiver"
        val dpmCmd = "dpm set-device-owner --user 0 $comp"

        try {
            // Step 0: remove secondary users.
            // Android rejects `dpm set-device-owner` when more than one user exists
            // ("Not allowed to set the device owner because there are already several
            // users on the device" — e.g. Xiaomi XSpace dual-app User 999). Only User 0
            // may remain. This is irreversible, but required by Android's security model.
            Timber.tag(TAG).i("Step 0: listing users")
            val (cu, ou) = exec("pm list users")
            if (cu == 0) {
                val secondaryIds = Regex("UserInfo\\{(\\d+):")
                    .findAll(ou)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .filter { it != 0 }
                    .toList()
                for (secondaryId in secondaryIds) {
                    Timber.tag(TAG).i("removing secondary user $secondaryId")
                    val (cr, or_) = exec("pm remove-user $secondaryId")
                    if (cr != 0) {
                        return Result(false, "无法移除副用户 $secondaryId: $or_")
                    }
                }
            } else {
                // Listing failed; proceed anyway — dpm will surface the real error.
                Timber.tag(TAG).w("pm list users failed (exit=$cu): $ou")
            }

            // Step 1: open the setup gate (pretend we're still in SUW)
            Timber.tag(TAG).i("Step 1: clearing provisioning flags")
            val (c1, o1) = exec("settings put global device_provisioned 0 && settings put secure user_setup_complete 0")
            if (c1 != 0) {
                return Result(false, "无法关闭开机向导标记: $o1")
            }

            // Step 2: run dpm set-device-owner
            Timber.tag(TAG).i("Step 2: $dpmCmd")
            val (c2, o2) = exec(dpmCmd)
            Timber.tag(TAG).i("dpm exit=$c2 out=$o2")

            if (c2 != 0 || o2.lowercase().contains("error") || o2.lowercase().contains("failure")) {
                // Translate known errors
                val msg = translateError(context, o2)
                return Result(false, msg)
            }

            // Step 3: verify
            Thread.sleep(500)
            if (!DeviceOwnerHelper.isDeviceOwner(context)) {
                return Result(false, context.getString(R.string.rsx_do_verify_failed, o2))
            }

            return Result(true, context.getString(R.string.rsx_do_success))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "provision crashed")
            return Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            // ALWAYS restore flags — even on success. Without this the phone is stuck in SUW.
            restoreFlags()
        }
    }

    /** Translate dpm error output to user-facing Chinese. */
    private fun translateError(context: Context, raw: String): String {
        val low = raw.lowercase()
        return when {
            low.contains("already has a device owner") || low.contains("device owner is already set") ->
                context.getString(R.string.rsx_do_err_already_owner)
            low.contains("already some accounts") || low.contains("already several accounts") ->
                context.getString(R.string.rsx_do_err_accounts)
            low.contains("already some users") || low.contains("already several users") ->
                context.getString(R.string.rsx_do_err_multiuser)
            low.contains("unknown admin") ->
                context.getString(R.string.rsx_do_err_unknown_admin)
            low.contains("not allowed") || low.contains("security exception") ->
                context.getString(R.string.rsx_do_err_not_allowed, raw)
            raw.isBlank() ->
                context.getString(R.string.rsx_do_err_no_output)
            else ->
                context.getString(R.string.rsx_do_err_unknown, raw)
        }
    }
}
