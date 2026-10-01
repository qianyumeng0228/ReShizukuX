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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import af.shizuku.manager.xposed.PatchedAppInfo
import io.reshizukux.xposed.scan.XposedModuleInfo
import io.reshizukux.xposed.scan.XposedModuleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Xposed 模块" category of the Modules tab.
 *
 * Upper half: modules already installed on this device that [XposedModuleScanner] flags as Xposed
 * entry points (modern `META-INF/xposed/` layout or legacy `assets/xposed_init`), each shown with
 * its launcher icon, label, version and a modern/legacy badge.
 *
 * Lower half: applications already patched this session. Nothing is persisted yet, so a cold start
 * shows the empty state until a patch completes.
 */
@Composable
fun XposedModulesTabContent(
    patchedApps: List<PatchedAppInfo>,
    onStartPatch: () -> Unit,
    onUninstall: (String) -> Unit = {},
    onRepatch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var modules by remember { mutableStateOf<List<XposedModuleInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scanning = true
        modules = withContext(Dispatchers.IO) {
            runCatching { XposedModuleScanner(context.packageManager).scan() }.getOrDefault(emptyList())
        }
        scanning = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "已安装 Xposed 模块",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (scanning) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else if (modules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "未检测到 Xposed 模块",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(modules, key = { it.packageName }) { mod ->
                    XposedModuleRow(mod)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "已 patch 的应用",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (patchedApps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无已 patch 应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(patchedApps, key = { it.packageName }) { info ->
                    PatchedAppCard(
                        info = info,
                        onUninstall = { onUninstall(info.packageName) },
                        onRepatch = { onRepatch(info.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchedAppCard(
    info: PatchedAppInfo,
    onUninstall: () -> Unit,
    onRepatch: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager

    // Resolve the app icon + label off PackageManager; if the patched apk was uninstalled out-of-band,
    // the package is gone and we fall back to a letter avatar + raw package name.
    val label = remember(info.packageName) {
        runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(info.packageName, 0)).toString()
        }.getOrDefault(info.packageName)
    }
    val iconBitmap = remember(info.packageName) {
        runCatching { pm.getApplicationIcon(info.packageName).toBitmap() }.getOrNull()
    }

    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    info.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val n = info.modulePackageNames.size
                Text(
                    "已嵌入 $n 个模块",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (n > 0) {
                    Text(
                        info.modulePackageNames.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("⋮", style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("卸载 patched APK") },
                        onClick = { menuOpen = false; onUninstall() }
                    )
                    DropdownMenuItem(
                        text = { Text("重新 patch") },
                        onClick = { menuOpen = false; onRepatch() }
                    )
                }
            }
        }
    }
}

@Composable
private fun XposedModuleRow(mod: XposedModuleInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val bitmap = remember(mod.icon) {
                mod.icon?.let { runCatching { it.toBitmap() }.getOrNull() }
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = mod.name,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mod.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(mod.name, style = MaterialTheme.typography.titleMedium)
                    val (badge, color) = if (mod.isModern) "modern" to Color(0xFF4CAF50) else "legacy" to Color(0xFFFF9800)
                    AssistChip(
                        onClick = {},
                        label = { Text(badge, style = MaterialTheme.typography.labelSmall, color = color) }
                    )
                }
                Text(
                    mod.versionName ?: mod.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
