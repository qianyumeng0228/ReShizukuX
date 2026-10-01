package af.shizuku.manager.xposed

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Persisted record for one app that has been patched by this manager.
 *
 * The plain package-name set in `lspatch_patched` ("packages") only remembers *what* was patched;
 * this detail record adds *when* and *which modules were baked in*, so the "已 patch 的应用" list can
 * show module info and offer per-entry actions. Stored as a JSON map under the
 * `lspatch_patched_detail` key of the same SharedPreferences file.
 */
@Serializable
data class PatchedAppInfo(
    val packageName: String,
    val patchedTimestamp: Long,
    val modulePackageNames: List<String> = emptyList(),
)

/**
 * Thin persistence wrapper around the `lspatch_patched` SharedPreferences file.
 *
 * The legacy string-set key "packages" stays the source of truth for the list itself; the detail
 * map is keyed by package name and may lag behind (older entries have no detail row), so callers
 * always tolerate a missing detail.
 */
object PatchedAppStore {

    private const val PREFS = "lspatch_patched"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_DETAIL = "lspatch_patched_detail"

    private val json = Json { ignoreUnknownKeys = true }

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadPackages(context: Context): List<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toList()?.sorted() ?: emptyList()

    fun savePackages(context: Context, packages: List<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, packages.toSet()).apply()
    }

    fun loadDetails(context: Context): Map<String, PatchedAppInfo> {
        val raw = prefs(context).getString(KEY_DETAIL, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PatchedAppInfo>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun saveDetail(context: Context, info: PatchedAppInfo) {
        val map = loadDetails(context).toMutableMap()
        map[info.packageName] = info
        prefs(context).edit()
            .putString(KEY_DETAIL, json.encodeToString(map))
            .apply()
    }

    fun remove(context: Context, packageName: String) {
        savePackages(context, loadPackages(context) - packageName)
        val map = loadDetails(context).toMutableMap()
        map.remove(packageName)
        prefs(context).edit()
            .putString(KEY_DETAIL, json.encodeToString(map))
            .apply()
    }
}
