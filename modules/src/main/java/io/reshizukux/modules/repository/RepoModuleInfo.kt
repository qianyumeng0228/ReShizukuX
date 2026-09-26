package io.reshizukux.modules.repository

import kotlinx.serialization.Serializable

/**
 * 仓库 modules.json 中单个模块条目（设计方案 §3.7.1）。
 *
 * 与 MMRL modules.json 协议对齐；可选字段允许仓库省略，由 P5/P7 流程兜底。
 * 所有可选字段在 [RepoManifest] 解析时缺失即回退到默认值（null）。
 */
@Serializable
data class RepoModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val signature: String? = null,
    val publicKey: String? = null,
    val minSdk: Int? = null,
    val requiresRoot: Boolean? = null,
    val usesWebUI: Boolean? = null,
    val changelog: String? = null,
    val lastUpdated: Long
)
