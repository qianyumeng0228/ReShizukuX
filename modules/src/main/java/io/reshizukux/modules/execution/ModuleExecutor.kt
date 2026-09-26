package io.reshizukux.modules.execution

import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 脚本执行结果。
 *
 * @property exitCode 退出码；超时 destroy 后为 124；启动失败为 -1
 * @property stdout 标准输出（合并读取后）
 * @property stderr 标准错误
 * @property timedOut 是否因超时被强杀
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)

/**
 * Shizuku newProcess 封装（设计方案 §3.3.1，参考 Portable WifiDebugReassert.kt:92-103）。
 *
 * P1 基础版：同步执行 + 并发读 stdout/stderr + 超时 destroy。
 * 工作目录固定 /data/local/tmp（与 Shevery AdbModuleManager.kt:198-218 一致）。
 */
object ModuleExecutor {

    private const val TAG = "ModuleExecutor"
    private const val WORKING_DIR = "/data/local/tmp"

    /**
     * 通过 Shizuku 特权执行 `sh -c <script>`。
     *
     * @param env 额外环境变量（MODULE_DIR/MODULE_ID 等），空则继承远端默认环境
     * @param timeoutSec 超时秒数，超时后 destroy() 强杀（exitCode=124）
     * @param onOutput 可选实时输出回调；stdout/stderr 每读到一段字节就以字符串回调一次
     *   （用于 action.sh 的流式显示，P6 UI 对话框）。为 null 时保持 P1 缓冲行为。
     * @param stdin 可选写入远端进程 stdin 的内容；写完后关闭输出流（P4 WebUI execWithOptions 用）
     * @param cwd 可选远端工作目录覆盖；null 时默认 /data/local/tmp（P4 WebUI execWithOptions 用）
     */
    fun execute(
        script: String,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Int = 60,
        onOutput: ((String) -> Unit)? = null,
        stdin: String? = null,
        cwd: String? = null
    ): ExecResult {
        val envArray = if (env.isEmpty()) null else env.map { "${it.key}=${it.value}" }.toTypedArray()
        val workingDir = cwd?.takeIf { it.isNotBlank() } ?: WORKING_DIR
        val process = try {
            Shizuku.newProcess(arrayOf("sh", "-c", script), envArray, workingDir)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "newProcess failed")
            return ExecResult(-1, "", "newProcess failed: ${e.message}", false)
        }

        // 写入 stdin（P4 WebUI bridge）：写完立即关闭，让远端 shell 收到 EOF。
        if (!stdin.isNullOrEmpty()) {
            try {
                process.outputStream.use { os ->
                    os.write(stdin.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "write stdin failed")
            }
        }

        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        val stdoutThread = pump(process.inputStream, stdoutBuffer, "stdout", onOutput)
        val stderrThread = pump(process.errorStream, stderrBuffer, "stderr", onOutput)

        var timedOut = false
        val exitCode = try {
            val finished = process.waitForTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (finished) {
                process.waitFor()
            } else {
                timedOut = true
                process.destroy()
                124
            }
        } catch (e: InterruptedException) {
            process.destroy()
            130
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "waitFor failed")
            try { process.destroy() } catch (_: Exception) {}
            -1
        }

        stdoutThread.join(1000)
        stderrThread.join(1000)

        return ExecResult(
            exitCode = exitCode,
            stdout = stdoutBuffer.toString(Charsets.UTF_8.name()),
            stderr = stderrBuffer.toString(Charsets.UTF_8.name()),
            timedOut = timedOut
        )
    }

    /** 后台线程并发读流，避免 stdout/stderr 管道缓冲区填满导致远端进程阻塞。 */
    private fun pump(
        input: InputStream,
        sink: ByteArrayOutputStream,
        tag: String,
        onOutput: ((String) -> Unit)? = null
    ): Thread {
        val t = Thread({
            try {
                input.use { src ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (src.read(buffer).also { read = it } != -1) {
                        synchronized(sink) { sink.write(buffer, 0, read) }
                        if (onOutput != null) {
                            val chunk = String(buffer, 0, read, Charsets.UTF_8)
                            try {
                                onOutput.invoke(chunk)
                            } catch (e: Exception) {
                                Timber.tag(TAG).d(e, "onOutput callback threw")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "pump $tag ended")
            }
        }, "mod-exec-$tag")
        t.isDaemon = true
        t.start()
        return t
    }
}
