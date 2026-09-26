package io.reshizukux.modules.core

/**
 * 模块生命周期状态机（设计方案 §3.2）。
 *
 * | 状态 | 磁盘标记 | 含义 |
 * |------|---------|------|
 * | NOT_INSTALLED | 目录不存在 | 未安装 |
 * | DISABLED | `disable` 文件存在 | 已安装但停用，service.sh 不运行 |
 * | ENABLED | 无 disable 文件 | 已启用，service.sh 可运行 |
 * | UPDATING | `update` 文件存在 | 正在更新（瞬时） |
 * | ERROR | `error.log` 存在 | 上次执行失败 |
 * | CORRUPTED | 哈希树校验失败 | 文件被篡改，拒绝执行 |
 */
enum class ModuleState {
    NOT_INSTALLED,
    DISABLED,
    ENABLED,
    UPDATING,
    ERROR,
    CORRUPTED;

    companion object {
        fun fromName(name: String?): ModuleState =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NOT_INSTALLED
    }
}

/**
 * 对外暴露的模块元数据 + 运行时状态（不直接作为 Room 实体，见 db/ModuleDatabase.kt）。
 */
data class ModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val minSdk: Int,
    val requiresRoot: Boolean,
    val usesWebUI: Boolean,
    val usesShellBridge: Boolean,
    val state: ModuleState,
    val permissionLevel: String,
    val installTime: Long,
    val lastUpdateTime: Long,
    val servicePid: Int?,
    val sha256: String,
    val publicKey: String?
)
