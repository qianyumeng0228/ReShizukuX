package af.shizuku.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * 终端 Tab：通过 Shizuku（shell UID 2000 特权）执行 adb shell 命令并回显输出。
 *
 * 执行引擎固定为 [Shizuku.newProcess]（参考 WifiDebugReassert 已验证用法），
 * 不使用 Runtime.getRuntime().exec —— 后者只拿到 app 自身权限，无法证明 shell 特权。
 *
 * - 服务状态复用全局 [ShizukuStateMachine]；未运行时执行按钮禁用并给出提示。
 * - stdout / stderr 并发读取后合并显示，等待退出带 15s 超时，超时 destroy() 终止。
 */
@Composable
fun TerminalTab() {
    val scope = rememberCoroutineScope()

    val state by ShizukuStateMachine.asFlow()
        .collectAsState(initial = ShizukuStateMachine.get())
    LaunchedEffect(Unit) { ShizukuStateMachine.update() }
    val running = state == ShizukuStateMachine.State.RUNNING

    var input by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }
    // 输出区：每行一条（命令行以 "$ " 开头，其余为 stdout/stderr 文本）。
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    // 输出新增后自动滚动到底部。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            // 等一帧让 LazyColumn 完成布局再滚动。
            delay(16)
            runCatching { listState.animateScrollToItem(lines.size - 1) }
        }
    }

    fun runCommand() {
        val cmd = input.trim()
        if (cmd.isEmpty() || executing || !running) return
        executing = true
        lines.add("$ $cmd")
        input = ""
        scope.launch {
            val output = withContext(Dispatchers.IO) {
                execViaShizuku(cmd)
            }
            lines.add(output)
            executing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- 顶部状态条 ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (running) Color(0xFF4CAF50) else Color.Gray,
                        shape = CircleShape
                    )
            )
            Text(
                text = if (running) "Shizuku 服务运行中（shell 权限）"
                else "Shizuku 服务未运行，请先在状态页启动",
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error
            )
        }

        // --- 输出区域 ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(8.dp)
                )
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = "暂无输出。输入 shell 命令后点「执行」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(lines.size) { idx ->
                        val text = lines[idx]
                        Text(
                            text = text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (text.startsWith("$ ")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // --- 常用命令快捷栏（点击填入输入框，不自动执行） ---
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickCommands.forEach { qc ->
                TextButton(
                    enabled = !executing,
                    onClick = { input = qc }
                ) { Text(qc, style = MaterialTheme.typography.labelSmall) }
            }
        }

        // --- 命令输入区 ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (!executing) input = it },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 120.dp),
                placeholder = { Text("输入 shell 命令…") },
                singleLine = false,
                maxLines = 3,
                enabled = !executing && running
            )
            Button(
                onClick = { runCommand() },
                enabled = !executing && running && input.isNotBlank()
            ) {
                Text(if (executing) "停止中…" else "执行")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (executing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("执行中…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { lines.clear() }, enabled = !executing && lines.isNotEmpty()) {
                Text("清除")
            }
        }
    }
}

private val QuickCommands = listOf(
    "ls",
    "id",
    "whoami",
    "pm list packages",
    "settings get global adb_wifi_enabled",
    "dumpsys activity activities | head"
)

/**
 * 通过 Shizuku shell 执行单条命令，合并 stdout/stderr 返回为一段文本。
 * 必须在 IO 调度器上调用。15s 超时后 destroy() 进程。
 */
private suspend fun execViaShizuku(cmd: String): String {
    if (!Shizuku.pingBinder()) {
        return "（错误）Shizuku 服务未运行\n"
    }
    return try {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        coroutineScope {
            // stdout / stderr 并发读，避免管道缓冲区写满导致子进程阻塞。
            val outDeferred = async(Dispatchers.IO) {
                runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
            }
            val errDeferred = async(Dispatchers.IO) {
                runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("")
            }
            val exitCode = withTimeoutOrNull(15_000L) { p.waitFor() }
            if (exitCode == null) {
                runCatching { p.destroy() }
                "（命令执行超过 15s，已强制终止）\n"
            } else {
                val out = outDeferred.await()
                val err = errDeferred.await()
                buildString {
                    if (out.isNotEmpty()) append(out)
                    if (err.isNotEmpty()) {
                        if (isNotEmpty() && !endsWith("\n")) append("\n")
                        append(err)
                    }
                    if (isEmpty()) append("（无输出，退出码=$exitCode）")
                    if (!endsWith("\n")) append("\n")
                }
            }
        }
    } catch (e: Exception) {
        Timber.tag("TerminalTab").w(e, "exec failed: %s", cmd)
        "执行失败：${e.javaClass.simpleName}: ${e.message}\n"
    }
}
