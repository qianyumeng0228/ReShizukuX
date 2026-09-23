package af.shizuku.manager

import af.shizuku.manager.BuildConfig
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.Configuration
import com.topjohnwu.superuser.Shell
import android.content.Intent
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ThemeDelegateImpl
import af.shizuku.core.ui.ThemeDelegateManager
import af.shizuku.manager.utils.AppContextSettingsImpl
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import timber.log.Timber
import af.shizuku.manager.di.appModule
import android.os.UserManager
import com.airbnb.mvrx.Mavericks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * ShizukuX Application class
 *
 * Initialization order:
 * 1. Sentry (for crash reporting)
 * 2. Static components (native libraries)
 * 3. Settings and managers
 * 4. State machine
 */
class ShizukuApplication : Application(), Configuration.Provider {

    companion object {
        lateinit var appContext: Context
            private set

        /** True only if libadb.so loaded successfully. ADB pairing features must check this. */
        var isAdbNativeAvailable: Boolean = false
            private set
    }

    // WorkManager's own internal background thread (e.g. ForceStopRunnable's Room queries) can
    // throw on an unrecoverable device condition (observed: full-disk SQLiteFullException, which
    // WorkManager converts to an IllegalStateException) entirely inside library code, with no
    // ShizukuExtra frames in the stack. Without a custom TaskExecutor to catch it here, that
    // reaches the process-wide uncaught-exception handler and kills the whole app.
    private val workManagerTaskExecutor: java.util.concurrent.Executor by lazy {
        val delegate = java.util.concurrent.Executors.newFixedThreadPool(4)
        java.util.concurrent.Executor { command ->
            delegate.execute {
                try {
                    command.run()
                } catch (t: Throwable) {
                    Timber.e(t, "WorkManager task threw on internal executor")
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .setTaskExecutor(workManagerTaskExecutor)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base
        // Initialize ShizukuSettings as early as possible
        ShizukuSettings.initialize(base)
    }

    /**
     * Initialize static components (native libraries, etc.)
     */
    private fun initializeStatics() {
        Timber.d("Initializing static components")

        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR).setTimeout(20))

        if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }

        if (atLeast30) {
            try {
                System.loadLibrary("adb")
                isAdbNativeAvailable = true
                Timber.d("Native library 'adb' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                // Log and report but do NOT rethrow — ADB pairing features degrade gracefully.
                // Common causes: SELinux policy on vendor ROMs, missing system dependency.
                Timber.e(e, "libadb.so failed to load — ADB pairing features disabled")
            }
        }
    }

    /**
     * Keeps AppIconCache honest: trims it under memory pressure (it's sized to 1/4 of max heap
     * and never shrinks on its own otherwise) and drops entries for apps that update/uninstall
     * so a changed icon doesn't keep showing the stale cached one.
     */
    private fun registerIconCacheMaintenance() {
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                af.shizuku.manager.utils.AppIconCache.trimMemory(level)
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java", ReplaceWith("onTrimMemory"))
            override fun onLowMemory() {
                af.shizuku.manager.utils.AppIconCache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        })

        val packageChangeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                af.shizuku.manager.utils.AppIconCache.invalidate(packageName)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Never unregistered — this is an Application-scoped singleton, same lifetime as the process.
        androidx.core.content.ContextCompat.registerReceiver(
            this, packageChangeReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Initialize settings and managers
     */
    private fun initializeManagers() {
        ActivityLogManager.initialize(this, ActivityLogSettingsImpl())

        // Redeploy the SU bridge dex whenever the server comes up, not just on app self-update
        // (#423 fix, `9dba4bd3`, only covered ACTION_MY_PACKAGE_REPLACED). If the server wasn't
        // running yet at that point, deployBridgeToTmp() silently no-ops with nothing to retry
        // it later — common on devices where an OEM freezer (Xiaomi/HyperOS, Samsung "Sleeping
        // apps") or a dropped wireless-ADB session delays the server past that moment. Cheap and
        // idempotent to just redeploy on every RUNNING transition.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            ShizukuStateMachine.asFlow()
                .distinctUntilChanged()
                .filter { it == ShizukuStateMachine.State.RUNNING && ShizukuSettings.isSuBridgeEnabled() }
                .collect {
                    try {
                        af.shizuku.manager.database.RootCompatHelper.deployBridgeToTmp(this@ShizukuApplication)
                    } catch (e: Exception) {
                        Timber.tag("ShizukuApplication").w(e, "SU bridge redeploy on service start skipped")
                    }
                }
        }
        AppContextManager.initialize(AppContextSettingsImpl())
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()

        // #429: one-time migration — push the legacy custom locale into AppCompat's
        // own per-app-language store so AppCompatDelegate.getApplicationLocales()
        // becomes authoritative going forward (system Settings > App Info > Language
        // integration on API 33+; AppLocalesStorageHelper-backed persistence below
        // it). AppActivity.attachBaseContext() re-syncs LocaleDelegate.defaultLocale
        // from AppCompatDelegate on every activity creation once this flag is set,
        // so AppCompatDelegate genuinely becomes the source of truth, not just a
        // parallel/secondary write MaterialActivity ignores. Runs exactly once, ever.
        if (!ShizukuSettings.hasMigratedToAppCompatLocales()) {
            val tag = ShizukuSettings.getRawLanguageTag() // KEY_LANGUAGE pref, may be null/"SYSTEM"
            if (!tag.isNullOrEmpty() && tag != "SYSTEM") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            ShizukuSettings.setMigratedToAppCompatLocales()
        }

        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())

        // Initialize Starter with context
        af.shizuku.manager.starter.Starter.initialize(this)

        if (ShizukuSettings.getWatchdog() && !isDaemonProcess()) {
            WatchdogService.start(this)
            val userManagerWatchdog = getSystemService(Context.USER_SERVICE) as? UserManager
            if (userManagerWatchdog == null || userManagerWatchdog.isUserUnlocked) {
                try {
                    af.shizuku.manager.worker.WatchdogWorker.schedule(this)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to schedule WatchdogWorker in direct boot")
                }
                af.shizuku.manager.receiver.WatchdogAlarmReceiver.schedule(this)
            }
        }

        af.shizuku.manager.automation.registerDefaultRules()

        // Portable keep-alive: watch for Wi-Fi returning and re-trigger the ADB worker.
        // Solves the "boot on cellular -> worker fails -> Wi-Fi never re-triggers" gap.
        try {
            af.shizuku.manager.utils.AdbNetworkObserver.register(this)
        } catch (e: Exception) {
            Timber.e(e, "Failed to register ADB network observer")
        }

        Shizuku.addLogListener { appName, packageName, action ->
            ActivityLogManager.log(appName, packageName, action)
        }

    }

    override fun onCreate() {
        ThemeDelegateManager.setDelegate(ThemeDelegateImpl())
        super.onCreate()

        // 0. Initialize Timber
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Prewarm root check on a background thread early to avoid main thread delays/ANRs
        af.shizuku.manager.utils.EnvironmentUtils.prewarmAsync()

        // 1. Run security check
        if (af.shizuku.manager.security.SecurityGuard.isTampered()) {
            Timber.e("Security violation: Environment tampered!")
            // Optionally: crash or notify user
        }

        // Track the foreground Activity so a crash captured on a background thread can still
        // find "what was on screen" for SelectiveScreenshotEventProcessor.
        af.shizuku.manager.utils.ForegroundActivityTracker.register(this)

        // Hide from Recents: when enabled, strip this app's card from the recent-tasks list on
        // every Activity resume so it never lingers as a visible entry after the user leaves.
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (ShizukuSettings.isHideFromRecentsEnabled()) {
                    try {
                        val am = activity.getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                        for (task in am.appTasks) task.setExcludeFromRecents(true)
                    } catch (e: Exception) {
                        android.util.Log.e("HideFromRecents", "failed", e)
                    }
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        // Register persistent crash handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(af.shizuku.manager.utils.CrashHandler(this, defaultHandler))

        // 3. Initialize Mavericks and Koin
        Mavericks.initialize(this)
        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@ShizukuApplication)
            modules(appModule)
        }

        // 2. Initialize static components FIRST to ensure HiddenApiBypass is active
        try {
            initializeStatics()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize static components")
            if (e is Error) throw e
        }

        // Strict mode for debugging (DEBUG only)
        if (BuildConfig.DEBUG) {
            // NOTE: penaltyFlashScreen() intentionally omitted — it flashes a red border around
            // the whole screen on every main-thread disk/preference I/O violation, which is
            // extremely intrusive when tapping through Settings. penaltyLog() still surfaces the
            // violations in logcat for debugging.
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        registerIconCacheMaintenance()

        // 5. Initialize settings and managers
        try {
            initializeManagers()

            // ShizukuX beautification: apply the wallpaper-forced color scheme on every app start
            // (white miku -> light, black miku -> dark) so home and settings pages stay consistent
            // even after the process restarts. AppCompatDelegate persists the mode, but forcing it
            // here guarantees correctness right after install/first launch too. Combined with the
            // DynamicColors.Light/Dark overlay (see ThemeDelegateImpl), the forced config makes the
            // settings page render light/dark to match the wallpaper theme.
            val wallpaperForced = ShizukuSettings.getWallpaperForcedNightMode()
            if (wallpaperForced != -1) {
                AppCompatDelegate.setDefaultNightMode(wallpaperForced)
            }

        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize managers")
            if (e is Error) throw e
        }

        // 6. Update state machine
        try {
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Failed to update state machine")
        }

        Timber.d("ShizukuX ${BuildConfig.VERSION_NAME} initialization complete")
    }

    /**
     * True when this process is the dedicated ":daemon" guard process declared in the manifest.
     * Application.onCreate runs for every process, so the auto-start of WatchdogService / Worker /
     * Alarm must be skipped there — otherwise the :daemon process would spawn its own duplicate
     * foreground watchdog and notification. minSdk 24 predates Application.getProcessName(), so
     * read /proc/self/cmdline directly.
     */
    private fun isDaemonProcess(): Boolean {
        return try {
            val name = java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
            name.endsWith(":daemon")
        } catch (e: Exception) {
            false
        }
    }
}
