package af.shizuku.manager.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 助手 API 客户端（ReShizukuX beta1 组A）。
 *
 * POST {endpoint}  body: {"command": "...", "context": "...", "model": "..."}
 * Header: Authorization: Bearer {apiKey}
 * 期望返回: {"explanation": "..."}
 *
 * 不引入 OkHttp，统一 HttpURLConnection + Dispatchers.IO。
 */
object AiClient {

    data class AiRequest(
        val command: String,
        val context: String = ""
    )

    suspend fun explain(context: Context, request: AiRequest): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = AiSettings.getApiKey(context)
                if (apiKey.isBlank()) throw IllegalStateException("未配置 API 密钥")
                val endpoint = AiSettings.getEndpoint(context)
                val model = AiSettings.getModel(context)

                val body = JSONObject()
                    .put("command", request.command)
                    .put("context", request.context)
                    .put("model", model)
                    .toString()

                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code: $respText")
                    }
                    val json = JSONObject(respText)
                    json.optString("explanation", json.optString("text", respText))
                        .ifBlank { respText }
                } finally {
                    conn?.disconnect()
                }
            }
        }
}
