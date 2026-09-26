package io.reshizukux.modules.execution

import android.os.Build
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleSpec
import io.reshizukux.modules.permission.PermissionController
import timber.log.Timber
import java.io.File

/**
 * action.sh 手动动作执行器（设计方案 §3.3.2）。
 *
 * 流程：
 *  1. PermissionController.canAction(moduleId) 权限检查（SAFE 默认拒绝）
 *  2. 读 <moduleDir>/action.sh（无则返回错误）
 *  3. CommandFilter.filter：命中 HIGH 风险 → 调 onHighRisk 回调让上层决定
 *     （P1 无 UI，默认 true 放行；P6 UI 会弹确认对话框展示命中行）
 *  4. ModuleExecutor.execute（60s 超时），实时输出通过 onOutput 回调
 *  5. 结果写入 <moduleDir>/logs/action-last.log（仅尾部 64KB）
 */
object ActionRunner {

    private const val TAG = "ActionRunner"
    private const val TIMEOUT_SEC = 60

    /**
     * @param onOutput 实时输出回调（stdout/stderr 流式推送）
     * @param onHighRisk 命中 HIGH 风险时的确认回调；返回 true 继续执行，false 中止
     */
    fun runAction(
        moduleId: String,
        onOutput: (String) -> Unit = {},
        onHighRisk: (FilterResult) -> Boolean = { true }
    ): ExecResult {
        // 1. 权限
        if (!PermissionController.canAction(moduleId)) {
            val msg = "permission denied: action not granted for module $moduleId"
            Timber.tag(TAG).w(msg)
            return ExecResult(-3, "", msg, false)
        }

        // 1b. P7：CORRUPTED 模块拒绝执行（哈希树篡改，设计方案 §3.5.4）
        if (ModuleManager.isCorrupted(moduleId)) {
            val msg = "module $moduleId is CORRUPTED (hash tree mismatch); repair() or reinstall first"
            Timber.tag(TAG).w(msg)
            return ExecResult(-6, "", msg, false)
        }

        val moduleDir = ModuleManager.getModuleDir(moduleId)
        if (!moduleDir.exists() || !moduleDir.isDirectory) {
            val msg = "module not installed: $moduleId"
            return ExecResult(-4, "", msg, false)
        }

        // 2. action.sh 必须存在
        val scriptFile = File(moduleDir, "action.sh")
        if (!scriptFile.exists()) {
            val msg = "action.sh not found in module $moduleId"
            Timber.tag(TAG).w(msg)
            return ExecResult(-5, "", msg, false)
        }

        // 3. 命令过滤
        val content = runCatching { scriptFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val filter = CommandFilter.filter(content)
        if (filter.hasHighRisk && !onHighRisk.invoke(filter)) {
            val msg = "action aborted: user declined HIGH-risk commands: " +
                filter.matchedLines.joinToString(" | ")
            Timber.tag(TAG).w(msg)
            ModuleLogs.writeActionLog(moduleDir, msg + "\n")
            return ExecResult(3, "", msg, false)
        }

        // 4. 环境变量（version 从 module.prop 现场补全）
        val spec = ModuleSpec.parseModuleProp(File(moduleDir, "module.prop"))
        val env = mapOf(
            "MODULE_DIR" to moduleDir.absolutePath,
            "MODULE_ID" to moduleId,
            "ANDROID_SDK" to Build.VERSION.SDK_INT.toString(),
            "MODULE_VERSION" to (spec?.version ?: "")
        )
        val script = "cd '${moduleDir.absolutePath}' && sh action.sh"

        // 5. 执行（流式输出）
        val result = ModuleExecutor.execute(script, env, TIMEOUT_SEC, onOutput = onOutput)

        // 6. 落日志（尾部 64KB）
        val combined = buildString {
            append(result.stdout)
            if (result.stderr.isNotEmpty()) {
                if (isNotEmpty() && last() != '\n') append('\n')
                append(result.stderr)
            }
        }
        ModuleLogs.writeActionLog(moduleDir, combined)
        Timber.tag(TAG).i("action.sh exit=${result.exitCode} timedOut=${result.timedOut}")
        return result
    }
}
