package io.reshizukux.modules.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 仓库 modules.json 根对象（设计方案 §3.7.1）。
 *
 * ```json
 * {
 *   "name": "ReShizukuX Official",
 *   "version": 1,
 *   "modules": [ ... ]
 * }
 * ```
 */
@Serializable
data class RepoManifest(
    val name: String,
    val version: Int,
    val modules: List<RepoModuleInfo> = emptyList()
) {
    companion object {
        /**
         * 宽松解析：忽略未知字段（向前兼容），不强制忽略缺失可选字段。
         */
        val Json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
