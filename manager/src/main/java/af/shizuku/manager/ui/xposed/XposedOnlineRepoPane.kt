package af.shizuku.manager.ui.xposed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.reshizukux.xposed.repo.XposedRepoModule
import io.reshizukux.xposed.repo.XposedRepoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "Xposed 模块" branch of the 在线仓库 sub-tab.
 *
 * Loads the flat modules.lsposed.org JSON feed via [XposedRepoRepository] and lists each module as a
 * card (letter avatar, package name, summary, target scope). The download button fetches the latest
 * release apk into [Context.getCacheDir] so the user can pick it up afterwards; the caller surfaces
 * a snackbar pointing them back to the Xposed patch flow.
 *
 * @param query free-text filter shared with the parent search box.
 * @param onDownloaded invoked on the main thread once an apk lands in cacheDir.
 */
@Composable
fun XposedOnlineRepoPane(
    query: String,
    onDownloaded: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modules by remember { mutableStateOf<List<XposedRepoModule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyDownload by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        error = null
        runCatching { XposedRepoRepository.fetchLsposedRepo() }
            .onSuccess { modules = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun download(mod: XposedRepoModule) {
        if (busyDownload != null) return
        scope.launch {
            busyDownload = mod.packageName
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "xposed-repo").apply { mkdirs() }
                    val out = File(dir, "${mod.packageName}-latest.apk")
                    val conn = (URL(mod.downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                    conn.inputStream.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out
                }
            }
            busyDownload = null
            result.onSuccess { onDownloaded(it) }
                .onFailure { error = "下载失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LSPosed 在线仓库（${modules.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { scope.launch { reload() } }, enabled = !loading) {
                Text(if (loading) "加载中…" else "刷新")
            }
        }

        when {
            loading && modules.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && modules.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { scope.launch { reload() } }) { Text("重试") }
                }
            }

            else -> {
                val filtered = modules.filter {
                    query.isBlank() ||
                        it.packageName.contains(query, true) ||
                        it.summary.contains(query, true)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.packageName }) { mod ->
                        XposedRepoModuleCard(
                            mod = mod,
                            busy = busyDownload == mod.packageName,
                            onDownload = { download(mod) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XposedRepoModuleCard(
    mod: XposedRepoModule,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Letter avatar (upstream feed ships no iconUrl; this stays as a graceful fallback).
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    mod.packageName.substringAfterLast('.').firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(mod.packageName, style = MaterialTheme.typography.titleSmall)
                if (mod.summary.isNotBlank()) {
                    Text(
                        mod.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (mod.scope.isNotEmpty()) {
                    Text(
                        "作用域：" + mod.scope.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                OutlinedButton(onClick = onDownload) { Text("下载") }
            }
        }
    }
}
