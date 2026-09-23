package af.shizuku.manager.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine

/**
 * 状态 Tab（首页）：服务状态大卡片 + 单开关。
 *
 * 状态来源复用全局 [ShizukuStateMachine]（内部已挂接 binder received/dead 监听），
 * 不重新实现连接逻辑；开关打开时复用现有 [StarterActivity] 走 Root/ADB 激活流程。
 */
@Composable
fun HomeTab() {
    val context = LocalContext.current

    // ShizukuStateMachine 是全局单例，asFlow() 会立刻补发当前状态。
    val state by ShizukuStateMachine.asFlow()
        .collectAsState(initial = ShizukuStateMachine.get())

    // 每次进入页面时校正一次状态（捕获崩溃/掉线等边角情况）。
    LaunchedEffect(Unit) {
        ShizukuStateMachine.update()
    }

    val running = state == ShizukuStateMachine.State.RUNNING
    val statusText = when (state) {
        ShizukuStateMachine.State.RUNNING -> "运行中"
        ShizukuStateMachine.State.STARTING -> "启动中…"
        ShizukuStateMachine.State.STOPPING -> "停止中…"
        ShizukuStateMachine.State.CRASHED -> "服务已崩溃"
        ShizukuStateMachine.State.STOPPED -> "未运行"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "服务状态",
            style = MaterialTheme.typography.headlineSmall
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = if (running) "Shizuku 服务已就绪，可授权应用使用。"
                    else "打开开关以 Root 或无线 ADB 方式启动服务。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "启动服务",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = running,
                        onCheckedChange = { wantOn ->
                            // 骨架阶段只实现"打开→拉起 StarterActivity"；
                            // 关闭（停止服务）链路后续再接。
                            if (wantOn && !running) {
                                val intent = Intent(context, StarterActivity::class.java).apply {
                                    // 与 StartRootViewHolder 相同的判定：Root 可用走 Root 流程，
                                    // 否则走默认无线 ADB 流程（不带 EXTRA_IS_ROOT）。
                                    if (EnvironmentUtils.isRooted()) {
                                        putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                                    }
                                }
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            }
        }

        Text(
            text = "激活模式：Root / 无线 ADB / Dhizuku",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
