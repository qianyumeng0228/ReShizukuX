package io.reshizukux.modules.webui

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.execution.CommandFilter
import io.reshizukux.modules.execution.ModuleExecutor
import io.reshizukux.modules.permission.PermissionController
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * WebView JS Bridge（设计方案 §3.6，参考 Shevery ModuleJsBridge.kt）。
 *
 * 暴露给模块页面的全局对象名为 `Shizuku`（addJavascriptInterface 时指定）。
 *
 * 安全约束：
 *  - 每个 @JavascriptInterface 方法首先做 origin 校验：当前 WebView URL 必须是 file://
 *    且 canonical 路径落在模块 webRoot 目录内（防其他 file:// 页面盗用桥对象）。
 *  - 模块必须 ENABLED 且 [PermissionController.canWebBridge] 通过（挂桥前由 Activity 再判一次）。
 *  - exec / execWithOptions 执行前过 [CommandFilter]，命中 HIGH 风险 → 直接拒绝返回错误
 *    （不在原生层弹确认对话框；设计方案 §3.6.2 由 JS 层处理或直接拒绝）。
 *  - download() 由 app 进程走 HttpsURLConnection（不是 WebView 联网），受
 *    [PermissionController.canDownload] 独立控制；HTTPS only，≤20MB，≤5 次重定向。
 *
 * 注意：@JavascriptInterface 方法运行在 JS 桥后台线程，不能直接读 webView.url（UI 线程），
 * 需 post 到 UI 线程再 CountDownLatch 同步等待（与 Shevery 一致）。
 */
class ModuleJsBridge(
    private val moduleId: String,
    private val moduleDir: File,
    private val webRoot: File,
    private val webView: WebView
) {

    private val tag = "ModuleJsBridge"

    // ------------------------------------------------------------------ origin

    /**
     * 校验当前 WebView 加载的页面是否是 webRoot 内的 file:// 页面。
     * 必须在 JS 桥后台线程调用；通过 post 切到 UI 线程读 webView.url。
     */
    private fun isOriginValid(): Boolean {
        val latch = CountDownLatch(1)
        var url: String? = null
        webView.post {
            url = webView.url
            latch.countDown()
        }
        return try {
            if (!latch.await(500, TimeUnit.MILLISECONDS)) return false
            val current = url ?: return false
            val uri = Uri.parse(current)
            if (uri.scheme?.lowercase() != "file") return false
            val path = uri.path ?: return false
            val rootPath = webRoot.canonicalPath
            val filePath = File(path).canonicalPath
            filePath == rootPath || filePath.startsWith(rootPath + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    /** 模块是否处于 ENABLED 状态。 */
    private fun isModuleEnabled(): Boolean =
        ModuleManager.getModule(moduleId)?.state == ModuleState.ENABLED

    /** 桥是否允许使用（enabled + canWebBridge）。每次方法调用前都复查。 */
    private fun bridgeAllowed(): Boolean = isModuleEnabled() && PermissionController.canWebBridge(moduleId)

    // ------------------------------------------------------------------ JS API

    /**
     * 同步执行 shell 命令，返回 JSON 字符串。
     *
     * 返回字段：{ok, exitCode, stdout, stderr, timedOut}
     * ok=true 仅当进程正常结束且 exitCode==0。
     */
    @JavascriptInterface
    fun exec(command: String): String {
        if (!isOriginValid()) return shellError("Permission denied: origin mismatch.")
        if (!bridgeAllowed()) return shellError("Permission denied: web bridge not granted.")
        return execInternal(command, defaultTimeoutSec = DEFAULT_EXEC_TIMEOUT_SEC)
    }

    /**
     * 带选项执行 shell 命令。optionsJson 形如：
     * ```json
     * {"timeoutSec": 10, "stdin": "...", "cwd": "sub/dir", "env": {"FOO": "bar"}}
     * ```
     * - timeoutSec：1..120，默认 30
     * - stdin：写入远端进程 stdin（≤64KB）
     * - cwd：相对模块目录的子目录（禁止 ../）
     * - env：额外环境变量（≤32 个，key 符合 [A-Za-z_][A-Za-z0-9_]*）
     */
    @JavascriptInterface
    fun execWithOptions(command: String, optionsJson: String): String {
        if (!isOriginValid()) return shellError("Permission denied: origin mismatch.")
        if (!bridgeAllowed()) return shellError("Permission denied: web bridge not granted.")
        return try {
            val opts = parseOptions(optionsJson)
            execInternal(command, defaultTimeoutSec = opts.timeoutSec, options = opts)
        } catch (e: Exception) {
            shellError(e.message ?: "Invalid exec options.")
        }
    }

    /**
     * 通过 app 的 HttpsURLConnection 下载文件到 webRoot 子目录。
     *
     * @param url HTTPS URL（http:// 一律拒绝）
     * @param relativePath 相对 webRoot 的目标路径（禁止 ../、禁止覆盖 index.html）
     * @return JSON {ok, path, bytes?, error?}
     */
    @JavascriptInterface
    fun download(url: String, relativePath: String): String {
        val result = JSONObject()
        if (!isOriginValid()) {
            return result.apply {
                put("ok", false)
                put("error", "Permission denied: origin mismatch.")
            }.toString()
        }
        if (!isModuleEnabled()) {
            return result.apply { put("ok", false); put("error", "Module is disabled.") }.toString()
        }
        if (!PermissionController.canDownload(moduleId)) {
            return result.apply { put("ok", false); put("error", "Download permission not granted.") }.toString()
        }
        return try {
            val outFile = resolveWebFile(relativePath)
            val bytes = downloadHttps(url, outFile)
            result.apply {
                put("ok", true)
                put("path", relativePath)
                put("bytes", bytes)
            }.toString()
        } catch (e: Exception) {
            Timber.tag(tag).w(e, "download failed: $url")
            result.apply {
                put("ok", false)
                put("path", relativePath)
                put("error", e.message ?: "Download failed.")
            }.toString()
        }
    }

    /** 返回模块信息 + 当前权限快照（JSON）。 */
    @JavascriptInterface
    fun getModuleInfo(): String {
        if (!isOriginValid()) return "{}"
        val info = ModuleManager.getModule(moduleId) ?: return "{}"
        return JSONObject().apply {
            put("ok", true)
            put("id", info.id)
            put("name", info.name)
            put("version", info.version)
            put("versionCode", info.versionCode)
            put("author", info.author)
            put("description", info.description)
            put("state", info.state.name)
            put("moduleDir", moduleDir.absolutePath)
            put("webRoot", webRoot.absolutePath)
            put("permissions", JSONObject().apply {
                put("action", PermissionController.canAction(moduleId))
                put("service", PermissionController.canService(moduleId))
                put("webBridge", PermissionController.canWebBridge(moduleId))
                put("webNetwork", PermissionController.canWebNetwork(moduleId))
                put("download", PermissionController.canDownload(moduleId))
                put("level", PermissionController.getPermissionLevel(moduleId).name)
                put("customFlags", PermissionController.getCustomFlags(moduleId))
            })
        }.toString()
    }

    // ------------------------------------------------------------------ exec impl

    private fun execInternal(
        command: String,
        defaultTimeoutSec: Int,
        options: ExecOptions? = null
    ): String {
        if (command.isBlank()) return shellError("Command is blank.")

        // HIGH 风险命令直接拒绝（不在原生层弹确认；JS 层应在调用前自行提示）
        val filter = CommandFilter.filter(command)
        if (filter.hasHighRisk) {
            return shellError(
                "Command rejected by CommandFilter (HIGH risk): " +
                    filter.matchedLines.joinToString(" | ")
            )
        }

        val env = buildMap {
            put("MODULE_DIR", moduleDir.absolutePath)
            put("MODULE_ID", moduleId)
            put("WEBUI", "1")
            if (options != null) putAll(options.env)
        }
        val cwd = options?.cwdResolved
        val timeout = (options?.timeoutSec ?: defaultTimeoutSec).coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

        val result = ModuleExecutor.execute(
            script = command,
            env = env,
            timeoutSec = timeout,
            stdin = options?.stdin,
            cwd = cwd
        )
        return JSONObject().apply {
            put("ok", !result.timedOut && result.exitCode == 0)
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timedOut", result.timedOut)
        }.toString()
    }

    private fun parseOptions(raw: String): ExecOptions {
        if (raw.isBlank()) return ExecOptions()
        val json = JSONObject(raw)
        val timeout = json.optInt("timeoutSec", DEFAULT_EXEC_TIMEOUT_SEC)
            .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        val stdin = json.optString("stdin", "").takeIf { it.isNotEmpty() }
        if (stdin != null && stdin.length > MAX_STDIN_BYTES) {
            throw IllegalArgumentException("stdin too large (max ${MAX_STDIN_BYTES} bytes).")
        }
        val cwdRelative = json.optString("cwd", "").trim().trim('/')
        val cwdResolved = if (cwdRelative.isEmpty()) {
            null
        } else {
            // 禁止 ../ 逃逸
            val parts = cwdRelative.split('/')
            require(parts.none { it.isBlank() || it == "." || it == ".." }) {
                "Unsafe cwd: $cwdRelative"
            }
            val resolved = File(moduleDir, cwdRelative)
            require(resolved.canonicalPath.startsWith(moduleDir.canonicalPath + File.separator) ||
                resolved.canonicalPath == moduleDir.canonicalPath) {
                "cwd escapes module directory."
            }
            require(resolved.isDirectory) { "cwd is not a directory: $cwdRelative" }
            resolved.absolutePath
        }
        val extraEnv = linkedMapOf<String, String>()
        json.optJSONObject("env")?.let { envJson ->
            val keys = envJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(ENV_KEY_REGEX.matches(key)) { "Invalid env key: $key" }
                require(extraEnv.size < MAX_EXTRA_ENV_COUNT) { "Too many env vars." }
                val value = envJson.optString(key, "")
                require(value.length <= MAX_ENV_VALUE_BYTES) { "Env value too long: $key" }
                extraEnv[key] = value
            }
        }
        return ExecOptions(
            timeoutSec = timeout,
            stdin = stdin,
            cwdResolved = cwdResolved,
            env = extraEnv
        )
    }

    private data class ExecOptions(
        val timeoutSec: Int = DEFAULT_EXEC_TIMEOUT_SEC,
        val stdin: String? = null,
        val cwdResolved: String? = null,
        val env: Map<String, String> = emptyMap()
    )

    // ------------------------------------------------------------------ download impl

    private fun resolveWebFile(relativePath: String): File {
        val clean = relativePath.trim().replace('\\', '/').trim('/')
        require(clean.isNotEmpty()) { "relativePath is blank." }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe path: $relativePath" }
        // 禁止覆盖 WebUI 入口文件
        require(!clean.equals("index.html", ignoreCase = true) &&
            !clean.endsWith("/index.html", ignoreCase = true)) {
            "download() cannot overwrite index.html."
        }
        val target = File(webRoot, clean)
        val rootPath = webRoot.canonicalPath
        val targetPath = target.canonicalPath
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes webRoot."
        }
        return target
    }

    private fun downloadHttps(rawUrl: String, outFile: File): Long {
        var current = parseHttpsUrl(rawUrl)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ReShizukuX-ModuleWebUI/1.0")
            }
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    redirects++
                    require(redirects <= MAX_REDIRECTS) { "Too many redirects." }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location.")
                    current = parseHttpsUrl(URL(current, location).toString())
                    continue
                }
                require(code in 200..299) { "HTTP $code for $current" }
                outFile.parentFile?.mkdirs()
                val tmp = File.createTempFile("dl-", ".tmp", outFile.parentFile)
                var total = 0L
                try {
                    BufferedInputStream(connection.inputStream).use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                total += read
                                require(total <= MAX_DOWNLOAD_BYTES) {
                                    "File too large (max ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB)."
                                }
                                output.write(buf, 0, read)
                            }
                        }
                    }
                    if (outFile.exists()) outFile.delete()
                    check(tmp.renameTo(outFile)) { "Failed to save file." }
                    return total
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseHttpsUrl(raw: String): URL {
        val url = URL(raw.trim())
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS URLs are allowed." }
        require(!url.host.isNullOrBlank()) { "URL host is blank." }
        return url
    }

    private fun shellError(message: String): String = JSONObject().apply {
        put("ok", false)
        put("exitCode", EXIT_ERROR)
        put("stdout", "")
        put("stderr", message)
        put("timedOut", false)
    }.toString()

    companion object {
        /** JS 桥对象名（与 Shevery 保持一致，模块页面用 Shizuku.exec(...)）。 */
        const val JS_INTERFACE_NAME = "Shizuku"

        private const val EXIT_ERROR = -1
        private const val DEFAULT_EXEC_TIMEOUT_SEC = 30
        private const val MIN_TIMEOUT_SEC = 1
        private const val MAX_TIMEOUT_SEC = 120
        private const val MAX_STDIN_BYTES = 64 * 1024
        private const val MAX_EXTRA_ENV_COUNT = 32
        private const val MAX_ENV_VALUE_BYTES = 4096
        private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308
        )
    }
}
