package io.reshizukux.modules.permission

/**
 * 三档权限（设计方案 §3.4.1，参考 Shevery ModuleSettings.kt:37-63，去掉 trusted 旁路）。
 *
 * | 档位 | action | service | WebUI Bridge | WebUI 网络 | 下载 |
 * | SAFE（默认） | ❌ | ❌ | ❌ | ❌ | ❌ |
 * | CUSTOM | 逐开关 | 逐开关 | 逐开关 | 逐开关 | 逐开关 |
 * | FULL | ✅ | ✅ | ✅ | ❌（仍禁） | ✅ |
 */
enum class PermissionLevel {
    SAFE,
    CUSTOM,
    FULL;

    companion object {
        fun fromName(name: String?): PermissionLevel =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SAFE
    }
}

/**
 * CUSTOM 档逐开关位掩码常量（对应 InstalledModule.customPermissions）。
 */
object PermissionFlag {
    const val ACTION = 1
    const val SERVICE = 2
    const val WEB_BRIDGE = 4
    const val WEB_NETWORK = 8
    const val DOWNLOAD = 16
}
