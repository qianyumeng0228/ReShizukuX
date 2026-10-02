package io.reshizukux.xposed.scan

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * One installed application that looks like an Xposed module.
 *
 * Identification mirrors LSPatch's own loader-side check (ModuleLoader): a *modern* module declares
 * its entry points under {@code META-INF/xposed/} (a {@code java_init.list}, optionally described by
 * a {@code module.prop}), while a *legacy* module (the original Xposed API) ships an
 * {@code assets/xposed_init} resource and/or advertises a minimum Xposed version through the
 * {@code xposedminversion} manifest meta-data.
 */
data class XposedModuleInfo(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val icon: Drawable?,
    /** True when the module uses the modern {@code META-INF/xposed/} layout; false for legacy. */
    val isModern: Boolean,
    /**
     * The module's declared target Xposed API level: read from {@code module.prop}'s
     * {@code targetApiVersion} for modern modules, from the {@code xposedminversion} meta-data for
     * legacy ones, 0 when unknown.
     */
    val targetApiVersion: Int,
    /** Absolute path to the module's base apk ({@link ApplicationInfo#sourceDir}). */
    val apkPath: String,
)

/**
 * Enumerates installed applications on this device and picks out the ones that are Xposed modules.
 *
 * This only *detects* modules -- it never patches or loads anything. Patching lands in a later
 * phase. Reading each apk's central directory directly off [ApplicationInfo.sourceDir] (rather than
 * asking the package manager) is how the loader itself decides whether a zip is a module, so the
 * two agree.
 */
class XposedModuleScanner(private val packageManager: PackageManager) {

    companion object {
        /** Modern module: explicit list of entry class names. */
        private const val MODERN_INIT_LIST = "META-INF/xposed/java_init.list"

        /** Modern module: descriptor properties (carries {@code targetApiVersion}). */
        private const val MODULE_PROP = "META-INF/xposed/module.prop"

        /** Legacy module: a plain-text file listing entry class names, one per line. */
        private const val LEGACY_INIT = "assets/xposed_init"

        /** Legacy module: manifest meta-data naming the minimum Xposed API it needs. */
        private const val META_XPOSED_MIN_VERSION = "xposedminversion"

        // 问题 #10：扫描结果缓存（maxSize=1）。遍历全部已安装包并逐个解 apk zip 很贵，
        // 向导在选目标/选模块间来回跳转时 5 分钟内直接复用上次结果。
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val CACHE_KEY = "scan"

        private class CacheEntry(val result: List<XposedModuleInfo>, val atMillis: Long)

        private val cache = android.util.LruCache<String, CacheEntry>(1)
    }

    /**
     * @return every installed package that looks like an Xposed module, in no particular order.
     *         Packages that cannot be opened (uninstalled mid-scan, unreadable apk) are skipped.
     *         5 分钟内重复调用直接复用缓存（问题 #10）。
     */
    fun scan(): List<XposedModuleInfo> {
        cache.get(CACHE_KEY)?.let {
            if (System.currentTimeMillis() - it.atMillis < CACHE_TTL_MS) {
                return it.result
            }
        }
        val result = doScan()
        cache.put(CACHE_KEY, CacheEntry(result, System.currentTimeMillis()))
        return result
    }

    private fun doScan(): List<XposedModuleInfo> {
        val packages = try {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (e: Throwable) {
            // Some restricted environments refuse the query entirely; report nothing rather than crash.
            emptyList()
        }
        return packages.mapNotNull { detect(it) }
    }

    private fun detect(packageInfo: PackageInfo): XposedModuleInfo? {
        val appInfo: ApplicationInfo = packageInfo.applicationInfo ?: return null
        val apkPath: String = appInfo.sourceDir ?: return null

        var hasModernList = false
        var hasModuleProp = false
        var hasLegacyInit = false
        var targetApi = 0

        var zip: ZipFile? = null
        try {
            zip = ZipFile(File(apkPath))
            hasModernList = zip.getEntry(MODERN_INIT_LIST) != null

            zip.getEntry(MODULE_PROP)?.let { propEntry ->
                hasModuleProp = true
                runCatching {
                    val props = Properties()
                    zip.getInputStream(propEntry).use { props.load(it) }
                    targetApi = props.getProperty("targetApiVersion")?.trim()?.toIntOrNull() ?: 0
                }
            }

            hasLegacyInit = zip.getEntry(LEGACY_INIT) != null
        } catch (_: Throwable) {
            // Not a readable zip; treat as non-module.
        } finally {
            runCatching { zip?.close() }
        }

        // Legacy modules may be detected purely from their manifest meta-data.
        val legacyMetaVersion = appInfo.metaData?.getInt(META_XPOSED_MIN_VERSION, 0) ?: 0

        val isModern = hasModernList || hasModuleProp
        val isLegacy = hasLegacyInit || legacyMetaVersion > 0
        if (!isModern && !isLegacy) return null

        if (targetApi == 0) {
            targetApi = legacyMetaVersion
        }

        val label = runCatching { appInfo.loadLabel(packageManager).toString() }
            .getOrDefault(packageInfo.packageName)
        val icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull()

        return XposedModuleInfo(
            packageName = packageInfo.packageName,
            name = label,
            versionName = packageInfo.versionName,
            versionCode = packageInfo.longVersionCode,
            icon = icon,
            isModern = isModern,
            targetApiVersion = targetApi,
            apkPath = apkPath,
        )
    }
}
