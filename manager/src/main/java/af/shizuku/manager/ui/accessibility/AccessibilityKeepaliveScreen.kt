package af.shizuku.manager.ui.accessibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.service.AccessibilityKeepaliveService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 无障碍保活设置页（ReShizukuX beta1 组B）。
 *
 *  - 显示服务状态（已启用 / 未启用）。
 *  - 「开启无障碍保活」按钮跳系统无障碍设置。
 *  - onResume 时刷新状态（用户从系统设置返回后自动更新）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityKeepaliveScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AccessibilityKeepaliveService.isEnabled(context)) }

    // onResume 时重新读取状态（用户在系统设置中开关后返回本页）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = AccessibilityKeepaliveService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("无障碍保活") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "服务状态",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (enabled) "已启用" else "未启用",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }

            Button(
                onClick = { AccessibilityKeepaliveService.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (enabled) "前往无障碍设置" else "开启无障碍保活")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "关于无障碍保活",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "开启后，ReShizukuX 会作为无障碍服务在后台持续运行，提升 Shizuku 服务在国产 ROM 上的存活率。" +
                            "本服务不读取、不收集、不上报任何窗口内容或用户数据，仅用于保活。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
