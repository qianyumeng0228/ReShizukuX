package af.shizuku.manager.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍保活服务（ReShizukuX beta1 组B）。
 *
 * 核心作用：作为一个「空」的无障碍服务保持本进程在后台活跃。国产 ROM 对无障碍服务的
 * 后台限制相对宽松，借此提升 Shizuku 服务在后台被杀后的存活率。
 *
 * 隐私承诺：本服务不抓取、不读取、不上报任何窗口内容；eventTypes 仅注册
 * [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]（最小化），[onAccessibilityEvent] 为空实现。
 *
 * 配置见 res/xml/accessibility_keepalive_config.xml。
 * 集成阶段需在 AndroidManifest.xml 中注册本 service（带 BIND_ACCESSIBILITY_SERVICE intent-filter）。
 */
class AccessibilityKeepaliveService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 保活用，不处理任何事件。
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    companion object {
        /**
         * 检查本无障碍服务是否已在系统设置中启用。
         *
         * 通过读取 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 并匹配本服务的
         * ComponentName 扁平化名称（packageName/className）判定。
         */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${AccessibilityKeepaliveService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val name = splitter.next().trim()
                if (name.equals(expected, ignoreCase = true)) {
                    return true
                }
                // 部分 ROM 会写简写类名（.service.AccessibilityKeepaliveService），兼容一下。
                val shorthand = "${context.packageName}/.service.AccessibilityKeepaliveService"
                if (name.equals(shorthand, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /** 跳转系统无障碍设置页，让用户手动开启本服务。 */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
