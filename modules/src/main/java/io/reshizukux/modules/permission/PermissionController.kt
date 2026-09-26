package io.reshizukux.modules.permission

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块权限判定 + 存储（设计方案 §3.4.3）。
 *
 * SharedPreferences "module_prefs"：
 *  - key `permission_<moduleId>` = 档位名（SAFE/CUSTOM/FULL）
 *  - key `custom_perms_<moduleId>` = 逐开关位掩码
 *
 * 新模块默认 SAFE。WebUI 联网即使 FULL 也禁止（学 Shevery，防 WebView 窃取数据）。
 */
object PermissionController {

    private const val PREFS_NAME = "module_prefs"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: error("PermissionController not initialized; call init(context) first")

    fun getPermissionLevel(moduleId: String): PermissionLevel {
        val raw = requirePrefs().getString(levelKey(moduleId), PermissionLevel.SAFE.name)
        return PermissionLevel.fromName(raw)
    }

    fun setPermissionLevel(moduleId: String, level: PermissionLevel) {
        requirePrefs().edit().putString(levelKey(moduleId), level.name).apply()
    }

    fun getCustomFlags(moduleId: String): Int =
        requirePrefs().getInt(flagsKey(moduleId), 0)

    fun setCustomFlags(moduleId: String, flags: Int) {
        requirePrefs().edit().putInt(flagsKey(moduleId), flags).apply()
    }

    fun setCustomFlag(moduleId: String, flag: Int, enabled: Boolean) {
        val current = getCustomFlags(moduleId)
        val updated = if (enabled) current or flag else current and flag.inv()
        setCustomFlags(moduleId, updated)
    }

    fun canAction(moduleId: String): Boolean =
        hasCapability(moduleId, PermissionFlag.ACTION)

    fun canService(moduleId: String): Boolean =
        hasCapability(moduleId, PermissionFlag.SERVICE)

    fun canWebBridge(moduleId: String): Boolean =
        hasCapability(moduleId, PermissionFlag.WEB_BRIDGE)

    fun canDownload(moduleId: String): Boolean =
        hasCapability(moduleId, PermissionFlag.DOWNLOAD)

    /** WebUI 联网永远禁止（FULL 也禁，学 Shevery）。 */
    fun canWebNetwork(moduleId: String): Boolean = false

    private fun hasCapability(moduleId: String, flag: Int): Boolean {
        return when (getPermissionLevel(moduleId)) {
            PermissionLevel.FULL -> when (flag) {
                // Web 网络即使 FULL 也拒绝
                PermissionFlag.WEB_NETWORK -> false
                else -> true
            }
            PermissionLevel.CUSTOM -> (getCustomFlags(moduleId) and flag) != 0
            PermissionLevel.SAFE -> false
        }
    }

    private fun levelKey(moduleId: String) = "permission_$moduleId"
    private fun flagsKey(moduleId: String) = "custom_perms_$moduleId"
}
