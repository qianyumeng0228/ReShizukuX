package af.shizuku.manager.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复管理器（ReShizukuX beta1 组A）。
 *
 * 备份内容：
 *  - SharedPreferences "settings"（ShizukuSettings.NAME）全量 key/value
 *  - 已安装模块 id 列表
 *  - 每个模块的权限档位
 *
 * 存储路径：/sdcard/ReShizukuX/backup-<yyyyMMdd-HHmmss>.json
 * 通过 Shizuku shell（Shizuku.newProcess）执行文件写入，利用 UID 2000 的 sdcard 权限。
 * 若 shell 不可用，则回退到应用外部存储目录。
 */
object BackupManager {

    private const val BACKUP_DIR_NAME = "ReShizukuX"
    private const val PREFS_NAME = "settings" // ShizukuSettings.NAME

    data class BackupEntry(
        val fileName: String,
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long
    )

    /** 执行 shell 命令；stdout 合并返回。shell 不可用时返回 null。 */
    private fun execShell(cmd: String): String? {
        return runCatching {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        }.getOrNull()
    }

    suspend fun createBackup(context: Context): Result<BackupEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val fileName = "backup-$ts.json"

            val root = JSONObject()
            root.put("version", 1)
            root.put("createdAt", System.currentTimeMillis())
            root.put("device", android.os.Build.DEVICE)

            // 1. SharedPreferences "settings"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefsJson = JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> prefsJson.put(k, v)
                    is Int -> prefsJson.put(k, v)
                    is Long -> prefsJson.put(k, v)
                    is Float -> prefsJson.put(k, v)
                    is String -> prefsJson.put(k, v)
                    else -> v?.toString()?.let { prefsJson.put(k, it) }
                }
            }
            root.put("prefs", prefsJson)

            // 2. 模块列表 + 权限设置
            val modules = JSONObject()
            runCatching {
                val list = io.reshizukux.modules.core.ModuleManager.getInstalledModules()
                list.forEach { info ->
                    val m = JSONObject()
                    m.put("id", info.id)
                    m.put("name", info.name)
                    m.put("version", info.version)
                    m.put("enabled", info.state == io.reshizukux.modules.core.ModuleState.ENABLED)
                    m.put("permissionLevel", info.permissionLevel)
                    modules.put(info.id, m)
                }
            }
            root.put("modules", modules)

            val payload = root.toString(2)

            // 写入：优先通过 shell 写到 /sdcard/ReShizukuX/
            val sdcardDir = File(Environment.getExternalStorageDirectory(), BACKUP_DIR_NAME)
            val sdcardTarget = File(sdcardDir, fileName)
            val shellOk = execShell(
                "mkdir -p '${sdcardDir.absolutePath}' && cat > '${sdcardTarget.absolutePath}' <<'RESHIZUKUX_EOF'\n$payload\nRESHIZUKUX_EOF && echo OK"
            )?.contains("OK") == true

            val finalFile = if (shellOk) {
                sdcardTarget
            } else {
                val dir = File(context.getExternalFilesDir(null), "backups")
                dir.mkdirs()
                File(dir, fileName).apply { writeText(payload, Charsets.UTF_8) }
            }

            BackupEntry(
                fileName = fileName,
                path = finalFile.absolutePath,
                sizeBytes = finalFile.length(),
                lastModified = finalFile.lastModified()
            )
        }
    }

    suspend fun listBackups(context: Context): List<BackupEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<BackupEntry>()
        val ls = execShell(
            "ls -1 '${Environment.getExternalStorageDirectory()}/$BACKUP_DIR_NAME'/backup-*.json 2>/dev/null"
        )
        ls?.lines()?.filter { it.isNotBlank() }?.forEach { path ->
            val f = File(path.trim())
            val stat = execShell("stat -c '%s %Y' '${f.absolutePath}' 2>/dev/null")?.trim()
            val parts = stat?.split(" ")
            val size = parts?.getOrNull(0)?.toLongOrNull() ?: f.length()
            val mtime = parts?.getOrNull(1)?.toLongOrNull()?.times(1000L) ?: f.lastModified()
            result += BackupEntry(f.name, f.absolutePath, size, mtime)
        }
        val fallback = File(context.getExternalFilesDir(null), "backups")
        fallback.listFiles { f -> f.name.startsWith("backup-") && f.name.endsWith(".json") }
            ?.forEach { f ->
                result += BackupEntry(f.name, f.absolutePath, f.length(), f.lastModified())
            }
        result.sortedByDescending { it.lastModified }
    }

    suspend fun deleteBackup(entry: BackupEntry): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val shellOk = execShell("rm -f '${entry.path}' && echo OK")?.contains("OK") == true
            if (!shellOk) File(entry.path).delete() else true
        }.getOrDefault(false)
    }

    /**
     * 从 SAF 选择的 Uri 读取备份 JSON 并恢复。
     * 恢复 SharedPreferences + 模块权限设置；模块本身不重装（提示用户）。
     */
    suspend fun restoreFromUri(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: throw IllegalStateException("无法读取备份文件")
            val root = JSONObject(text)

            val prefs = root.optJSONObject("prefs") ?: JSONObject()
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            prefs.keys().forEach { k ->
                when (val v = prefs.get(k)) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is String -> editor.putString(k, v)
                    else -> editor.putString(k, v?.toString())
                }
            }
            editor.apply()

            val modules = root.optJSONObject("modules") ?: JSONObject()
            modules.keys().forEach { id ->
                val m = modules.optJSONObject(id) ?: return@forEach
                runCatching {
                    val levelName = m.optString("permissionLevel", "SAFE")
                    val level = runCatching {
                        io.reshizukux.modules.permission.PermissionLevel.valueOf(levelName)
                    }.getOrDefault(io.reshizukux.modules.permission.PermissionLevel.SAFE)
                    io.reshizukux.modules.permission.PermissionController.setPermissionLevel(id, level)
                }
            }
        }
    }
}
