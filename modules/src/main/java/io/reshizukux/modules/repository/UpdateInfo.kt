package io.reshizukux.modules.repository

/**
 * 模块更新提示（设计方案 §3.7.2：对比本地 versionCode 与仓库 versionCode）。
 *
 * 仅当仓库中同名模块 versionCode 高于已安装版本时由
 * [RepoManager.checkUpdates] 产出。
 */
data class UpdateInfo(
    val moduleId: String,
    val installedVersionCode: Int,
    val installedVersionName: String,
    val availableVersionCode: Int,
    val availableVersionName: String,
    val repoName: String,
    val repoUrl: String,
    val downloadUrl: String
)
