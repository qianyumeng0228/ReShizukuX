package af.shizuku.manager.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.Button
import androidx.glance.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.analytics.SentryManager

/**
 * 组C - Glance 桌面小部件：显示 Shizuku 服务状态 + 启动按钮。
 *
 * 状态来源：SharedPreferences("reshizukux_beta1") 的 "service_state" 布尔值，
 * 集成阶段由状态机在服务启停时写入。
 *
 * 按钮：向 ${applicationId}.START 发送广播（复用已有 ManualStartReceiver）。
 */
class ShizukuWidget : GlanceAppWidget() {

    companion object {
        const val KEY_SERVICE_STATE = "service_state"
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val ctx = LocalContext.current
            val running = ctx.getSharedPreferences(SentryManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SERVICE_STATE, false)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .cornerRadius(16.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(if (running) "Shizuku 运行中" else "Shizuku 未运行")
                Button(
                    text = "启动",
                    onClick = actionSendBroadcast("${ctx.packageName}.START")
                )
            }
        }
    }
}
