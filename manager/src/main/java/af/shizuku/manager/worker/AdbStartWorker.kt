package af.shizuku.manager.worker

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.work.*
import java.io.EOFException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbMdns
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import af.shizuku.manager.receiver.ShizukuReceiverStarter.updateNotification
import af.shizuku.manager.settings.BugReportDialogActivity
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.AdbPortProbe
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlin.coroutines.resume

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            updateNotification(
                applicationContext,
                WorkerState.RUNNING
            )

            // Full-path lock screen gate: on plain-TCP / wireless adb, attempting the
            // handshake while the keyguard is locked deadlocks (adbd won't authenticate
            // until the user unlocks). Wait for USER_PRESENT up to 30s; on timeout, retry
            // later rather than burning the attempt on a locked-handshake failure.
            if (!awaitUserUnlocked(applicationContext)) {
                updateNotification(applicationContext, WorkerState.AWAITING_RETRY)
                return Result.retry()
            }

            val cr = applicationContext.contentResolver

            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

            val tcpPort = EnvironmentUtils.getAdbTcpPort()

            val savedPort = ShizukuSettings.getLastPort()
            val isWifiOk = !EnvironmentUtils.isWifiRequired() || ShizukuSettings.isForceStartWadbEnabled()

            // Fast path: if a loopback ADB port is already listening (127.0.0.1), use it
            // directly and skip the 15s mDNS discovery. This covers the common "wadb was
            // already on from a previous session / boot" case and works even over cellular
            // because loopback never leaves the device.
            val livePort = AdbPortProbe.getLiveAdbTcpPort(applicationContext)
            val port = when {
                livePort > 0 -> livePort
                tcpPort > 0 && isWifiOk -> tcpPort
                savedPort > 0 && isWifiOk && runAttemptCount == 0 -> savedPort
                else -> callbackFlow {
                val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                    if (p > 0) trySend(p)
                }

                var awaitingAuth = false
                var timeoutJob: Job? = null
                var unlockReceiver: BroadcastReceiver? = null

                fun startDiscoveryWithTimeout() {
                    adbMdns.start()
                    timeoutJob?.cancel()
                    timeoutJob = launch {
                        delay(15_000)
                        close(TimeoutException("Timed out during mDNS port discovery"))
                    }
                }

                fun handleAuth() {
                    val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isKeyguardLocked) {
                        val notification = ShizukuReceiverStarter.buildNotification(
                            applicationContext,
                            null
                        )
                        // On Android 14+ (API 34), ForegroundInfo must declare a foreground
                        // service type or the OS throws InvalidForegroundServiceTypeException
                        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ForegroundInfo(
                                ShizukuReceiverStarter.NOTIFICATION_ID,
                                notification,
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                            )
                        } else {
                            ForegroundInfo(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
                        }
                        setForegroundAsync(foregroundInfo)

                        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                        unlockReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (intent.action == Intent.ACTION_USER_PRESENT) {
                                    context.unregisterReceiver(this)
                                    unlockReceiver = null
                                    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                }
                            }
                        }
                        ContextCompat.registerReceiver(
                            applicationContext,
                            unlockReceiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    } else awaitingAuth = true
                    timeoutJob?.cancel()
                    adbMdns.stop()
                }

                val observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                            0 -> if (awaitingAuth) {
                                close(SecurityException("Network is not authorized for wireless debugging"))
                            } else handleAuth()
                            1 -> startDiscoveryWithTimeout()
                        }
                    }
                }

                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                cr.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
                startDiscoveryWithTimeout()

                awaitClose {
                    adbMdns.stop()
                    timeoutJob?.cancel()
                    cr.unregisterContentObserver(observer)
                    unlockReceiver?.let { applicationContext.unregisterReceiver(it) }
                }
            }.first()
            }

            AdbStarter.startAdb(applicationContext, port)
            // Remember the port that actually worked so the next boot's loopback fast-path
            // (and mDNS fallback) hits it immediately.
            ShizukuSettings.setLastPort(port)
            Starter.waitForBinder()
            // Record launch mode so HomeTab "activation mode" reflects the ADB path even
            // when the server was auto-started by this background worker (NetworkCallback /
            // boot), not via the manual switch / StarterActivity.
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
            ActivityLogManager.log("ReShizukuX", applicationContext.packageName, "Service started via background ADB worker on port $port")

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)

            return Result.success()
        } catch (e: CancellationException) {
            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                WorkerState.AWAITING_RETRY
            } else {
                when (stopReason) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WorkerState.STOPPED
                    else -> WorkerState.AWAITING_RETRY
                }
            }
            updateNotification(applicationContext, state)

            throw e
        } catch (e: Exception) {
            val ignored = listOf(
                EOFException::class,
                SecurityException::class,
                TimeoutException::class,
                java.net.ConnectException::class,
                java.net.SocketException::class,
                java.net.SocketTimeoutException::class
            )
            // Only show error notification if it's not a common transient error,
            // or if we've already tried several times and it's still failing.
            if (ignored.none { it.isInstance(e) } || runAttemptCount >= 5) {
                if (e !is SecurityException && e !is TimeoutException) {
                    showErrorNotification(applicationContext, e)
                }
            }

            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                return Result.success()
            } else {
                // Show a more informative message when mDNS discovery timed out so users
                // aren't left wondering why the "waiting for WiFi" message appears on WiFi.
                val retryState = if (e is TimeoutException) WorkerState.AWAITING_DISCOVERY else WorkerState.AWAITING_RETRY
                updateNotification(applicationContext, retryState)
                return Result.retry()
            }
        }
    }

    /**
     * Full-path lock screen gate: returns true immediately when the keyguard isn't locked,
     * otherwise suspends until ACTION_USER_PRESENT fires (user unlocked) or the 30s budget
     * expires. On expiry returns false so the caller can Result.retry() rather than deadlocking
     * the ADB handshake against a locked adbd.
     */
    private suspend fun awaitUserUnlocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) return true
        return try {
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    var receiver: BroadcastReceiver? = null
                    receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_USER_PRESENT) {
                                try { c.unregisterReceiver(this) } catch (_: Exception) {}
                                receiver = null
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                    }
                    val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                    ContextCompat.registerReceiver(
                        context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    cont.invokeOnCancellation {
                        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
                    }
                }
            } != null
        } catch (e: Exception) {
            false
        }
    }

    private fun showErrorNotification(context: Context, e: Exception) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wadb_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        val msgNotif = "$e. ${context.getString(R.string.wadb_error_notify_dev)}"

        val intent = Intent(context, BugReportDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = nb
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(msgNotif)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msgNotif))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "adb_start_worker"

        @JvmStatic
        fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

        /**
         * @param policy REPLACE (user manual start / orchestrator) or KEEP (network observer
         *                re-trigger when a worker is already RUNNING — don't yank it out).
         */
        fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            // WorkManager uses credential-encrypted storage which is unavailable during direct boot.
            // Skip enqueueing until the user has unlocked their device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val cb = Constraints.Builder()
            if (EnvironmentUtils.isWifiRequired() && !ShizukuSettings.isForceStartWadbEnabled())
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request
            )
        }
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
    }
}
