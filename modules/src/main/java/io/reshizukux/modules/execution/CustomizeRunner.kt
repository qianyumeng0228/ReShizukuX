package io.reshizukux.modules.execution

import android.os.Build
import io.reshizukux.modules.core.ModuleSpec
import timber.log.Timber
import java.io.File

/**
 * customize.sh 安装脚本执行器（设计方案 §3.3.4）。
 *
 * 调用时机：ModuleManager 原子 rename 成功后、写数据库前。
 *  - 超时 120s（安装可能涉及下载/解压）
 *  - 工作目录 = 模块目录（`cd '<dir>' && sh customize.sh`）
 *  - 注入 MODULE_DIR / MODULE_ID / ANDROID_SDK / MODULE_VERSION
 *  - CommandFilter：安装是自动化流程，命中 HIGH 风险直接拒绝执行（exit=2），不弹确认
 *  - 失败（exitCode != 0 或超时）→ ModuleManager 回滚删除模块目录、不写库
 *  - 日志写入 <moduleDir>/logs/customize-last.log
 */
object CustomizeRunner {

    private const val TAG = "CustomizeRunner"
    private const val TIMEOUT_SEC = 120

    fun run(moduleDir: File, spec: ModuleSpec): ExecResult {
        val scriptFile = File(moduleDir, "customize.sh")
        if (!scriptFile.exists()) {
            // 无 customize.sh：正常跳过
            return ExecResult(0, "", "", false)
        }

        // 静态扫描：HIGH 风险命令在安装阶段直接拒绝
        val content = runCatching { scriptFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val filter = CommandFilter.filter(content)
        if (filter.hasHighRisk) {
            val msg = "customize.sh blocked by CommandFilter (HIGH risk): " +
                filter.matchedLines.joinToString(" | ")
            Timber.tag(TAG).w(msg)
            ModuleLogs.write(moduleDir, "customize-last.log", msg + "\n")
            return ExecResult(2, "", msg, false)
        }

        val env = mapOf(
            "MODULE_DIR" to moduleDir.absolutePath,
            "MODULE_ID" to spec.id,
            "ANDROID_SDK" to Build.VERSION.SDK_INT.toString(),
            "MODULE_VERSION" to spec.version
        )
        val script = "cd '${moduleDir.absolutePath}' && sh customize.sh"
        val result = ModuleExecutor.execute(script, env, TIMEOUT_SEC)
        ModuleLogs.write(moduleDir, "customize-last.log", ModuleLogs.formatResult(result))
        Timber.tag(TAG).i("customize.sh exit=${result.exitCode} timedOut=${result.timedOut}")
        return result
    }
}
