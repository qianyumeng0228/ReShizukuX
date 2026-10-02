package org.lsposed.lspatch.manager

import android.content.pm.PackageManager
import android.os.Build
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.ModuleCode
import java.nio.channels.Channels
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Manager-side counterpart of LSPatch's `ModuleLoader` / `LoadedModules.fromApk`: turns an installed
 * module APK (named by package) into the [LoadedModule] the precompiled loader.dex expects to read
 * back over IPC.
 *
 * A module APK is read straight off [android.content.pm.ApplicationInfo.sourceDir] -- copied nowhere.
 * Its `classes.dex` / `classes2.dex` ... entries are each mapped into a [SharedMemory] region handed
 * to the patched process (the framework loads dexes out of shared memory, not off this apk path), and
 * its entry classes come from `META-INF/xposed/java_init.list` (modern) or `assets/xposed_init`
 * (legacy), exactly like the daemon's `FileSystem.loadModule`.
 *
 * SharedMemory is API 27; this app's minSdk is 24. On an older runtime the dexes simply cannot be
 * handed over this channel, so [loadModuleCode] returns null and the module is skipped rather than
 * crashing the bind. The verification device runs a modern API, so this path is what actually fires.
 */
object ModuleApkLoader {

    private const val TAG = "LSPatch-ModLoader"

    /** Leading digits only, so "101.0" -> 101 and absent/garbage -> 0. */
    private fun leadingInt(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        var i = 0
        while (i < value.length && value[i].isDigit()) i++
        if (i == 0) return 0
        return runCatching { Integer.parseInt(value.substring(0, i)) }.getOrDefault(0)
    }

    private fun readName(apk: ZipFile, entryName: String, out: MutableList<String>) {
        val entry = apk.getEntry(entryName) ?: return
        runCatching {
            apk.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach(out::add)
            }
        }.onFailure { Log.e(TAG, "Can not open $entryName", it) }
    }

    private fun readDexes(apk: ZipFile, out: MutableList<SharedMemory>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            Log.w(TAG, "SharedMemory needs API 27; this runtime is API ${Build.VERSION.SDK_INT}, skipping dex mapping")
            return
        }
        var secondary = 2
        var dex = apk.getEntry("classes.dex")
        while (dex != null) {
            val size = dex.size
            if (size <= 0) {
                dex = apk.getEntry("classes${secondary}.dex"); secondary++
                continue
            }
            runCatching {
                // SharedMemory.create takes an int size; a classesN.dex is orders of magnitude under 2GB.
                val memory = SharedMemory.create(null, size.toInt())
                val buffer = memory.mapReadWrite()
                apk.getInputStream(dex).use { input ->
                    val channel = Channels.newChannel(input)
                    // Stop as soon as the region is full: the buffer is exactly dex.size bytes and the
                    // stream decompresses to exactly that. Reading past a full MappedByteBuffer makes
                    // ReadableByteChannelImpl.read() return 0 forever (it has no backing array, so once
                    // dst.remaining()==0 each read reads 0 bytes and never signals EOF) -- a busy-loop
                    // that pinned a binder thread at 100% CPU and ANR-killed the patched app.
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) == -1) break
                    }
                }
                SharedMemory.unmap(buffer)
                memory.setProtect(OsConstants.PROT_READ)
                out.add(memory)
                Log.i(TAG, "mapped ${dex.name} size=$size bytes into SharedMemory")
            }.onFailure {
                Log.w(TAG, "Can not load ${dex.name} in ${apk.name}", it)
            }
            dex = apk.getEntry("classes${secondary}.dex"); secondary++
        }
    }

    /**
     * Reads [apkPath] into a [ModuleCode], or null when the apk is not a loadable module of any kind.
     * Modern (targetApi >= 101) and legacy (has assets/xposed_init) are both accepted here; the
     * modern/legacy split happens at [buildLoadedModule] against what the caller asked for.
     */
    fun loadModuleCode(apkPath: String): ModuleCode? {
        val file = ModuleCode()
        val dexes = ArrayList<SharedMemory>()
        val classNames = ArrayList<String>(1)
        val libraryNames = ArrayList<String>(1)
        return runCatching {
            ZipFile(apkPath).use { apk ->
                val props = Properties()
                apk.getEntry("META-INF/xposed/module.prop")?.let { prop ->
                    runCatching { apk.getInputStream(prop).use { props.load(it) } }
                        .onFailure { Log.w(TAG, "Malformed module.prop in $apkPath", it) }
                }

                val targetApi = leadingInt(props.getProperty("targetApiVersion"))
                val autoHotReload = props.getProperty("autoHotReload", "false").trim().equals("true", ignoreCase = true)
                val exceptionPassthrough = props.getProperty("exceptionMode", "").trim().equals("passthrough", ignoreCase = true)
                val hasLegacyFile = apk.getEntry("assets/xposed_init") != null

                val legacy: Boolean
                when {
                    targetApi >= 101 -> {
                        legacy = false
                        readName(apk, "META-INF/xposed/java_init.list", classNames)
                        readName(apk, "META-INF/xposed/native_init.list", libraryNames)
                    }
                    hasLegacyFile -> {
                        legacy = true
                        readName(apk, "assets/xposed_init", classNames)
                        readName(apk, "assets/native_init", libraryNames)
                    }
                    else -> {
                        Log.w(TAG, "Unsupported or non-module APK: $apkPath (targetApi=$targetApi)")
                        return null
                    }
                }

                if (classNames.isEmpty()) {
                    Log.e(TAG, "No entry classes for $apkPath")
                    return null
                }

                readDexes(apk, dexes)
                if (dexes.isEmpty()) return null

                file.preLoadedDexes = dexes
                file.moduleClassNames = classNames
                file.moduleLibraryNames = libraryNames
                file.legacy = legacy
                file.targetApiVersion = targetApi
                file.autoHotReload = autoHotReload
                file.exceptionPassthrough = exceptionPassthrough
                file.nativeLibraryDir = null // LSPatch never injects system_server; no staged libs.
                file
            }
        }.getOrElse {
            Log.e(TAG, "Can not open $apkPath", it)
            dexes.forEach { runCatching { it.close() } }
            null
        }
    }

    /**
     * Builds the [LoadedModule] for [packageName] as installed, or null when it cannot be loaded or
     * is not of the requested [requireLegacy] kind. A module of the other kind has its freshly mapped
     * dexes closed here rather than left for the GC, because modern and legacy are served by two
     * separate calls and the caller of one never sees the other's memory.
     *
     * [service] is intentionally null: the minimum-viable manager does not yet broker a module's own
     * IModuleService (remote prefs/files); modules that do not call those APIs load fine.
     */
    fun buildLoadedModule(pm: PackageManager, packageName: String, requireLegacy: Boolean): LoadedModule? {
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrElse {
            Log.w(TAG, "Module $packageName not installed; skipping")
            return null
        }
        val apkPath = appInfo.sourceDir ?: return null
        val code = loadModuleCode(apkPath) ?: return null
        if (code.legacy != requireLegacy) {
            code.preLoadedDexes?.forEach { runCatching { it.close() } }
            return null
        }
        val appId = if (appInfo.uid < 0) -1 else appInfo.uid % 100000
        val versionCode = runCatching { pm.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)
        Log.i(TAG, "buildLoadedModule $packageName requireLegacy=$requireLegacy legacy=${code.legacy} dexes=${code.preLoadedDexes?.size} entryClasses=${code.moduleClassNames}")
        return LoadedModule().apply {
            this.packageName = packageName
            this.appId = appId
            this.versionCode = versionCode
            this.apkPath = apkPath
            this.code = code
            this.applicationInfo = appInfo
            this.service = null
        }
    }

    /**
     * Like [buildLoadedModule] but does not filter on modern/legacy -- used by hot reload, where the
     * manager hands the patched process a fresh generation of whichever kind it already runs.
     */
    fun buildLoadedModuleAny(pm: PackageManager, packageName: String): LoadedModule? {
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val code = loadModuleCode(apkPath) ?: return null
        val appId = if (appInfo.uid < 0) -1 else appInfo.uid % 100000
        val versionCode = runCatching { pm.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)
        return LoadedModule().apply {
            this.packageName = packageName
            this.appId = appId
            this.versionCode = versionCode
            this.apkPath = apkPath
            this.code = code
            this.applicationInfo = appInfo
            this.service = null
        }
    }
}
