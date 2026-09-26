package af.shizuku.manager.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 桌面小部件设置页（ReShizukuX beta1 组C）。
 *
 * 引导用户将 Shizuku 状态小部件添加到桌面。
 * Glance 小部件实现见 [ShizukuWidget] / [ShizukuWidgetReceiver]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面小部件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shizuku 状态小部件", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在桌面显示 Shizuku 服务运行状态，点击可快速启动服务。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("添加方法", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. 长按桌面空白处\n" +
                        "2. 选择「小部件」或「Widgets」\n" +
                        "3. 找到 ReShizukuX\n" +
                        "4. 拖拽「Shizuku 状态」小部件到桌面",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("小部件功能", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "• 实时显示服务状态（运行中 / 未运行）\n" +
                        "• 点击「启动」按钮快速拉起 Shizuku 服务\n" +
                        "• 每 30 分钟自动刷新状态",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
