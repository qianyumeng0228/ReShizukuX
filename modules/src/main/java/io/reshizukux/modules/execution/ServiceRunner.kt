package io.reshizukux.modules.execution

import android.os.Build
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleSpec
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.permission.PermissionController
import timber.log.Timber
import java.io.File

/**
 * service.sh 后台脚本启动/停止/存活检测（设计方案 §3.3.3）。
 *
 * 进程模型：service.sh 通过 shell 后台启动，用 `exec -a '<moduleId>_service' sh service.sh`
 * 设置 argv[0] 进程名，便于 `pgrep -f '<id>_service'` 检测存活。进程脱离 app 生命周期——
 * Shizuku 模式下由 shizuku server 收养；daemon（root shell）模式下由持久 root shell 收养。
 *
 * 执行通道（ShellBridge）：
 *  - 主进程（UI enable/disable）：默认走 Shizuku.newProcess（[ModuleExecutor]），binder 已连接。
 *  - :daemon 进程（watchdog 拉起）：Shizuku binder 不可用（daemon 自身负责启动 server），
 *    由 [af.shizuku.manager.service.ShizukuDaemonService] 通过 [installShell] 注入 libsu root shell。
 *
 * pid 记录：启动后 `pgrep -f '<id>_service'` 取首个 pid 写入 `<moduleDir>/service.pid` 并落库。
 */
object ServiceRunner {

    private const val TAG = "ServiceRunner"
    private const val PID_FILE = "service.pid"
    private const val STOP_GRACE_MS = 3_000L
    private const val STOP_POLL_MS = 250L

    /**
     * 注入式 shell 执行器：运行一条 shell 命令并返回 stdout（trim 后）。
     * 默认未注入时使用 Shizuku.newProcess（主进程）。
     */
    @Volatile
    private var shell: ((String) -> String)? = null

    /** :daemon 进程注入 libsu root shell；主进程无需调用（走默认 Shizuku 通道）。 */
    fun installShell(exec: (String) -> String) {
        shell = exec
    }

    private fun sh(cmd: String): String {
        val exec = shell
        return try {
            if (exec != null) {
                exec(cmd).trim()
            } else {
                // 默认通道：Shizuku.newProcess（主进程 binder 已连接）。
                ModuleExecutor.execute(cmd, timeoutSec = 10).stdout.trim()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "shell exec failed: $cmd")
            ""
        }
    }

    // ------------------------------------------------------------------ start

    /**
     * 启动模块 service.sh（后台常驻，不等待）。
     *
     * 前置校验：canService 权限 → service.sh 存在 → CommandFilter（HIGH 直接拒绝）。
     * @return true 表示已发起启动并取到 pid；false 表示校验未过或启动失败。
     */
    fun startService(moduleId: String): Boolean {
        if (!PermissionController.canService(moduleId)) {
            Timber.tag(TAG).w("startService denied: no SERVICE permission for $moduleId")
            return false
        }
        val moduleDir = ModuleManager.getModuleDir(moduleId)
        val scriptFile = File(moduleDir, "service.sh")
        if (!scriptFile.exists()) {
            Timber.tag(TAG).d("startService: no service.sh for $moduleId")
            return false
        }
        // 已在运行则不重复启动
        if (isServiceRunning(moduleId)) {
            Timber.tag(TAG).d("startService: $moduleId already running, skip")
            return true
        }

        val content = runCatching { scriptFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val filter = CommandFilter.filter(content)
        if (filter.hasHighRisk) {
            Timber.tag(TAG).w(
                "service.sh blocked by CommandFilter (HIGH risk): " +
                    filter.matchedLines.joinToString(" | ")
            )
            return false
        }

        val spec = ModuleSpec.parseModuleProp(File(moduleDir, "module.prop"))
            ?: run {
                Timber.tag(TAG).w("startService: cannot parse module.prop for $moduleId")
                return false
            }

        // 确保 logs 目录存在，stdout/stderr 重定向到 logs/service.log
        ModuleLogs.logsDir(moduleDir)

        val dir = moduleDir.absolutePath
        val sdk = Build.VERSION.SDK_INT.toString()
        // 后台启动：exec -a 设置进程名；& 让外层 sh 立即退出，service.sh 被收养后常驻。
        val startCmd = "cd '$dir' && " +
            "MODULE_DIR='$dir' MODULE_ID='$moduleId' ANDROID_SDK='$sdk' MODULE_VERSION='${spec.version}' " +
            "exec -a '${moduleId}_service' sh service.sh > logs/service.log 2>&1 &"
        sh(startCmd)

        // 给 fork + exec 一点时间，再 pgrep 取 pid
        Thread.sleep(500)
        val pid = queryPid(moduleId)
        if (pid == null) {
            Timber.tag(TAG).w("startService: $moduleId launched but pgrep found no pid")
            return false
        }
        writePidFile(moduleDir, pid)
        runCatching {
            ModuleDatabase.getInstance(ModuleManager.appContext()).moduleDao()
                .updateServicePid(moduleId, pid)
        }
        Timber.tag(TAG).i("service.sh started for $moduleId pid=$pid")
        return true
    }

    // ------------------------------------------------------------------ stop

    /**
     * 停止模块 service.sh：SIGTERM → 等 [STOP_GRACE_MS] → SIGKILL 兜底 → 删 pid 文件。
     */
    fun stopService(moduleId: String): Boolean {
        val moduleDir = ModuleManager.getModuleDir(moduleId)
        val pid = getServicePid(moduleId)
        if (pid == null) {
            // 无 pid 记录也兜底按进程名杀一次
            sh("pkill -TERM -f '${pgrepPattern(moduleId)}' 2>/dev/null")
            clearPid(moduleDir, moduleId)
            return true
        }
        sh("kill -TERM $pid 2>/dev/null")
        // 轮询等待退出
        val deadline = System.currentTimeMillis() + STOP_GRACE_MS
        while (System.currentTimeMillis() < deadline && isServiceRunning(moduleId)) {
            try { Thread.sleep(STOP_POLL_MS) } catch (_: InterruptedException) { break }
        }
        if (isServiceRunning(moduleId)) {
            Timber.tag(TAG).w("service $moduleId still alive after TERM, sending KILL")
            sh("kill -9 $pid 2>/dev/null")
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }
        clearPid(moduleDir, moduleId)
        Timber.tag(TAG).i("service.sh stopped for $moduleId pid=$pid")
        return true
    }

    // ------------------------------------------------------------------ liveness

    /** pgrep 命中即存活（bracket 自匹配排除，与 ShizukuDaemonService 同一手法）。 */
    fun isServiceRunning(moduleId: String): Boolean = queryPid(moduleId) != null

    fun getServicePid(moduleId: String): Int? {
        // 优先 pid 文件；文件与 pgrep 交叉校验
        val moduleDir = ModuleManager.getModuleDir(moduleId)
        val filePid = readPidFile(moduleDir)
        val livePid = queryPid(moduleId)
        if (livePid != null) return livePid
        // pgrep 没命中：清理可能过期的 pid 文件
        if (filePid != null) clearPid(moduleDir, moduleId)
        return null
    }

    // ------------------------------------------------------------------ internal

    /** pgrep -f 进程名；bracket 包裹首字符避免匹配到包装 sh -c 自身。 */
    private fun pgrepPattern(moduleId: String): String {
        val first = moduleId.first()
        return "[$first]${moduleId.drop(1)}_service"
    }

    private fun queryPid(moduleId: String): Int? {
        val out = sh("pgrep -f '${pgrepPattern(moduleId)}'")
        return out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()
    }

    private fun pidFile(moduleDir: File) = File(moduleDir, PID_FILE)

    private fun writePidFile(moduleDir: File, pid: Int) {
        runCatching { pidFile(moduleDir).writeText(pid.toString(), Charsets.UTF_8) }
    }

    private fun readPidFile(moduleDir: File): Int? =
        runCatching { pidFile(moduleDir).readText().trim().toIntOrNull() }.getOrNull()

    private fun clearPid(moduleDir: File, moduleId: String) {
        runCatching { pidFile(moduleDir).delete() }
        runCatching {
            ModuleDatabase.getInstance(ModuleManager.appContext()).moduleDao()
                .updateServicePid(moduleId, null)
        }
    }
}
