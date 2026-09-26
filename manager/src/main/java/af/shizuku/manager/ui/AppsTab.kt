package af.shizuku.manager.ui

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 授权 Tab（Compose 实现）：
 *
 * - 列表数据复用 [AuthorizationManager.getPackages]（仅声明了 Shizuku 权限的应用）。
 * - 授权状态复用 [AuthorizationManager.granted]，授权/取消复用 [AuthorizationManager.grant] /
 *   [AuthorizationManager.revoke]，与旧版 ApplicationManagementActivity 同一套 binder 通道。
 * - 服务未运行时不加载列表，直接引导用户回首页启动。
 * - 顶部搜索框 + 全部/已授权/未授权 FilterChip 筛选；应用图标按可见项懒加载。
 *
 * 旧版 ApplicationManagementActivity 保留未删除，仍可从外部入口跳转。
 */
@Composable
fun AppsTab() {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()

    // 与 HomeTab 一致：复用全局状态机，服务起来后自动刷新。
    val state by ShizukuStateMachine.asFlow()
        .collectAsState(initial = ShizukuStateMachine.get())
    val running = state == ShizukuStateMachine.State.RUNNING

    val packages = remember { mutableStateListOf<PackageInfo>() }
    // packageName -> 是否已授权；用 SnapshotStateMap 让 Switch 在切换后即时重组。
    val grantedMap = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    fun reload() {
        scope.launch {
            loading = true
            val (pkgs, grants) = withContext(Dispatchers.IO) {
                val list = runCatching { AuthorizationManager.getPackages() }
                    .getOrDefault(emptyList())
                val g = HashMap<String, Boolean>()
                list.forEach { pi ->
                    val ai = pi.applicationInfo ?: return@forEach
                    g[pi.packageName] = runCatching {
                        AuthorizationManager.granted(pi.packageName, ai.uid)
                    }.getOrDefault(false)
                }
                list to g
            }
            packages.clear()
            packages.addAll(pkgs)
            grantedMap.clear()
            grantedMap.putAll(grants)
            loading = false
        }
    }

    // 服务进入 RUNNING 时加载一次；服务掉线后由状态机驱动 UI 切回空状态。
    LaunchedEffect(running) {
        if (running) reload()
    }

    fun onToggleGranted(pi: PackageInfo, wantGrant: Boolean) {
        val ai = pi.applicationInfo ?: return
        val pkg = pi.packageName
        val uid = ai.uid
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (wantGrant) AuthorizationManager.grant(pkg, uid)
                    else AuthorizationManager.revoke(pkg, uid)
                }
            }
            grantedMap[pkg] = wantGrant
        }
    }

    val filtered = remember(packages, searchQuery, filter, grantedMap) {
        packages.filter { pi ->
            val ai = pi.applicationInfo
            val label = ai?.loadLabel(pm)?.toString() ?: ""
            val matchesSearch = searchQuery.isBlank() ||
                label.contains(searchQuery, ignoreCase = true) ||
                pi.packageName.contains(searchQuery, ignoreCase = true)
            val isGranted = grantedMap[pi.packageName] ?: false
            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.GRANTED -> isGranted
                AppFilter.DENIED -> !isGranted
            }
            matchesSearch && matchesFilter
        }.sortedBy {
            it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: it.packageName
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.rsx_apps_title),
                style = MaterialTheme.typography.headlineSmall
            )
            TextButton(onClick = { if (running) reload() }) {
                Text(stringResource(R.string.rsx_apps_refresh))
            }
        }

        if (!running) {
            // 空状态：服务未启动。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rsx_apps_not_running),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.rsx_apps_go_start),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(stringResource(R.string.rsx_apps_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(stringResource(f.labelRes)) }
                )
            }
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            filtered.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank() || filter != AppFilter.ALL)
                            stringResource(R.string.rsx_apps_no_match)
                        else
                            stringResource(R.string.rsx_apps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.packageName }) { pi ->
                        AppRow(
                            packageInfo = pi,
                            pm = pm,
                            granted = grantedMap[pi.packageName] ?: false,
                            onToggle = { want -> onToggleGranted(pi, want) }
                        )
                    }
                }
            }
        }
    }
}

private enum class AppFilter(@StringRes val labelRes: Int) {
    ALL(R.string.rsx_apps_all),
    GRANTED(R.string.rsx_apps_granted),
    DENIED(R.string.rsx_apps_denied)
}

@Composable
private fun AppRow(
    packageInfo: PackageInfo,
    pm: PackageManager,
    granted: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val ai = packageInfo.applicationInfo
    val label = ai?.loadLabel(pm)?.toString() ?: packageInfo.packageName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageInfo = packageInfo, pm = pm, modifier = Modifier.size(40.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Text(
                text = packageInfo.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Switch(
            checked = granted,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun AppIcon(
    packageInfo: PackageInfo,
    pm: PackageManager,
    modifier: Modifier = Modifier
) {
    // 仅在该 item 进入组合时才在 IO 线程加载图标，LazyColumn 滚动出屏后 produceState 随之释放，
    // 避免一次性解码全部应用图标造成卡顿。
    val bitmap by produceState<Bitmap?>(initialValue = null, packageInfo.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val ai = packageInfo.applicationInfo ?: return@withContext null
                drawableToBitmap(ai.loadIcon(pm))
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(
                MaterialTheme.colorScheme.surfaceVariant,
                CircleShape
            )
        )
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}
