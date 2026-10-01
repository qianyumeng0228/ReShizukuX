package io.reshizukux.xposed.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * One Xposed module entry as surfaced by the online LSPosed module repository.
 *
 * @property packageName module package id (the `name` field in the upstream JSON).
 * @property summary one-line description.
 * @property description longer description.
 * @property iconUrl optional icon URL; the upstream feed currently omits this field, so it is
 *   always null in practice and the UI falls back to a letter-avatar.
 * @property downloadUrl direct URL to the latest release's first `.apk` asset.
 * @property scope the target packages this module hooks (informational).
 */
@Serializable
data class XposedRepoModule(
    val packageName: String,
    val summary: String,
    val description: String,
    val iconUrl: String? = null,
    val downloadUrl: String,
    val scope: List<String> = emptyList(),
)

/**
 * Fetches and parses https://backup.modules.lsposed.org/modules.json into [XposedRepoModule]s.
 *
 * The upstream feed is a *flat* JSON array (~1000+ modules) with a nested releases/releaseAssets
 * structure; we collapse each entry to the first downloadable `.apk` of its latest release and
 * drop entries that ship no installable asset. Parsing is lenient ([ignoreUnknownKeys]) because the
 * feed carries many fields we do not model.
 *
 * Network runs on [Dispatchers.IO] with a 10s connect/read timeout; callers may throw on IO failure.
 */
object XposedRepoRepository {

    private const val ENDPOINT = "https://backup.modules.lsposed.org/modules.json"
    private const val TIMEOUT_MS = 10_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Upstream entry — only the fields we consume are modelled; everything else is ignored. */
    @Serializable
    private data class RepoEntry(
        val name: String? = null,
        val summary: String? = null,
        val description: String? = null,
        val iconUrl: String? = null,
        val scope: List<String>? = null,
        val releases: List<Release>? = null,
    )

    @Serializable
    private data class Release(
        val releaseAssets: List<Asset>? = null,
    )

    @Serializable
    private data class Asset(
        val downloadUrl: String? = null,
    )

    /**
     * @return parsed modules, sorted alphabetically by package name.
     * @throws Exception on network/parse failure (callers wrap in runCatching).
     */
    suspend fun fetchLsposedRepo(): List<XposedRepoModule> = withContext(Dispatchers.IO) {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        conn.inputStream.use { stream ->
            val body = stream.bufferedReader(Charsets.UTF_8).readText()
            val entries: List<RepoEntry> = json.decodeFromString(body)
            entries.mapNotNull { it.toModule() }
                .sortedBy { it.packageName.lowercase() }
        }
    }

    private fun RepoEntry.toModule(): XposedRepoModule? {
        val pkg = name?.takeIf { it.isNotBlank() } ?: return null
        // Pick the first .apk asset across the listed releases (upstream orders newest first).
        val dl = releases?.firstNotNullOfOrNull { rel ->
            rel.releaseAssets?.firstNotNullOfOrNull { asset ->
                asset.downloadUrl?.takeIf { u -> u.isNotBlank() && u.endsWith(".apk", ignoreCase = true) }
            }
        } ?: return null
        return XposedRepoModule(
            packageName = pkg,
            summary = summary.orEmpty(),
            description = description.orEmpty(),
            iconUrl = iconUrl,
            downloadUrl = dl,
            scope = scope ?: emptyList(),
        )
    }
}
