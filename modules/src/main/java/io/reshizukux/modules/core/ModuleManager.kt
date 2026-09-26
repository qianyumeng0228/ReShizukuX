package io.reshizukux.modules.core

import android.content.Context
import io.reshizukux.modules.db.InstalledModule
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.permission.PermissionController
import io.reshizukux.modules.permission.PermissionLevel
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * 模块管理门面（单例，设计方案 §4.2，参考 Shevery AdbModuleManager.kt）。
 *
 * 目录布局：
 * ```
 * filesDir/modules/
 *   .staging/<id>/      # 解包暂存，校验通过后原子 rename
 *   <id>/               # 已安装模块
 *     module.prop
 *     customize.sh / service.sh / action.sh / uninstall.sh
 *     webroot/
 *     disable           # 存在 = DISABLED
 *     error.log         # 存在 = ERROR
 * ```
 *
 * P1：解包 + 校验 + 原子安装 + 启停标记 + 落库。customize.sh/uninstall.sh/service.sh
 * 的实际执行留 P2/P3（见 TODO）。
 */
object ModuleManager {

    private const val TAG = "ModuleManager"
    private const val MODULES_DIR = "modules"
    private const val STAGING_DIR = ".staging"
    private const val DISABLE_FILE = "disable"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            PermissionController.init(context)
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("ModuleManager not initialized; call init(context) first")

    private fun db() = ModuleDatabase.getInstance(requireContext())

    fun moduleRootDir(): File = File(requireContext().filesDir, MODULES_DIR).apply { mkdirs() }

    fun stagingDir(): File = File(moduleRootDir(), STAGING_DIR).apply { mkdirs() }

    fun getModuleDir(moduleId: String): File = File(moduleRootDir(), moduleId)

    // ------------------------------------------------------------------ install

    /**
     * 安装模块 ZIP：解包到 staging → module.prop 解析 → 安全校验 → 原子 rename → 落库（默认 DISABLED）。
     */
    fun install(zipFile: File): Result<ModuleInfo> {
        if (!zipFile.exists() || !zipFile.isFile) {
            return Result.failure(IllegalArgumentException("ZIP not found: ${zipFile.absolutePath}"))
        }
        val root = moduleRootDir()
        val staging = stagingDir()
        var stagedModuleDir: File? = null
        return try {
            val zipSha256 = ModuleSecurity.calculateSha256(zipFile)
            ZipFile(zipFile).use { zip ->
                // 1. 静态校验 entries（路径穿越第一重 + 资源上限）
                val violations = ModuleSecurity.validateZipEntries(zip)
                if (violations.isNotEmpty()) {
                    return Result.failure(IllegalStateException("ZIP validation failed: ${violations.joinToString("; ")}"))
                }
                // 2. 解析 module.prop
                val propEntry = zip.getEntry("module.prop")
                    ?: return Result.failure(IllegalStateException("module.prop missing in ZIP"))
                val tmpProp = File.createTempFile("module-prop", ".tmp", staging)
                zip.getInputStream(propEntry).use { input ->
                    tmpProp.outputStream().use { output -> input.copyTo(output) }
                }
                val spec = ModuleSpec.parseModuleProp(tmpProp)
                tmpProp.delete()
                if (spec == null) {
                    return Result.failure(IllegalStateException("module.prop parse/validation failed"))
                }

                // 3. 拒绝覆盖已安装模块（P1 不做 update）
                val target = File(root, spec.id)
                if (target.exists()) {
                    return Result.failure(IllegalStateException("module already installed: ${spec.id}"))
                }
                // 4. 解包到 staging/<id>/
                stagedModuleDir = File(staging, spec.id).apply { deleteRecursively(); mkdirs() }
                extractZip(zip, stagedModuleDir!!)

                // 5. 原子 rename staging/<id> -> modules/<id>
                if (!stagedModuleDir!!.renameTo(target)) {
                    return Result.failure(IllegalStateException("atomic rename failed for ${spec.id}"))
                }

                // TODO(P2): 在模块目录执行 customize.sh（工作目录=模块目录，超时 120s，失败回滚删除 target）
                // val r = ModuleExecutor.execute("sh customize.sh", envOf(spec), timeoutSec = 120)
                // if (r.exitCode != 0) { target.deleteRecursively(); return Result.failure(...) }

                // 6. 落库（默认 DISABLED + SAFE）
                val now = System.currentTimeMillis()
                val installed = InstalledModule(
                    id = spec.id,
                    name = spec.name,
                    version = spec.version,
                    versionCode = spec.versionCode,
                    author = spec.author,
                    description = spec.description,
                    state = ModuleState.DISABLED.name,
                    permissionLevel = PermissionLevel.SAFE.name,
                    customPermissions = 0,
                    sha256 = zipSha256,
                    publicKey = null,
                    installTime = now,
                    lastUpdateTime = now,
                    lastActionExitCode = null,
                    servicePid = null
                )
                db().moduleDao().upsert(installed)
                Timber.tag(TAG).i("installed module ${spec.id} v${spec.version}")
                Result.success(installed.toModuleInfo(target))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "install failed")
            stagedModuleDir?.deleteRecursively()
            Result.failure(e)
        }
    }

    private fun extractZip(zip: ZipFile, targetDir: File) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val outFile = File(targetDir, entry.name)
            // 第二重 canonical 级路径穿越校验
            if (!ModuleSecurity.isCanonicalUnder(outFile, targetDir)) {
                throw SecurityException("canonical path traversal blocked: ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ uninstall

    fun uninstall(moduleId: String): Boolean {
        val moduleDir = getModuleDir(moduleId)
        return try {
            // TODO(P2): 卸载前同步执行 uninstall.sh（超时 60s，失败不阻塞卸载，仅记日志）
            // if (File(moduleDir, "uninstall.sh").exists()) ModuleExecutor.execute("sh uninstall.sh", envOf(...), 60)
            db().moduleDao().deleteById(moduleId)
            moduleDir.deleteRecursively()
            Timber.tag(TAG).i("uninstalled module $moduleId")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "uninstall failed for $moduleId")
            false
        }
    }

    // ------------------------------------------------------------------ enable / disable

    fun enable(moduleId: String): Boolean {
        val moduleDir = getModuleDir(moduleId)
        if (!moduleDir.exists()) return false
        File(moduleDir, DISABLE_FILE).delete()
        db().moduleDao().updateState(moduleId, ModuleState.ENABLED.name)
        return true
    }

    fun disable(moduleId: String): Boolean {
        val moduleDir = getModuleDir(moduleId)
        if (!moduleDir.exists()) return false
        File(moduleDir, DISABLE_FILE).createNewFile()
        db().moduleDao().updateState(moduleId, ModuleState.DISABLED.name)
        // TODO(P3): kill -TERM 模块 service.sh 进程组（ModuleWatchdog 集成后实现）
        return true
    }

    // ------------------------------------------------------------------ queries

    fun getInstalledModules(): List<ModuleInfo> =
        db().moduleDao().getAll().map { it.toModuleInfo(getModuleDir(it.id)) }

    fun getModule(moduleId: String): ModuleInfo? =
        db().moduleDao().getById(moduleId)?.toModuleInfo(getModuleDir(moduleId))

    // ------------------------------------------------------------------ mapping

    /**
     * DB 行 → ModuleInfo。minSdk/requiresRoot/usesWebUI/usesShellBridge 不在实体里（设计方案 §4.4），
     * 从模块目录的 module.prop 现场补全（始终存在；解析失败则用默认值）。
     */
    private fun InstalledModule.toModuleInfo(moduleDir: File): ModuleInfo {
        val spec = ModuleSpec.parseModuleProp(File(moduleDir, "module.prop"))
        return ModuleInfo(
            id = id,
            name = name,
            version = version,
            versionCode = versionCode,
            author = author,
            description = description,
            minSdk = spec?.minSdk ?: 0,
            requiresRoot = spec?.requiresRoot ?: false,
            usesWebUI = spec?.usesWebUI ?: false,
            usesShellBridge = spec?.usesShellBridge ?: false,
            state = ModuleState.fromName(state),
            permissionLevel = permissionLevel,
            installTime = installTime,
            lastUpdateTime = lastUpdateTime,
            servicePid = servicePid,
            sha256 = sha256,
            publicKey = publicKey
        )
    }
}
