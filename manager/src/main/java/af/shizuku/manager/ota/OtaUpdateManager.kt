package af.shizuku.manager.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import af.shizuku.manager.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * OTA 自动更新管理器（ReShizukuX beta1 组A）。
 *
 * 负责：
 *  - 拉取远端 version.json（GET，HttpURLConnection，不引入 OkHttp）
 *  - 对比 versionCode 判断是否有更新
 *  - 下载 APK 到 cacheDir，回调进度
 *  - 安装：优先 PackageInstaller.Session（无需 FileProvider/Manifest 改动），
 *    失败时回退 ACTION_VIEW + FileProvider URI（集成阶段可在 Manifest 注册 provider）。
 *
 * 所有网络/IO 均在 Dispatchers.IO 调用方执行；本类只暴露 suspend 函数。
 */
object OtaUpdateManager {

    const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/qianyumeng0228/ReShizukuX/main/version.json"

    data class RemoteVersion(
        val latestVersion: String,
        val versionCode: Int,
        val downloadUrl: String,
        val changelog: String
    )

    sealed class CheckResult {
        object UpToDate : CheckResult()
        data class Available(val remote: RemoteVersion) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    /** 静默检查（Application 启动时调用）。仅日志，不弹 UI。 */
    fun checkSilently(context: Context) {
        Thread {
            runCatching {
                val remote = fetchRemoteVersion()
                if (remote != null && remote.versionCode > BuildConfig.VERSION_CODE) {
                    android.util.Log.i(
                        "OtaUpdate",
                        "new version available: ${remote.latestVersion} (${remote.versionCode})"
                    )
                }
            }
        }.start()
    }

    suspend fun checkUpdate(): CheckResult = withContextIO {
        runCatching {
            val remote = fetchRemoteVersion() ?: return@withContextIO CheckResult.Failed("无法解析版本信息")
            if (remote.versionCode > BuildConfig.VERSION_CODE) {
                CheckResult.Available(remote)
            } else {
                CheckResult.UpToDate
            }
        }.getOrElse { CheckResult.Failed(it.message ?: "网络错误") }
    }

    private fun fetchRemoteVersion(): RemoteVersion? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(VERSION_JSON_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            RemoteVersion(
                latestVersion = json.optString("latestVersion", json.optString("version", "unknown")),
                versionCode = json.optInt("versionCode", 0),
                downloadUrl = json.optString("downloadUrl", ""),
                changelog = json.optString("changelog", "")
            )
        } catch (e: Exception) {
            android.util.Log.w("OtaUpdate", "fetch version.json failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载 APK 到 cacheDir/update-latest.apk。
     * onProgress 回调 (0f..1f)。返回下载好的 File，失败抛异常。
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): File = withContextIO {
        if (downloadUrl.isBlank()) throw IllegalStateException("下载地址为空")
        val outFile = File(context.cacheDir, "update-latest.apk")
        if (outFile.exists()) outFile.delete()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("服务器返回 ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                    output.flush()
                }
            }
            onProgress(1f)
            outFile
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 触发安装。优先 PackageInstaller.Session（应用内静默提交，系统弹确认框）；
     * 失败回退到 ACTION_VIEW intent。
     */
    fun installApk(context: Context, apk: File) {
        try {
            installViaSession(context, apk)
        } catch (e: Exception) {
            android.util.Log.w("OtaUpdate", "session install failed, fallback ACTION_VIEW: ${e.message}")
            installViaIntent(context, apk)
        }
    }

    private fun installViaSession(context: Context, apk: File) {
        val pm = context.packageManager
        val installer = pm.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId,
                Intent("${context.packageName}.ota.INSTALL_DONE").setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)
            )
            session.commit(pi.intentSender)
        }
    }

    private fun installViaIntent(context: Context, apk: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
        } catch (e: Exception) {
            // FileProvider 未注册（集成阶段前），退化为 file://（Android N+ 会失败，仅作兜底日志）
            android.util.Log.w("OtaUpdate", "FileProvider not registered, using Uri.fromFile")
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

private suspend inline fun <T> withContextIO(crossinline block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
