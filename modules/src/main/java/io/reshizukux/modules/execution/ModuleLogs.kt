package io.reshizukux.modules.execution

import timber.log.Timber
import java.io.File

/**
 * 模块脚本日志读写助手（设计方案 §3.3 / §3.5.2）。
 *
 * 日志目录：`<moduleDir>/logs/`
 *  - customize-last.log   安装脚本输出
 *  - uninstall-last.log   卸载钩子输出（卸载删除目录前会被复制到 cache 保留）
 *  - action-last.log      手动动作输出（仅保留尾部 64KB）
 */
internal object ModuleLogs {

    private const val TAG = "ModuleLogs"

    /** action-last.log 单文件上限（尾部 64KB，参考 Shevery AdbModuleManager.kt:214-218）。 */
    private const val ACTION_LOG_TAIL_BYTES = 64L * 1024

    fun logsDir(moduleDir: File): File = File(moduleDir, "logs").apply { mkdirs() }

    /** 写日志（全量覆盖）。写失败仅记录，不抛异常。 */
    fun write(moduleDir: File, name: String, content: String) {
        runCatching {
            File(logsDir(moduleDir), name).writeText(content, Charsets.UTF_8)
        }.onFailure { Timber.tag(TAG).w(it, "write $name failed") }
    }

    /**
     * 写 action 日志：若总输出超过 [ACTION_LOG_TAIL_BYTES]，只保留尾部 64KB。
     */
    fun writeActionLog(moduleDir: File, content: String) {
        runCatching {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val kept = if (bytes.size > ACTION_LOG_TAIL_BYTES) {
                String(bytes, bytes.size - ACTION_LOG_TAIL_BYTES.toInt(), ACTION_LOG_TAIL_BYTES.toInt(), Charsets.UTF_8)
            } else {
                content
            }
            File(logsDir(moduleDir), "action-last.log").writeText(kept, Charsets.UTF_8)
        }.onFailure { Timber.tag(TAG).w(it, "write action-last.log failed") }
    }

    /** 读日志；文件不存在或读取失败返回 null。 */
    fun read(moduleDir: File, name: String): String? {
        val f = File(logsDir(moduleDir), name)
        return runCatching { if (f.exists()) f.readText(Charsets.UTF_8) else null }
            .getOrNull()
    }

    /** 把执行结果格式化为日志文本（头部带 exitCode/timedOut）。 */
    fun formatResult(r: ExecResult): String = buildString {
        appendLine("# exitCode=${r.exitCode} timedOut=${r.timedOut}")
        if (r.stdout.isNotEmpty()) append(r.stdout)
        if (r.stderr.isNotEmpty()) {
            if (isNotEmpty() && last() != '\n') append('\n')
            append(r.stderr)
        }
    }
}
