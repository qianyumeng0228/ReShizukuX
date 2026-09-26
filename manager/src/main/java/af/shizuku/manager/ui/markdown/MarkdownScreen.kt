package af.shizuku.manager.ui.markdown

import android.content.Context
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

/**
 * 组C - Markdown 渲染页（更新日志）。
 *
 * - 使用 markwon 4.6.2 渲染。
 * - [renderMarkdown] 为静态方法，集成阶段可在任意页面复用。
 */
object MarkdownScreen {

    /** 集成阶段可复用：把 Markdown 文本渲染为带样式的 CharSequence。 */
    fun renderMarkdown(context: Context, markdown: String): CharSequence {
        return Markwon.create(context).toMarkdown(markdown)
    }

    private val CHANGELOG = """
        # ReShizukuX v14.0.0-beta1 更新日志

        **首个回归测试版本**，完成基础设施与功能组 A/B/C 的整合。

        ## 新功能

        1. **Sentry 崩溃上报** — 匿名上报崩溃堆栈，支持手动开关与测试崩溃。
        2. **Lottie 动画** — 加载动画可开关，支持 0.5x / 1x / 1.5x 速度调节。
        3. **Glance 桌面小部件** — 桌面一键查看服务状态并启动 Shizuku。
        4. **Markdown 更新日志** — 使用 markwon 渲染富文本更新说明。
        5. **多语言扩展** — 跟随系统 / 简中 / 英语 / 日语 / 韩语 / 巴西葡语。
        6. **模块系统重构** — RuntimeModuleService 前台服务保活。
        7. **ADB 配对向导** — 分步引导无线调试配对流程。
        8. **自动化规则引擎** — 支持 locale 等条件与动作插件。
        9. **系统健康面板** — 服务指标与诊断信息可视化。
        10. **应用管理优化** — 授权应用列表与批量切换。
        11. **网络状态监听** — Wi-Fi 调试断连后自动重断言。
        12. **崩溃去重上报** — SentryEventDeduper 避免重复堆栈刷屏。

        > 感谢所有参与测试与翻译贡献者。发现问题请通过崩溃上报或 Issue 反馈。
        """.trimIndent()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(onBack: () -> Unit) {
        val context = LocalContext.current
        val spanned = remember { renderMarkdown(context, CHANGELOG) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("更新日志") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    "v14.0.0-beta1",
                    style = MaterialTheme.typography.titleLarge
                )
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    factory = { ctx ->
                        TextView(ctx).apply {
                            textSize = 14f
                        }
                    },
                    update = { it.text = spanned }
                )
            }
        }
    }
}
