package af.shizuku.manager.analytics

import android.content.Context
import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryOptions
import timber.log.Timber

/**
 * ReShizukuX beta1 组C：Sentry 崩溃上报单例。
 *
 * - 用户在 SentrySettingsScreen 中开启后才会初始化。
 * - DSN 为空字符串时即使开关打开也禁用上报（beta 阶段默认占位 DSN，不真正发送）。
 * - 同时提供 [SentryTree]，把 Timber 的 ERROR/WARN 日志桥接到 Sentry。
 *
 * 注意：本类只负责初始化与手动上报；真正的 Application.onCreate 接线由集成阶段统一处理。
 */
object SentryManager {

    const val PREFS_NAME = "reshizukux_beta1"
    private const val KEY_ENABLED = "sentry_enabled"
    private const val KEY_DSN = "sentry_dsn"
    private const val KEY_LAST_CRASH = "sentry_last_crash_time"

    /** 占位 DSN：beta 阶段不指向真实项目，可在高级设置中替换。 */
    const val PLACEHOLDER_DSN = "https://placeholder@placeholder.ingest.sentry.io/0"

    @Volatile
    private var appContext: Context? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun getDsn(context: Context): String =
        prefs(context).getString(KEY_DSN, PLACEHOLDER_DSN).orEmpty()

    fun setDsn(context: Context, dsn: String) {
        prefs(context).edit().putString(KEY_DSN, dsn.trim()).apply()
        // DSN 变化后若处于开启状态，重新初始化使其生效。
        if (isEnabled(context)) init(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            init(context)
        } else {
            runCatching { Sentry.close() }
        }
    }

    /**
     * 在 Application 启动时调用：仅当用户已开启且 DSN 非空时才真正 init。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!isEnabled(context)) return
        val dsn = getDsn(context)
        if (dsn.isBlank()) return // beta：空 DSN 视为禁用

        runCatching {
            Sentry.init { options: SentryOptions ->
                options.dsn = dsn
                options.isEnableAutoSessionTracking = true
                options.environment = "reshizukux-beta1"
            }
        }.onFailure {
            Timber.w(it, "Sentry.init failed, crash reporting stays disabled")
        }
    }

    /** 手动上报异常。Sentry 未初始化时静默丢弃。 */
    fun captureException(throwable: Throwable) {
        if (!Sentry.isEnabled()) return
        recordCrashTime()
        runCatching { Sentry.captureException(throwable) }
    }

    /** 上次记录的崩溃时间（System.currentTimeMillis()），0 表示无记录。 */
    fun getLastCrashTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CRASH, 0L)

    private fun recordCrashTime() {
        appContext?.let { ctx ->
            runCatching {
                prefs(ctx).edit().putLong(KEY_LAST_CRASH, System.currentTimeMillis()).apply()
            }
        }
    }
}

/**
 * Timber.Tree：把 ERROR 及以上级别的日志转发到 Sentry。
 * 集成阶段可在 Debug/Release 中按需 Timber.plant(SentryTree())。
 */
class SentryTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.ERROR) return
        if (!Sentry.isEnabled()) return
        if (t != null) {
            runCatching { Sentry.captureException(t) }
        } else {
            runCatching { Sentry.captureMessage(message) }
        }
    }
}
