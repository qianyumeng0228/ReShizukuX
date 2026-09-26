package io.reshizukux.modules.core

import java.io.File

/**
 * 解析后的 module.prop 元数据（设计方案 §3.1）。
 *
 * 解析/校验方法放在 companion object，调用方式：ModuleSpec.parseModuleProp(file) / ModuleSpec.validateId(id)。
 */
data class ModuleSpec(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val minSdk: Int = 0,
    val requiresRoot: Boolean = false,
    val usesWebUI: Boolean = false,
    val usesShellBridge: Boolean = false
) {
    companion object {

        /** 模块 id 白名单：字母开头，后跟字母/数字/点/下划线/短横线，总长 2..64。 */
        private val ID_REGEX = Regex("[a-zA-Z][a-zA-Z0-9._-]{1,63}")

        /** 必需字段（设计方案 §3.1）。 */
        private val REQUIRED_FIELDS = setOf("id", "name", "version", "versionCode", "author", "description")

        fun validateId(id: String): Boolean = ID_REGEX.matches(id)

        /**
         * 按 key=value 解析 module.prop（split("=", 2)）。
         * 必需字段缺失 / id 不合法 / versionCode 非整数时返回 null。
         */
        fun parseModuleProp(file: File): ModuleSpec? {
            if (!file.exists() || !file.isFile) return null
            val props = HashMap<String, String>()
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEachLine
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty()) props[key] = value
            }
            return fromMap(props)
        }

        fun fromMap(map: Map<String, String>): ModuleSpec? {
            for (field in REQUIRED_FIELDS) {
                if (map[field].isNullOrBlank()) return null
            }
            val id = map.getValue("id").trim()
            if (!validateId(id)) return null
            val versionCode = map["versionCode"]?.trim()?.toIntOrNull() ?: return null
            return ModuleSpec(
                id = id,
                name = map.getValue("name").trim(),
                version = map.getValue("version").trim(),
                versionCode = versionCode,
                author = map.getValue("author").trim(),
                description = map.getValue("description").trim(),
                minSdk = map["minSdk"]?.trim()?.toIntOrNull() ?: 0,
                requiresRoot = map["requiresRoot"].toBool(),
                usesWebUI = map["usesWebUI"].toBool(),
                usesShellBridge = map["usesShellBridge"].toBool()
            )
        }

        private fun String?.toBool(): Boolean =
            this?.trim()?.equals("true", ignoreCase = true) == true
    }
}
