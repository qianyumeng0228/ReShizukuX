package af.shizuku.manager.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 组C - Glance AppWidgetReceiver。
 *
 * 注意：Manifest 中已有旧版 .widget.ShizukuWidgetProvider（AppWidgetProvider）。
 * 本类名与旧 Provider 不同，集成阶段在 Manifest 中注册本 Receiver（或与旧版并存）。
 */
class ShizukuWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShizukuWidget()
}
