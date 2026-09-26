package io.reshizukux.modules.repository

import android.content.Context
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.db.Repo

/**
 * 模块仓库管理（设计方案 §3.7，参考 MMRL IRepoManager.kt）。
 *
 * P1 骨架：仅提供仓库 CRUD 落库；modules.json 协议解析、下载、更新检测留 P5。
 */
object RepoManager {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun db() = ModuleDatabase.getInstance(
        appContext ?: error("RepoManager not initialized; call init(context) first")
    )

    fun addRepo(url: String, name: String) {
        db().repoDao().upsert(
            Repo(
                url = url,
                name = name,
                publicKey = null,
                enabled = true,
                lastRefresh = 0L
            )
        )
    }

    fun removeRepo(url: String) {
        db().repoDao().deleteByUrl(url)
        db().repoModuleDao().deleteByRepo(url)
    }

    fun getRepos(): List<Repo> = db().repoDao().getAll()

    /**
     * 刷新仓库 modules.json（P5 完整实现）。
     *
     * TODO(P5): GET <url>/modules.json → 校验签名 → 解析 RepoModule 列表 → upsert repo_modules 表。
     */
    fun refreshRepo(url: String) {
        // TODO(P5): implement modules.json fetch + parse + signature verify.
    }
}
