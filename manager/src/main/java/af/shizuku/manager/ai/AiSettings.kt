package af.shizuku.manager.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 助手本地设置（ReShizukuX beta1 组A）。
 *
 * SharedPreferences: "reshizukux_ai"
 *  - apiKey / endpoint / model
 *  - history: JSON 数组 [{role, text, time}]，最多 20 条
 */
object AiSettings {

    private const val PREFS_NAME = "reshizukux_ai"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 20

    const val DEFAULT_ENDPOINT = "https://api.example.com/v1/chat"
    const val DEFAULT_MODEL = "reshizuku-assist"

    data class HistoryItem(
        val role: String, // "user" or "ai"
        val text: String,
        val time: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String = prefs(context).getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API_KEY, value).apply()

    fun getEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

    fun setEndpoint(context: Context, value: String) =
        prefs(context).edit().putString(KEY_ENDPOINT, value).apply()

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(context: Context, value: String) =
        prefs(context).edit().putString(KEY_MODEL, value).apply()

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    fun getHistory(context: Context): List<HistoryItem> {
        val raw = prefs(context).getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(
                    role = o.optString("role", "user"),
                    text = o.optString("text", ""),
                    time = o.optLong("time", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun appendHistory(context: Context, item: HistoryItem) {
        val list = getHistory(context).toMutableList()
        list.add(item)
        while (list.size > MAX_HISTORY) list.removeAt(0)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("role", it.role).put("text", it.text).put("time", it.time))
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }
}
