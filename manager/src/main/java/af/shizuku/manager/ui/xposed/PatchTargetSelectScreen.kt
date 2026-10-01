package af.shizuku.manager.ui.xposed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import af.shizuku.manager.xposed.PatchController
import af.shizuku.manager.xposed.PatchMode
import af.shizuku.manager.xposed.PatchStep
import af.shizuku.manager.xposed.PatchTargetApp
import io.reshizukux.xposed.scan.XposedModuleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Steps 1–3 of the new-patch wizard: pick target app, pick modules (multi), pick mode, launch.
 * Step 4 (running) and step 5 (done) render in [PatchProgressScreen]; this composable delegates
 * there once the user confirms the mode choice.
 */
@Composable
fun PatchTargetSelectScreen(
    controller: PatchController,
    onBack: () -> Unit,
    onStarted: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (controller.targets.isEmpty()) {
            withContext(Dispatchers.IO) {
                controller.loadTargets()
                controller.availableModules = runCatching {
                    XposedModuleScanner(context.packageManager).scan()
                }.getOrDefault(emptyList())
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable {
                        if (controller.step == PatchStep.SELECT_TARGET) onBack() else controller.back()
                    }
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    when (controller.step) {
                        PatchStep.SELECT_TARGET -> "1/3 选择目标应用"
                        PatchStep.SELECT_MODULES -> "2/3 选择模块"
                        PatchStep.SELECT_MODE -> "3/3 选择 Patch 模式"
                        else -> "新建 Patch"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (controller.step) {
                PatchStep.SELECT_TARGET -> TargetStep(controller, onSelect = { controller.selectTarget(it) })
                PatchStep.SELECT_MODULES -> ModuleStep(
                    controller,
                    onNext = { controller.goToMode() },
                    onSkip = { controller.goToMode() }
                )
                PatchStep.SELECT_MODE -> ModeStep(controller, onStart = onStarted)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun TargetStep(controller: PatchController, onSelect: (PatchTargetApp) -> Unit) {
    if (controller.targets.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        items(controller.targets, key = { it.packageName }) { app ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelect(app) }
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val bmp = remember(app.icon) { app.icon?.let { runCatching { it.toBitmap() }.getOrNull() } }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Column {
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleStep(controller: PatchController, onNext: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "选择要注入的 Xposed 模块（Integrated 模式下会打包进 apk）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(controller.availableModules, key = { it.packageName }) { mod ->
                val checked = controller.selectedModules.any { it.packageName == mod.packageName }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.toggleModule(mod) }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = checked,
                            onClick = { controller.toggleModule(mod) },
                            label = { Text(if (checked) "已选" else "未选") }
                        )
                        Column {
                            Text(mod.name, style = MaterialTheme.typography.titleSmall)
                            Text(mod.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSkip) { Text("跳过") }
            Button(onClick = onNext) { Text("下一步 (${controller.selectedModules.size})") }
        }
    }
}

@Composable
private fun ModeStep(controller: PatchController, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Patch 模式", style = MaterialTheme.typography.titleMedium)
        ModeRow(
            title = "Integrated（内置）",
            desc = "模块打包进 apk，独立运行，不需要管理器",
            selected = controller.mode == PatchMode.INTEGRATED,
            onClick = { controller.mode = PatchMode.INTEGRATED }
        )
        ModeRow(
            title = "Manager（管理器）",
            desc = "由 ReShizukuX 运行时提供模块，apk 体积更小",
            selected = controller.mode == PatchMode.MANAGER,
            onClick = { controller.mode = PatchMode.MANAGER }
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth()
        ) { Text("开始 Patch") }
    }
}

@Composable
private fun ModeRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
