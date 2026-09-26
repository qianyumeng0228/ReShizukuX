package af.shizuku.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.permission.PermissionController
import io.reshizukux.modules.watchdog.ModuleWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * 模块后台保活前台服务（ReShizukuX 功能优先设计变更，2026-09）。
 *
 * 历史：P3 阶段模块 `service.sh` 的存活轮询被「附加」在 :daemon 进程的
 * [ShizukuDaemonService] 5s server 保活循环末尾，以避免新增前台服务（省电优先）。
 * 转为「功能优先」后，模块保活拆分为这个**独立前台服务**：
 *
 *  - 运行在 manager **主进程**（无 `android:process` 属性），持有 Shizuku binder 连接，
 *    因此 [ServiceRunner][io.reshizukux.modules.execution.ServiceRunner] 默认走
 *    `Shizuku.newProcess` 即可，不需要再像 :daemon 那样注入 libsu root shell。
 *  - [onStartCommand] 返回 [START_STICKY]：被系统杀死后由系统重建，而非依赖 15min Alarm。
 *  - 独立协程循环（[Dispatchers.IO] + SupervisorJob）每 [POLL_INTERVAL_MS] 调
 *    [ModuleWatchdog.checkAndRevive]；指数退避 + 熔断逻辑保持不变，只换调用方。
 *  - 常驻通知（IMPORTANCE_LOW，ongoing）：前台服务强制要求；文案附带当前后台模块数。
 *
 * 不做自停：即使当前没有任何后台模块，服务也保持运行。START_STICKY 语义下自停会被
 * 系统立即拉起，徒增抖动；无模块时 [ModuleWatchdog.checkAndRevive] 本身快速返回空转。
 *
 * 启停接线：[ModuleManager.enable] 拉起 service.sh 后通过依赖倒置钩子
 * （[ModuleManager.onBackgroundModuleActivated]）触发 [start]；app 启动时由
 * ShizukuApplication 检查是否已有 ENABLED 后台模块并补拉一次。disable 不 stop
 * （其他模块可能仍在运行）。
 */
class RuntimeModuleService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("runtime module service created")
        createChannel()
        // 必须在 startForegroundService() 后 5s 内调用，否则系统会抛
        // ForegroundServiceDidNotStartInTimeException。失败则自停（与 WatchdogService 同模式）。
        if (!startForegroundSafely(buildNotification())) {
            Timber.tag(TAG).w("startForeground refused; stopping")
            stopSelf()
            return
        }
        startWatchLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次 start 都重新挂一次前台通知（可能由重复 startService 触发）。
        startForegroundSafely(buildNotification())
        startWatchLoop()
        // 功能优先：系统杀死后由系统重建本服务（模块后台保活不依赖 Alarm 兜底）。
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        Timber.tag(TAG).i("runtime module service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop

    private fun startWatchLoop() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            Timber.tag(TAG).i("watch loop started, polling every ${POLL_INTERVAL_MS}ms")
            while (isActive) {
                try {
                    runCatching { ModuleWatchdog.checkAndRevive(this@RuntimeModuleService) }
                        .onFailure { Timber.tag(TAG).w(it, "module watchdog iteration failed") }
                    refreshNotification()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "watch loop iteration failed")
                }
                delay(POLL_INTERVAL_MS)
            }
            Timber.tag(TAG).i("watch loop exited")
        }
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模块后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持已启用的模块后台脚本（service.sh）持续运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "create notification channel failed")
        }
    }

    /** 统计当前「ENABLED 且带 service.sh 且有 SERVICE 权限」的后台模块数量。 */
    private fun backgroundModuleCount(): Int = try {
        ModuleManager.getInstalledModules().count { info ->
            info.state == ModuleState.ENABLED &&
                File(ModuleManager.getModuleDir(info.id), "service.sh").exists() &&
                PermissionController.canService(info.id)
        }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "count background modules failed")
        0
    }

    private fun buildNotification(count: Int = backgroundModuleCount()): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("模块后台运行中")
            .setContentText("当前运行的后台模块：$count 个")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "refresh notification failed")
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused")
            false
        }
    }

    companion object {
        private const val TAG = "RuntimeModuleSvc"
        private const val CHANNEL_ID = "module_runtime"
        private const val NOTIFICATION_ID = 1003
        private const val POLL_INTERVAL_MS = 5_000L

        /** 幂等拉起：已在运行时 startService 返回后 onStartCommand 直接复用现有循环。 */
        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, RuntimeModuleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "start failed")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RuntimeModuleService::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "stop failed")
            }
        }
    }
}
