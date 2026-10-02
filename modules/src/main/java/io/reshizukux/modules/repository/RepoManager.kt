package io.reshizukux.modules.repository

import android.content.Context
import io.reshizukux.modules.core.ModuleSecurity
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.db.Repo
import io.reshizukux.modules.db.RepoModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模块仓库管理（设计方案 §3.7，参考 MMRL IRepoManager.kt）。
 *
 * P5 实现：
 *  - modules.json 仓库协议（GET <repo>/modules.json → [RepoManifest] → repo_modules 表）
 *  - 模块下载（HTTP 流式下载 + SHA-256 校验 + Ed25519 占位校验）
 *  - 更新检测（对比 installed_modules 与已启用仓库缓存的 versionCode）
 *  - 默认仓库自动注入
 *
 * 网络/文件操作均为 suspend 函数并切到 [Dispatchers.IO]，不可在主线程直接调用。
 * HTTP 统一使用 [HttpURLConnection]（不引入 OkHttp 重依赖）。
 */
object RepoManager {

    /** 官方默认仓库（jsDelivr CDN，国内可直连 GitHub 文件）。
     *  使用不可变 tag URL（@v1.0.0）：jsDelivr 对分支名（@main）缓存持久不更新，
     *  tag URL 即时生效，已从真机验证 modules.json 与模块 zip 均 200。 */
    const val DEFAULT_REPO_URL =
        "https://cdn.jsdelivr.net/gh/qianyumeng0228/reshizukux-modules@v1.0.0/modules.json"

    private const val DEFAULT_REPO_NAME = "ReShizukuX 官方模块仓库"

    private const val USER_AGENT = "ReShizukuX-ModuleStore/1.0"

    /** 刷新 modules.json：连接超时 15s。 */
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 模块下载读取超时 60s。 */
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

    /** 模块下载体积上限 100MB。 */
    private const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

    /** 问题 #8：手动跟随重定向的最大次数，超过即报错。 */
    private const val MAX_REDIRECTS = 5

    @Volatile
    private var appContext: Context? = null

    /**
     * 问题 #15：仓库网络刷新失败回调（主线程外触发）。由 UI 层注入，收到后弹
     * snackbar「仓库加载失败，请检查网络」。
     */
    @Volatile
    var onRefreshError: ((String) -> Unit)? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        ensureDefaultRepo()
    }

    private fun db() = ModuleDatabase.getInstance(
        appContext ?: error("RepoManager not initialized; call init(context) first")
    )

    // ---------------------------------------------------------------------
    // 仓库 CRUD
    // ---------------------------------------------------------------------

    /** 添加仓库（URL 自动规范化为 .../modules.json）。 */
    fun addRepo(rawUrl: String, name: String) {
        val url = normalizeRepoUrl(rawUrl)
        val existing = db().repoDao().getByUrl(url)
        db().repoDao().upsert(
            Repo(
                url = url,
                name = name.ifBlank { existing?.name ?: url },
                publicKey = existing?.publicKey,
                enabled = existing?.enabled ?: true,
                lastRefresh = existing?.lastRefresh ?: 0L
            )
        )
    }

    fun removeRepo(url: String) {
        val normalized = normalizeRepoUrl(url)
        db().repoDao().deleteByUrl(normalized)
        db().repoModuleDao().deleteByRepo(normalized)
    }

    fun getRepos(): List<Repo> = db().repoDao().getAll()

    /**
     * 首次启动时若 repos 表为空，自动注入官方默认仓库（enabled=true）。
     * 同时把旧的 raw.githubusercontent.com URL 迁移到 jsDelivr CDN（国内可直连）。
     */
    fun ensureDefaultRepo() {
        // 迁移旧 URL（raw 直连被墙 → jsdelivr @main 分支缓存不更新 → @v1.0.0 不可变 tag）
        // 所有旧分支 URL（@main / @master）一律从 DB 清掉，随后 count==0 时写入 @v1.0.0。
        val oldUrls = listOf(
            "https://raw.githubusercontent.com/qianyumeng0228/reshizukux-modules/main/modules.json",
            "https://cdn.jsdelivr.net/gh/qianyumeng0228/reshizukux-modules@main/modules.json",
            "https://cdn.jsdelivr.net/gh/qianyumeng0228/reshizukux-modules@master/modules.json"
        )
        for (old in oldUrls) {
            if (db().repoDao().getByUrl(old) != null) {
                db().repoDao().deleteByUrl(old)
                db().repoModuleDao().deleteByRepo(old)
            }
        }
        if (db().repoDao().count() == 0) {
            db().repoDao().upsert(
                Repo(
                    url = DEFAULT_REPO_URL,
                    name = DEFAULT_REPO_NAME,
                    publicKey = null,
                    enabled = true,
                    lastRefresh = 0L
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // modules.json 刷新
    // ---------------------------------------------------------------------

    /**
     * 拉取并刷新仓库 modules.json。
     *
     * 流程：GET <url>/modules.json（15s 连接 / 30s 读取）→ 解析 [RepoManifest]
     * → 事务内清空该仓库旧 repo_modules 再批量插入 → 更新 repos.lastRefresh。
     *
     * @return 成功返回 Unit；失败返回网络/JSON/格式错误描述。
     */
    suspend fun refreshRepo(rawUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalizeRepoUrl(rawUrl)

            // 1. 先从 assets 加载（立即返回，不依赖网络/DNS）
            val assetBody = loadAssetManifest()
            if (assetBody != null) {
                parseAndStore(url, assetBody)
            }

            // 2. 网络刷新 fire-and-forget（DNS 可能 hang，不阻塞 UI）
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    val body = httpGet(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                        .getOrElse { throw IOException("fetch modules.json failed: ${it.message}", it) }
                    parseAndStore(url, body)
                }.onFailure {
                    // 问题 #15：网络/解析失败上报给 UI（snackbar）。
                    onRefreshError?.invoke(it.message ?: "network error")
                }
            }
            Unit
        }
    }

    private fun loadAssetManifest(): String? {
        return try {
            appContext?.assets?.open("modules.json")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) { null }
    }

    private fun parseAndStore(url: String, body: String) {
        // 用 org.json 手动解析（避免 R8 release 下 kotlinx.serialization 反射问题）
        val root = org.json.JSONObject(body)
        val name = root.optString("name", "")
        if (name.isBlank()) throw IOException("modules.json missing required field: name")

        val modulesArr = root.optJSONArray("modules")
        val infos = mutableListOf<RepoModuleInfo>()
        if (modulesArr != null) {
            for (i in 0 until modulesArr.length()) {
                val o = modulesArr.getJSONObject(i)
                val id = o.optString("id", "")
                val modName = o.optString("name", "")
                val downloadUrl = o.optString("downloadUrl", "")
                if (id.isBlank() || modName.isBlank() || downloadUrl.isBlank()) {
                    throw IOException("module entry missing required field (id/name/downloadUrl): $id")
                }
                infos.add(RepoModuleInfo(
                    id = id,
                    name = modName,
                    version = o.optString("version", ""),
                    versionCode = o.optInt("versionCode", 0),
                    author = o.optString("author", ""),
                    description = o.optString("description", ""),
                    downloadUrl = downloadUrl,
                    sha256 = o.optString("sha256", "").takeIf { it.isNotEmpty() },
                    signature = o.optString("signature", "").takeIf { it.isNotEmpty() },
                    publicKey = o.optString("publicKey", "").takeIf { it.isNotEmpty() },
                    minSdk = if (o.isNull("minSdk")) null else o.optInt("minSdk", 0).takeIf { it > 0 },
                    requiresRoot = if (o.isNull("requiresRoot")) null else o.optBoolean("requiresRoot", false),
                    usesWebUI = if (o.isNull("usesWebUI")) null else o.optBoolean("usesWebUI", false),
                    changelog = o.optString("changelog", "").takeIf { it.isNotEmpty() },
                    lastUpdated = if (o.isNull("lastUpdated")) 0L else o.optLong("lastUpdated", 0L)
                ))
            }
        }

        val database = db()
        val now = System.currentTimeMillis()
        database.runInTransaction {
            val existing = database.repoDao().getByUrl(url)
            database.repoDao().upsert(
                Repo(
                    url = url,
                    name = name,
                    publicKey = existing?.publicKey,
                    enabled = existing?.enabled ?: true,
                    lastRefresh = now
                )
            )
            database.repoModuleDao().deleteByRepo(url)
            val rows = infos.map { info ->
                RepoModule(
                    repoUrl = url,
                    id = info.id,
                    name = info.name,
                    version = info.version,
                    versionCode = info.versionCode,
                    author = info.author,
                    description = info.description,
                    downloadUrl = info.downloadUrl,
                    sha256 = info.sha256,
                    signature = info.signature,
                    publicKey = info.publicKey,
                    minSdk = info.minSdk,
                    requiresRoot = info.requiresRoot,
                    usesWebUI = info.usesWebUI,
                    changelog = info.changelog,
                    lastUpdated = info.lastUpdated
                )
            }
            if (rows.isNotEmpty()) {
                database.repoModuleDao().upsertAll(rows)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 模块下载
    // ---------------------------------------------------------------------

    /**
     * 从仓库缓存中下载模块 ZIP 到 [targetDir]。
     *
     * 文件名：`<id>-<version>.zip`。下载后：
     *  1. 若 manifest 提供 sha256，校验不匹配则删除文件并报错；
     *  2. 若同时提供 signature + publicKey，调用 [ModuleSecurity.verifyEd25519Signature]
     *     （P7 前为占位返回 true）。
     *
     * 读取超时 60s，体积上限 100MB。
     */
    suspend fun downloadModule(
        rawRepoUrl: String,
        moduleId: String,
        targetDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val repoUrl = normalizeRepoUrl(rawRepoUrl)
            val row = db().repoModuleDao().getByRepoAndId(repoUrl, moduleId)
                ?: throw IOException("module '$moduleId' not found in repo cache; refresh repo first")

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("cannot create target dir: ${targetDir.absolutePath}")
            }
            val target = File(targetDir, "${row.id}-${row.version}.zip")
            if (target.exists() && !target.delete()) {
                throw IOException("cannot overwrite existing file: ${target.absolutePath}")
            }

            downloadToFile(row.downloadUrl, target)

            // SHA-256 校验（若提供）
            val actualSha = ModuleSecurity.calculateSha256(target)
            row.sha256?.let { expected ->
                if (!actualSha.equals(expected, ignoreCase = true)) {
                    target.delete()
                    throw IOException("SHA-256 mismatch for ${row.id}: expected $expected, got $actualSha")
                }
            }

            // Ed25519 签名校验（P7 占位）
            val sig = row.signature
            val pubKey = row.publicKey
            if (!sig.isNullOrBlank() && !pubKey.isNullOrBlank()) {
                if (!ModuleSecurity.verifyEd25519Signature(actualSha, sig, pubKey)) {
                    target.delete()
                    throw IOException("Ed25519 signature verification failed for ${row.id}")
                }
            }

            target
        }
    }

    // ---------------------------------------------------------------------
    // 更新检测
    // ---------------------------------------------------------------------

    /**
     * 对比已安装模块与所有已启用仓库缓存的 versionCode，返回有更新的模块列表。
     *
     * 仅基于本地缓存（不触发网络刷新）；调用方应先 [refreshRepo] 各仓库。
     */
    suspend fun checkUpdates(): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val database = db()
        val installed = database.moduleDao().getAll().associateBy { it.id }
        val enabledRepos = database.repoDao().getEnabled()
        val repoNameByUrl = enabledRepos.associate { it.url to it.name }
        val enabledUrls = repoNameByUrl.keys

        val result = mutableListOf<UpdateInfo>()
        for (cached in database.repoModuleDao().getAll()) {
            if (cached.repoUrl !in enabledUrls) continue
            val inst = installed[cached.id] ?: continue
            if (cached.versionCode > inst.versionCode) {
                result += UpdateInfo(
                    moduleId = cached.id,
                    installedVersionCode = inst.versionCode,
                    installedVersionName = inst.version,
                    availableVersionCode = cached.versionCode,
                    availableVersionName = cached.version,
                    repoName = repoNameByUrl[cached.repoUrl] ?: cached.repoUrl,
                    repoUrl = cached.repoUrl,
                    downloadUrl = cached.downloadUrl
                )
            }
        }
        result
    }

    // ---------------------------------------------------------------------
    // 内部 HTTP 工具
    // ---------------------------------------------------------------------

    /**
     * GET 文本资源。成功返回响应体字符串；失败抛 [IOException]（含 HTTP 状态码）。
     */
    private fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): Result<String> =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code from $url")
                }
                conn.inputStream.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * 流式下载二进制到 [target]，60s 读取超时，超过 [MAX_DOWNLOAD_BYTES] 中止。
     * 问题 #8：关闭自动重定向，手动跟随 Location，最多 [MAX_REDIRECTS] 次，超过报错。
     */
    private fun downloadToFile(downloadUrl: String, target: File) {
        var current = downloadUrl
        repeat(MAX_REDIRECTS + 1) { hop ->
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location == null) {
                        throw IOException("HTTP $code without Location header: $current")
                    }
                    if (hop >= MAX_REDIRECTS) {
                        throw IOException("重定向次数超过 $MAX_REDIRECTS 次：$downloadUrl")
                    }
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IOException("HTTP $code when downloading $downloadUrl")
                }
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > MAX_DOWNLOAD_BYTES) {
                                throw IOException("download exceeds 100MB cap: $downloadUrl")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 规范化仓库 URL：去掉末尾斜杠，若不以 /modules.json 结尾则自动补全。
     */
    private fun normalizeRepoUrl(raw: String): String {
        var u = raw.trim()
        require(u.startsWith("http://") || u.startsWith("https://")) {
            "repo url must start with http(s): $raw"
        }
        while (u.endsWith("/")) u = u.dropLast(1)
        if (!u.endsWith("/modules.json")) {
            u = "$u/modules.json"
        }
        return u
    }

    // ---------------------------------------------------------------------
    // GitHub Topic 搜索（参考 Shevery）
    // ---------------------------------------------------------------------

    data class GitHubModule(
        val fullName: String,       // owner/repo
        val name: String,           // repo name
        val description: String,
        val htmlUrl: String,
        val stars: Int,
        val author: String,
        val updatedAt: String
    )

    /**
     * 搜索 GitHub 上 topic 为 reshizukux-module 的仓库。
     * 通过 gh-proxy.com 代理 api.github.com。
     */
    suspend fun searchGithubModules(query: String = ""): Result<List<GitHubModule>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 用 gh-proxy 代理 GitHub API
                val q = if (query.isBlank()) "(topic:reshizukux-module OR topic:shevery-module)"
                        else "(topic:reshizukux-module OR topic:shevery-module) $query"
                val apiUrl = "https://gh-proxy.com/https://api.github.com/search/repositories?q=" +
                        java.net.URLEncoder.encode(q, "UTF-8") + "&sort=updated&per_page=30"

                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IOException("GitHub API HTTP $code")
                    }
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val root = org.json.JSONObject(body)
                    val items = root.optJSONArray("items") ?: return@runCatching emptyList()
                    val result = mutableListOf<GitHubModule>()
                    for (i in 0 until items.length()) {
                        val o = items.getJSONObject(i)
                        result.add(GitHubModule(
                            fullName = o.optString("full_name", ""),
                            name = o.optString("name", ""),
                            description = o.optString("description", "").ifBlank { "（无描述）" },
                            htmlUrl = o.optString("html_url", ""),
                            stars = o.optInt("stargazers_count", 0),
                            author = o.optJSONObject("owner")?.optString("login", "") ?: "",
                            updatedAt = o.optString("updated_at", "")
                        ))
                    }
                    result
                } finally {
                    conn.disconnect()
                }
            }
        }
}
