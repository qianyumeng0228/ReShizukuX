package io.reshizukux.modules.execution

import android.os.Build
import timber.log.Timber
import java.io.File

/**
 * uninstall.sh 卸载钩子执行器（设计方案 §3.3.5，对 Shevery 的关键改进）。
 *
 * 调用时机：ModuleManager.uninstall() 删除模块目录前。
 *  - 超时 60s
 *  - 工作目录 = 模块目录（`cd '<dir>' && sh uninstall.sh`）
 *  - 注入 MODULE_DIR / MODULE_ID / ANDROID_SDK
 *  - CommandFilter：卸载是自动化流程，命中 HIGH 风险直接跳过执行（exit=2），不弹确认
 *  - 失败不阻塞卸载（仅记录日志，ModuleManager 随后继续删除目录）
 *  - 日志写入 <moduleDir>/logs/uninstall-last.log，并在删除前复制到
 *    <moduleRoot>/.cache/uninstall-<id>.log 保留
 */
object UninstallRunner {

    private const val TAG = "UninstallRunner"
    private const val TIMEOUT_SEC = 60

    fun run(moduleDir: File, moduleId: String): ExecResult {
        val scriptFile = File(moduleDir, "uninstall.sh")
        if (!scriptFile.exists()) {
            return ExecResult(0, "", "", false)
        }

        val content = runCatching { scriptFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val filter = CommandFilter.filter(content)
        if (filter.hasHighRisk) {
            val msg = "uninstall.sh skipped by CommandFilter (HIGH risk): " +
                filter.matchedLines.joinToString(" | ")
            Timber.tag(TAG).w(msg)
            ModuleLogs.write(moduleDir, "uninstall-last.log", msg + "\n")
            preserveLogToCache(moduleDir, moduleId)
            // 不阻塞卸载
            return ExecResult(2, "", msg, false)
        }

        val env = mapOf(
            "MODULE_DIR" to moduleDir.absolutePath,
            "MODULE_ID" to moduleId,
            "ANDROID_SDK" to Build.VERSION.SDK_INT.toString()
        )
        val script = "cd '${moduleDir.absolutePath}' && sh uninstall.sh"
        val result = ModuleExecutor.execute(script, env, TIMEOUT_SEC)
        ModuleLogs.write(moduleDir, "uninstall-last.log", ModuleLogs.formatResult(result))
        preserveLogToCache(moduleDir, moduleId)
        Timber.tag(TAG).i("uninstall.sh exit=${result.exitCode} timedOut=${result.timedOut} (non-blocking)")
        return result
    }

    /** 删除模块目录前，把卸载日志复制到 <moduleRoot>/.cache/ 保留。 */
    private fun preserveLogToCache(moduleDir: File, moduleId: String) {
        runCatching {
            val logFile = File(moduleDir, "logs/uninstall-last.log")
            if (!logFile.exists()) return@runCatching
            val root = moduleDir.parentFile ?: return@runCatching
            val cacheDir = File(root, ".cache").apply { mkdirs() }
            logFile.copyTo(File(cacheDir, "uninstall-$moduleId.log"), overwrite = true)
        }.onFailure { Timber.tag(TAG).w(it, "preserve uninstall log failed") }
    }
}
