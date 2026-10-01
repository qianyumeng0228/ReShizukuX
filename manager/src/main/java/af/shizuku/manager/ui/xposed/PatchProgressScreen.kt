package af.shizuku.manager.ui.xposed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import af.shizuku.manager.xposed.PatchController
import af.shizuku.manager.xposed.PatchStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Steps 4–5: runs the patcher on [Dispatchers.IO], shows a progress bar driven by the patcher's
 * own stage callbacks plus a rolling log, and then reports the install outcome.
 */
@Composable
fun PatchProgressScreen(
    controller: PatchController,
    onFinished: (targetPackage: String?) -> Unit,
) {
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { controller.runPatch() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (controller.step) {
            PatchStep.RUNNING -> {
                Text("正在 Patch ${controller.target?.label ?: ""}", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { controller.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${controller.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PatchStep.DONE -> {
                Text("Patch 完成", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                controller.outputApk?.let {
                    Text("输出：${it.name}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    controller.installMessage ?: "已提交安装",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            PatchStep.FAILED -> {
                Text("Patch 失败", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error)
                Text(
                    controller.error ?: "未知错误",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {}
        }

        Text("日志", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(controller.logLines) { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (controller.step == PatchStep.DONE || controller.step == PatchStep.FAILED) {
            Button(
                onClick = { onFinished(controller.target?.packageName) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("完成") }
            if (controller.step == PatchStep.FAILED) {
                OutlinedButton(
                    onClick = { controller.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("重试") }
            }
        }
    }
}
