package af.shizuku.manager.xposed

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manager-mode scope table: which modules the user has turned on for which *patched* app.
 *
 * This is the runtime source [org.lsposed.lspatch.manager.ManagerService] reads when a patched app
 * calls `getModules()` / `getLegacyModules()`: it resolves the caller from
 * [android.os.Binder.getCallingUid], looks the package up here, and serves exactly the modules the
 * user scoped to it. Integrated-mode patches bake modules into the apk and need no row here; the
 * table only feeds manager-mode apps.
 *
 * Stored as one JSON map under the `lspatch_scope` SharedPreferences file so the whole table can be
 * read and rewritten in two calls. A target with no row (or an empty set) is served no modules.
 */
@Serializable
private data class ScopeTable(
    /** target patched package -> module package names the user enabled for it. */
    val targets: Map<String, Set<String>> = emptyMap(),
)

object ScopeStore {

    private const val PREFS = "lspatch_scope"
    private const val KEY_TABLE = "table"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): ScopeTable {
        val raw = prefs(context).getString(KEY_TABLE, null) ?: return ScopeTable()
        return runCatching { json.decodeFromString<ScopeTable>(raw) }.getOrDefault(ScopeTable())
    }

    private fun save(context: Context, table: ScopeTable) {
        prefs(context).edit()
            .putString(KEY_TABLE, json.encodeToString(ScopeTable.serializer(), table))
            .apply()
    }

    /** Module package names the user scoped to [targetPackageName]; empty when none. */
    fun modulesFor(context: Context, targetPackageName: String): Set<String> =
        load(context).targets[targetPackageName].orEmpty()

    /** Every target package that has at least one module scoped to it. */
    fun targets(context: Context): Set<String> =
        load(context).targets.filterValues { it.isNotEmpty() }.keys.toSet()

    /** Enables or disables [modulePackageName] for [targetPackageName]; returns the new set. */
    fun setModuleEnabled(
        context: Context,
        targetPackageName: String,
        modulePackageName: String,
        enabled: Boolean,
    ): Set<String> {
        val table = load(context)
        val current = table.targets[targetPackageName].orEmpty().toMutableSet()
        if (enabled) current.add(modulePackageName) else current.remove(modulePackageName)
        val newTargets = table.targets.toMutableMap()
        if (current.isEmpty()) newTargets.remove(targetPackageName) else newTargets[targetPackageName] = current
        save(context, table.copy(targets = newTargets))
        return current
    }

    /** Drops every row for [targetPackageName] (e.g. when the patched app is uninstalled). */
    fun removeTarget(context: Context, targetPackageName: String) {
        val table = load(context)
        if (!table.targets.containsKey(targetPackageName)) return
        save(context, table.copy(targets = table.targets - targetPackageName))
    }
}
