package af.shizuku.manager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import io.reshizukux.modules.core.ModuleInfo
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.db.Repo
import io.reshizukux.modules.db.RepoModule
import io.reshizukux.modules.repository.RepoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模块 Tab（P6，设计方案 §4.3）：模块列表入口。
 *
 * 结构：
 *  - 顶部：搜索框 + TabRow（已安装 | 在线仓库）
 *  - 已安装：LazyColumn + [ModuleCard]，FAB「从 ZIP 安装」（SAF 选 .zip）
 *  - 在线仓库：刷新 modules.json + 列出 RepoModule 卡片，安装按钮下载并安装
 *  - 点击卡片 → selectedModuleId 非 null → 全屏切换到 [ModuleDetailScreen]
 *
 * 所有耗时操作（install/enable/disable/download/refresh）在 Dispatchers.IO。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var tabIndex by remember { mutableIntStateOf(0) } // 0=已安装, 1=在线仓库

    var installed by remember { mutableStateOf<List<ModuleInfo>>(emptyList()) }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var selectedRepoUrl by remember { mutableStateOf<String?>(null) }
    var repoModules by remember { mutableStateOf<List<RepoModule>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busyInstallId by remember { mutableStateOf<String?>(null) }

    var selectedModuleId by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // --- ZIP 安装 SAF 选择器 ---
    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyInstallId = "zip"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cacheFile = File(context.cacheDir, "install-${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法打开所选文件" }
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    ModuleManager.install(cacheFile).getOrThrow()
                }
            }
            busyInstallId = null
            result.onSuccess { info ->
                snackbarHostState.showSnackbar("已安装：${info.name}")
                refreshKey++
            }.onFailure { e ->
                snackbarHostState.showSnackbar("安装失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // --- 数据加载 ---
    suspend fun loadInstalled() {
        installed = withContext(Dispatchers.IO) { ModuleManager.getInstalledModules() }
    }

    suspend fun loadRepoModules() {
        val url = selectedRepoUrl ?: return
        repoModules = withContext(Dispatchers.IO) {
            runCatching {
                ModuleDatabase.getInstance(context).repoModuleDao().getByRepo(url)
            }.getOrDefault(emptyList())
        }
    }

    suspend fun refreshRepos() {
        loading = true
        withContext(Dispatchers.IO) { RepoManager.ensureDefaultRepo() }
        val repoList = withContext(Dispatchers.IO) { RepoManager.getRepos() }
        repos = repoList
        if (selectedRepoUrl == null) selectedRepoUrl = repoList.firstOrNull()?.url
        val url = selectedRepoUrl
        if (url != null) {
            val r = withContext(Dispatchers.IO) { RepoManager.refreshRepo(url) }
            r.onFailure { e ->
                snackbarHostState.showSnackbar("仓库刷新失败：${e.message ?: ""}")
            }
        }
        loadRepoModules()
        loading = false
    }

    LaunchedEffect(refreshKey) {
        loadInstalled()
    }

    LaunchedEffect(tabIndex, selectedRepoUrl) {
        if (tabIndex == 1 && repos.isEmpty()) refreshRepos()
        if (tabIndex == 1) loadRepoModules()
    }

    // 进入仓库 Tab 时若已选仓库，下拉刷新。
    fun installFromRepo(repoUrl: String, moduleId: String) {
        if (busyInstallId != null) return
        scope.launch {
            busyInstallId = moduleId
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "repo-downloads").apply { mkdirs() }
                    val zip = RepoManager.downloadModule(repoUrl, moduleId, dir).getOrThrow()
                    // 取仓库缓存的签名/公钥传给 install（P7 签名验证）
                    val row = ModuleDatabase.getInstance(context).repoModuleDao().getByRepoAndId(repoUrl, moduleId)
                    ModuleManager.install(zip, row?.signature, row?.publicKey).getOrThrow()
                }
            }
            busyInstallId = null
            result.onSuccess { info ->
                snackbarHostState.showSnackbar("已安装：${info.name}")
                refreshKey++
                loadRepoModules()
            }.onFailure { e ->
                snackbarHostState.showSnackbar("安装失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // --- 模块详情页全屏覆盖 ---
    selectedModuleId?.let { id ->
        ModuleDetailScreen(
            moduleId = id,
            onBack = {
                selectedModuleId = null
                refreshKey++
            }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (tabIndex == 0) {
                FloatingActionButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Icon(painter = painterResource(R.drawable.ic_add_24), contentDescription = "从 ZIP 安装")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索模块…") },
                singleLine = true
            )

            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("已安装") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("在线仓库") })
            }

            if (tabIndex == 0) {
                InstalledList(
                    modules = installed.filter {
                        searchQuery.isBlank() ||
                            it.name.contains(searchQuery, true) ||
                            it.id.contains(searchQuery, true) ||
                            it.author.contains(searchQuery, true)
                    },
                    busy = busyInstallId,
                    onToggle = { info, want ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (want) ModuleManager.enable(info.id) else ModuleManager.disable(info.id)
                            }
                            refreshKey++
                        }
                    },
                    onClick = { selectedModuleId = it.id }
                )
            } else {
                RepoList(
                    repos = repos,
                    selectedRepoUrl = selectedRepoUrl,
                    onSelectRepo = { selectedRepoUrl = it },
                    onRefresh = { scope.launch { refreshRepos() } },
                    modules = repoModules.filter {
                        searchQuery.isBlank() ||
                            it.name.contains(searchQuery, true) ||
                            it.id.contains(searchQuery, true) ||
                            it.author.contains(searchQuery, true)
                    },
                    installedIds = installed.map { it.id }.toSet(),
                    loading = loading,
                    busyInstallId = busyInstallId,
                    onInstall = { repoUrl, modId -> installFromRepo(repoUrl, modId) }
                )
            }
        }
    }
}

@Composable
private fun InstalledList(
    modules: List<ModuleInfo>,
    busy: String?,
    onToggle: (ModuleInfo, Boolean) -> Unit,
    onClick: (ModuleInfo) -> Unit
) {
    if (modules.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无已安装模块\n去「在线仓库」安装，或点右下角按钮从本地 ZIP 安装。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(modules, key = { it.id }) { info ->
            ModuleCard(
                info = info,
                busy = busy != null,
                onToggle = { onToggle(info, it) },
                onClick = { onClick(info) }
            )
        }
    }
}

@Composable
private fun ModuleCard(
    info: ModuleInfo,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 首字母圆形图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    info.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    StateChip(info.state)
                }
                Text("v${info.version} · ${info.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!info.publicKey.isNullOrBlank()) {
                    Text("已签名", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                }
            }
            Switch(
                checked = info.state == ModuleState.ENABLED,
                enabled = !busy && info.state != ModuleState.CORRUPTED,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun StateChip(state: ModuleState) {
    val (text, color) = when (state) {
        ModuleState.ENABLED -> "启用" to Color(0xFF4CAF50)
        ModuleState.DISABLED -> "停用" to Color.Gray
        ModuleState.ERROR -> "错误" to MaterialTheme.colorScheme.error
        ModuleState.CORRUPTED -> "损坏" to Color(0xFFFF9800)
        ModuleState.UPDATING -> "更新中" to Color(0xFF2196F3)
        ModuleState.NOT_INSTALLED -> "未安装" to Color.Gray
    }
    AssistChip(onClick = {}, label = { Text(text, color = color, style = MaterialTheme.typography.labelSmall) })
}

@Composable
private fun RepoList(
    repos: List<Repo>,
    selectedRepoUrl: String?,
    onSelectRepo: (String) -> Unit,
    onRefresh: () -> Unit,
    modules: List<RepoModule>,
    installedIds: Set<String>,
    loading: Boolean,
    busyInstallId: String?,
    onInstall: (repoUrl: String, moduleId: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (repos.isEmpty()) {
                Text("暂无仓库", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repos.forEach { repo ->
                        AssistChip(
                            onClick = { onSelectRepo(repo.url) },
                            label = { Text(repo.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "刷新中…" else "刷新")
            }
        }

        if (loading && modules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        if (modules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("仓库暂无模块", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(modules, key = { it.repoUrl + it.id }) { mod ->
                RepoModuleCard(
                    mod = mod,
                    alreadyInstalled = mod.id in installedIds,
                    busy = busyInstallId == mod.id,
                    onInstall = { selectedRepoUrl?.let { url -> onInstall(url, mod.id) } }
                )
            }
        }
    }
}

@Composable
private fun RepoModuleCard(
    mod: RepoModule,
    alreadyInstalled: Boolean,
    busy: Boolean,
    onInstall: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(mod.name, style = MaterialTheme.typography.titleMedium)
                if (!mod.signature.isNullOrBlank() && !mod.publicKey.isNullOrBlank()) {
                    Text("已签名", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                } else {
                    Text("未验证", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Text("v${mod.version} · ${mod.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mod.description.isNotBlank()) {
                Text(mod.description, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    OutlinedButton(onClick = onInstall, enabled = !alreadyInstalled) {
                        Text(if (alreadyInstalled) "已安装" else "安装")
                    }
                }
            }
        }
    }
}
