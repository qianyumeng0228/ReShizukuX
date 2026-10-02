package io.reshizukux.modules.core

import android.content.Context
import android.os.Build
import io.reshizukux.modules.db.InstalledModule
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.execution.CustomizeRunner
import io.reshizukux.modules.execution.ModuleLogs
import io.reshizukux.modules.execution.ServiceRunner
import io.reshizukux.modules.execution.UninstallRunner
import io.reshizukux.modules.permission.PermissionController
import io.reshizukux.modules.permission.PermissionLevel
import io.reshizukux.modules.watchdog.ModuleWatchdog
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
 *     .install_hash     # P7：安装时目录哈希树基线（篡改检测）
 * ```
 *
 * P1：解包 + 校验 + 原子安装 + 启停标记 + 落库。
 * P2：install 集成 customize.sh（失败回滚）、uninstall 集成 uninstall.sh（不阻塞）、
 *     action.sh 由 [io.reshizukux.modules.execution.ActionRunner] 执行。
 * P7：Ed25519 安装签名验证 + 更新公钥 pinning + 目录哈希树篡改检测（.install_hash）
 *     + CORRUPTED 状态 + repair/isCorrupted。
 */
object ModuleManager {

    private const val TAG = "ModuleManager"
    private const val MODULES_DIR = "modules"
    private const val STAGING_DIR = ".staging"
    private const val DISABLE_FILE = "disable"

    /** P7：安装时写入模块目录的哈希树基线文件名（不改 DB schema）。 */
    private const val INSTALL_HASH_FILE = ".install_hash"

    /** P7：ZIP 内可选的签名/公钥文件名（与 RepoModule.signature/publicKey 二选一来源）。 */
    private const val SIG_ENTRY = "module.sig"
    private const val PUBKEY_ENTRY = "module.pubkey"

    @Volatile
    private var appContext: Context? = null

    /**
     * 跨模块依赖倒置钩子（功能优先设计变更，2026-09）。
     *
     * 模块 service.sh 的后台保活由 :manager 进程的独立前台服务
     * `af.shizuku.manager.service.RuntimeModuleService` 托管。但 :modules 模块不能反向依赖
     * :manager（会成环），故由 manager 在启动时注入此回调；本类在拉起 service.sh 后触发它，
     * 由 manager 负责拉起前台保活服务（幂等）。
     */
    @Volatile
    var onBackgroundModuleActivated: (() -> Unit)? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            PermissionController.init(context)
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("ModuleManager not initialized; call init(context) first")

    /** 供同模块内 Runner/Watchdog 取 Context 访问数据库（不对外暴露）。 */
    internal fun appContext(): Context = requireContext()

    private fun db() = ModuleDatabase.getInstance(requireContext())

    fun moduleRootDir(): File = File(requireContext().filesDir, MODULES_DIR).apply { mkdirs() }

    fun stagingDir(): File = File(moduleRootDir(), STAGING_DIR).apply { mkdirs() }

    fun getModuleDir(moduleId: String): File = File(moduleRootDir(), moduleId)

    // ------------------------------------------------------------------ install

    /**
     * 安装/更新模块 ZIP：解包到 staging → module.prop 解析 → 签名验证 → 安全校验
     * → 原子 rename → customize.sh → 写哈希树基线 → 落库（默认 DISABLED）。
     *
     * @param signatureBase64 可选：来自仓库 manifest 的 Ed25519 签名（base64）；
     *   为空时回退读 ZIP 内 [SIG_ENTRY]。
     * @param publicKeyBase64 可选：来自仓库 manifest 的 Ed25519 公钥（base64）；
     *   为空时回退读 ZIP 内 [PUBKEY_ENTRY]。
     *   签名内容 = ZIP 的 SHA-256（hex 字符串 UTF-8 字节）。
     */
    fun install(
        zipFile: File,
        signatureBase64: String? = null,
        publicKeyBase64: String? = null
    ): Result<ModuleInfo> {
        if (!zipFile.exists() || !zipFile.isFile) {
            return Result.failure(IllegalArgumentException("ZIP not found: ${zipFile.absolutePath}"))
        }
        val root = moduleRootDir()
        val staging = stagingDir()
        var stagedModuleDir: File? = null
        var backupDir: File? = null
        var installedTarget: File? = null
        return try {
            val zipSha256 = ModuleSecurity.calculateSha256(zipFile)
            ZipFile(zipFile).use { zip ->
                // 1. 静态校验 entries（路径穿越第一重 + 资源上限）
                val violations = ModuleSecurity.validateZipEntries(zip)
                if (violations.isNotEmpty()) {
                    return Result.failure(IllegalStateException("ZIP validation failed: ${violations.joinToString("; ")}"))
                }
                // 1b. CRC 完整性校验（问题 #3）：逐个 entry 读全量数据并比对 central directory
                //     记录的 CRC32，损坏/截断的 zip 在解包前就拒绝，避免解出半个模块。
                verifyZipCrc(zip)
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

                // 问题 #9：minSdk 校验（module.prop 里声明了才检查）。
                if (spec.minSdk > 0 && Build.VERSION.SDK_INT < spec.minSdk) {
                    return Result.failure(IllegalStateException(
                        "模块需要 Android API ${spec.minSdk}+，当前 API ${Build.VERSION.SDK_INT}"
                    ))
                }

                // 3. 取签名/公钥：参数优先，其次 ZIP 内 module.sig / module.pubkey
                val effectiveSig = signatureBase64?.trim()?.takeIf { it.isNotEmpty() }
                    ?: readOptionalTextEntry(zip, SIG_ENTRY)
                val effectivePub = publicKeyBase64?.trim()?.takeIf { it.isNotEmpty() }
                    ?: readOptionalTextEntry(zip, PUBKEY_ENTRY)

                // 4. 安装签名验证（设计方案 §3.5.4 Level 2）
                if (!effectiveSig.isNullOrBlank() && !effectivePub.isNullOrBlank()) {
                    if (!ModuleSecurity.verifyEd25519Signature(zipSha256, effectiveSig, effectivePub)) {
                        return Result.failure(
                            IllegalStateException("签名验证失败 (Ed25519): ${spec.id}")
                        )
                    }
                }

                // 5. 更新场景：公钥 pinning（设计方案 §3.5.4）
                val existing = db().moduleDao().getById(spec.id)
                val target = File(root, spec.id)
                installedTarget = target
                if (existing != null) {
                    val oldPub = existing.publicKey?.takeIf { it.isNotBlank() }
                    val newPub = effectivePub?.takeIf { it.isNotBlank() }
                    when {
                        oldPub != null && newPub == null ->
                            return Result.failure(
                                IllegalStateException("更新被拒绝：已安装模块有签名，但更新包无签名")
                            )
                        oldPub != null && !oldPub.equals(newPub, ignoreCase = true) ->
                            return Result.failure(
                                IllegalStateException("更新被拒绝：公钥不一致（pinning）：${spec.id}")
                            )
                    }
                    // 旧目录移入 staging 备份，便于失败回滚
                    if (target.exists()) {
                        backupDir = File(staging, "${spec.id}.old-backup")
                        backupDir!!.deleteRecursively()
                        if (!target.renameTo(backupDir)) {
                            return Result.failure(
                                IllegalStateException("无法备份旧模块目录 for update: ${spec.id}")
                            )
                        }
                    }
                } else {
                    // 全新安装：目标目录必须不存在
                    if (target.exists()) {
                        return Result.failure(IllegalStateException("module already installed: ${spec.id}"))
                    }
                }

                // 6. 解包到 staging/<id>/（跳过签名材料，不写入模块目录）
                stagedModuleDir = File(staging, spec.id).apply { deleteRecursively(); mkdirs() }
                extractZip(zip, stagedModuleDir!!)

                // 7. 原子 rename staging/<id> -> modules/<id>
                if (!stagedModuleDir!!.renameTo(target)) {
                    return Result.failure(IllegalStateException("atomic rename failed for ${spec.id}"))
                }

                // 8. 执行 customize.sh（超时 120s，工作目录=模块目录，已过 CommandFilter）。
                //    失败（exitCode != 0 或超时）→ 回滚删除 target；更新场景恢复备份。
                val customizeResult = CustomizeRunner.run(target, spec)
                if (customizeResult.exitCode != 0 || customizeResult.timedOut) {
                    target.deleteRecursively()
                    restoreBackup(backupDir, target)
                    return Result.failure(
                        IllegalStateException(
                            "customize.sh failed (exit=${customizeResult.exitCode}, " +
                                "timedOut=${customizeResult.timedOut}): ${customizeResult.stderr}"
                        )
                    )
                }

                // 9. P7：写入目录哈希树基线（篡改检测，设计方案 §3.5.4 Level 1）。
                //    在 customize 之后计算，捕获 customize 落盘的所有文件。
                runCatching {
                    File(target, INSTALL_HASH_FILE).writeText(
                        ModuleSecurity.calculateDirHashTree(target)
                    )
                }.onFailure {
                    Timber.tag(TAG).w(it, "write .install_hash failed for ${spec.id}")
                }

                // 10. 落库（默认 DISABLED + SAFE；公钥 pinning）
                val now = System.currentTimeMillis()
                val pinnedPublicKey = effectivePub ?: existing?.publicKey
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
                    publicKey = pinnedPublicKey,
                    installTime = existing?.installTime ?: now,
                    lastUpdateTime = now,
                    lastActionExitCode = null,
                    servicePid = null
                )
                db().moduleDao().upsert(installed)
                backupDir?.deleteRecursively()
                Timber.tag(TAG).i(
                    "installed module ${spec.id} v${spec.version} (update=${existing != null}, signed=${pinnedPublicKey != null})"
                )
                Result.success(installed.toModuleInfo(target))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "install failed")
            stagedModuleDir?.deleteRecursively()
            // 失败回滚：删除半安装产物，更新场景恢复旧目录备份
            installedTarget?.let { if (it.exists()) it.deleteRecursively() }
            installedTarget?.let { target -> backupDir?.let { restoreBackup(it, target) } }
            Result.failure(e)
        }
    }

    /** 从 ZIP 读可选文本 entry（trim 后空串视为不存在）。 */
    private fun readOptionalTextEntry(zip: ZipFile, name: String): String? {
        val e = zip.getEntry(name) ?: return null
        return zip.getInputStream(e).use {
            it.bufferedReader(Charsets.UTF_8).readText().trim()
        }.ifBlank { null }
    }

    private fun restoreBackup(backupDir: File?, target: File) {
        backupDir ?: return
        runCatching {
            if (backupDir.exists() && !target.exists()) {
                backupDir.renameTo(target)
            }
        }.onFailure { Timber.tag(TAG).w(it, "restore backup failed") }
    }

    /**
     * 逐 entry 校验 CRC32（问题 #3）：读完全部未压缩数据后与 central directory 记录的
     * [java.util.zip.ZipEntry.getCrc] 比对。任一项不匹配即抛 [java.util.zip.ZipException]。
     */
    private fun verifyZipCrc(zip: ZipFile) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val expected = entry.crc
            if (expected == 0L) continue
            val crc32 = java.util.zip.CRC32()
            zip.getInputStream(entry).use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    crc32.update(buf, 0, n)
                }
            }
            if (crc32.value != expected) {
                throw java.util.zip.ZipException("ZIP CRC 校验失败：${entry.name}")
            }
        }
    }

    private fun extractZip(zip: ZipFile, targetDir: File) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            // 签名材料不落地到模块目录（避免被哈希树覆盖 / 被篡改）
            if (entry.name == SIG_ENTRY || entry.name == PUBKEY_ENTRY) continue
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
            val row = db().moduleDao().getById(moduleId) ?: run {
                Timber.tag(TAG).w("uninstall: module not found in db: $moduleId")
                return false
            }
            // 若当前 ENABLED，先写 disable 标记（P3 在此 kill -TERM service.sh 进程组；P2 仅标记）
            if (row.state == ModuleState.ENABLED.name) {
                File(moduleDir, DISABLE_FILE).createNewFile()
            }
            // 卸载钩子：超时 60s，失败不阻塞（日志已写并复制到 .cache 保留）
            UninstallRunner.run(moduleDir, moduleId)
            // 删除模块目录
            moduleDir.deleteRecursively()
            // 删数据库行
            db().moduleDao().deleteById(moduleId)
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

        // P7：启用前哈希树篡改检测（设计方案 §3.5.4 Level 1）
        if (!verifyIntegrity(moduleDir, moduleId)) {
            db().moduleDao().updateState(moduleId, ModuleState.CORRUPTED.name)
            Timber.tag(TAG).w("module $moduleId marked CORRUPTED (hash tree mismatch)")
            return false
        }

        File(moduleDir, DISABLE_FILE).delete()
        db().moduleDao().updateState(moduleId, ModuleState.ENABLED.name)

        // P3：用户重新启用即清除 watchdog 熔断计数（设计方案 §3.3.3）；
        // 若模块带 service.sh 且权限允许，启用即拉起（即时生效，不等下一轮 watchdog）。
        ModuleWatchdog.clearCircuit(moduleId)
        if (File(moduleDir, "service.sh").exists()) {
            runCatching { ServiceRunner.startService(moduleId) }
                .onFailure { Timber.tag(TAG).w(it, "startService on enable failed for $moduleId") }
            // 功能优先：拉起独立前台 RuntimeModuleService 做后续后台保活（幂等）。
            // :modules 不依赖 :manager，通过 [onBackgroundModuleActivated] 钩子回调。
            runCatching { onBackgroundModuleActivated?.invoke() }
                .onFailure { Timber.tag(TAG).w(it, "trigger RuntimeModuleService start failed") }
        }
        return true
    }

    fun disable(moduleId: String): Boolean {
        val moduleDir = getModuleDir(moduleId)
        if (!moduleDir.exists()) return false
        File(moduleDir, DISABLE_FILE).createNewFile()
        db().moduleDao().updateState(moduleId, ModuleState.DISABLED.name)
        // P3：停用即 SIGTERM（→SIGKILL 兜底）停止 service.sh 进程（设计方案 §3.2 即时生效）
        runCatching { ServiceRunner.stopService(moduleId) }
            .onFailure { Timber.tag(TAG).w(it, "stopService on disable failed for $moduleId") }
        return true
    }

    // ------------------------------------------------------------------ P7 篡改/CORRUPTED

    /**
     * 比对模块目录当前哈希树与安装时写入的 [INSTALL_HASH_FILE] 基线。
     *
     *  - 无基线（旧模块升级上来的）：不阻塞启用，补写一份基线作为兜底；
     *  - 一致：true；
     *  - 不一致：false（调用方置 CORRUPTED）。
     */
    private fun verifyIntegrity(moduleDir: File, moduleId: String): Boolean {
        val baselineFile = File(moduleDir, INSTALL_HASH_FILE)
        val recorded = runCatching { baselineFile.readText().trim() }.getOrNull()
        if (recorded.isNullOrEmpty()) {
            // 兼容旧模块：首次启用时建立基线
            runCatching {
                baselineFile.writeText(ModuleSecurity.calculateDirHashTree(moduleDir))
            }.onFailure { Timber.tag(TAG).w(it, "write baseline failed for $moduleId") }
            return true
        }
        val current = ModuleSecurity.calculateDirHashTree(moduleDir)
        if (!recorded.equals(current, ignoreCase = true)) {
            Timber.tag(TAG).w(
                "hash tree mismatch for $moduleId: recorded=$recorded current=$current"
            )
            return false
        }
        return true
    }

    /** 模块是否处于 CORRUPTED 状态（action/service/webui 执行前应先检查）。 */
    fun isCorrupted(moduleId: String): Boolean =
        db().moduleDao().getById(moduleId)?.state == ModuleState.CORRUPTED.name

    /**
     * 修复 CORRUPTED 模块：用户确认文件变更是故意的后，
     * 重新计算哈希树写入 [INSTALL_HASH_FILE]，状态回退到 DISABLED（不直接启用）。
     */
    fun repair(moduleId: String): Boolean {
        val moduleDir = getModuleDir(moduleId)
        if (!moduleDir.exists()) return false
        return try {
            val hash = ModuleSecurity.calculateDirHashTree(moduleDir)
            File(moduleDir, INSTALL_HASH_FILE).writeText(hash)
            File(moduleDir, DISABLE_FILE).createNewFile()
            db().moduleDao().updateState(moduleId, ModuleState.DISABLED.name)
            Timber.tag(TAG).i("repaired module $moduleId, new baseline written")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "repair failed for $moduleId")
            false
        }
    }

    // ------------------------------------------------------------------ queries

    fun getInstalledModules(): List<ModuleInfo> =
        db().moduleDao().getAll().map { it.toModuleInfo(getModuleDir(it.id)) }

    fun getModule(moduleId: String): ModuleInfo? =
        db().moduleDao().getById(moduleId)?.toModuleInfo(getModuleDir(moduleId))

    // ------------------------------------------------------------------ logs

    /** 读 action-last.log（手动动作输出，尾部 64KB）；模块目录不存在返回 null。 */
    fun getActionLog(moduleId: String): String? =
        ModuleLogs.read(getModuleDir(moduleId), "action-last.log")

    /** 读 customize-last.log（安装脚本输出）。 */
    fun getCustomizeLog(moduleId: String): String? =
        ModuleLogs.read(getModuleDir(moduleId), "customize-last.log")

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
