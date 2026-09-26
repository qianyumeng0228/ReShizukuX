package af.shizuku.manager.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import af.shizuku.manager.R
import af.shizuku.manager.analytics.SentryManager
import af.shizuku.manager.ota.OtaUpdateManager
import af.shizuku.manager.theme.ThemeManager
import af.shizuku.manager.ui.accessibility.AccessibilityKeepaliveScreen
import af.shizuku.manager.ui.ai.AiAssistantScreen
import af.shizuku.manager.ui.analytics.SentrySettingsScreen
import af.shizuku.manager.ui.automation.AutomationScreen
import af.shizuku.manager.ui.backup.BackupRestoreScreen
import af.shizuku.manager.ui.language.LanguageSettingsScreen
import af.shizuku.manager.ui.lottie.LottieSettingsScreen
import af.shizuku.manager.ui.markdown.MarkdownScreen
import af.shizuku.manager.ui.ota.OtaUpdateScreen
import af.shizuku.manager.ui.security.BiometricLockScreen
import af.shizuku.manager.ui.theme.ThemeSettingsScreen
import af.shizuku.manager.ui.widget.WidgetSettingsScreen
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.repository.RepoManager

/**
 * ReShizukuX beta1 宿主 Activity。
 *
 * 底部 5 Tab：状态 / 授权 / 终端 / 模块 / 设置。
 * 设置 Tab 中的入口行导航到全屏页面（OTA/备份/AI/生物识别/Sentry/无障碍/主题/Lottie/Widget/语言/Markdown/自动化）。
 * 启动时受 BiometricGate 保护（如果用户启用了生物识别锁）。
 * 主题由 ThemeManager 驱动（跟随系统/浅色/深色 + 6 种强调色 + 动态颜色）。
 */
class PortableMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleManager.init(this)
        RepoManager.init(this)
        // beta1 回归功能初始化
        SentryManager.init(this)
        OtaUpdateManager.checkSilently(this)
        setContent {
            val context = LocalContext.current
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            val colorScheme = ThemeManager.getInstance().getColorScheme(context, isDark)
            MaterialTheme(colorScheme = colorScheme) {
                BiometricGate {
                    PortableApp()
                }
            }
        }
    }
}

private enum class PortableTab(
    val label: String,
    @DrawableRes val icon: Int
) {
    HOME("状态", R.drawable.ic_power_settings_new_24),
    APPS("授权", R.drawable.ic_group_24),
    TERMINAL("终端", R.drawable.ic_code_24),
    MODULES("模块", R.drawable.ic_install_24),
    SETTINGS("设置", R.drawable.ic_settings_outline_24);
}

@Composable
private fun PortableApp() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showPairingWizard by rememberSaveable { mutableStateOf(false) }
    // 全屏页面导航：null = 显示 Tab，非 null = 显示对应全屏页
    var fullScreen by rememberSaveable { mutableStateOf<String?>(null) }

    // 系统返回键拦截：二级全屏页/配对向导按返回应回到上一级，而不是退出应用
    BackHandler(enabled = showPairingWizard) { showPairingWizard = false }
    BackHandler(enabled = fullScreen != null) { fullScreen = null }

    if (showPairingWizard) {
        AdbPairingWizard(
            onFinished = { showPairingWizard = false },
            onCancel = { showPairingWizard = false }
        )
        return
    }

    // 全屏页面渲染
    if (fullScreen != null) {
        val onBack = { fullScreen = null }
        when (fullScreen) {
            "ota" -> OtaUpdateScreen(onBack = onBack)
            "backup" -> BackupRestoreScreen(onBack = onBack)
            "ai" -> AiAssistantScreen(onBack = onBack)
            "biometric" -> BiometricLockScreen(onBack = onBack)
            "sentry" -> SentrySettingsScreen(onBack = onBack)
            "accessibility" -> AccessibilityKeepaliveScreen(onBack = onBack)
            "theme" -> ThemeSettingsScreen(onBack = onBack)
            "lottie" -> LottieSettingsScreen(onBack = onBack)
            "widget" -> WidgetSettingsScreen(onBack = onBack)
            "language" -> LanguageSettingsScreen(onBack = onBack)
            "changelog" -> MarkdownScreen.Content(onBack = onBack)
            "automation" -> AutomationScreen(onBack = onBack)
            "old_settings" -> {
                // 启动旧版 SettingsActivity
                val ctx = LocalContext.current
                ctx.startActivity(android.content.Intent(ctx, af.shizuku.manager.settings.SettingsActivity::class.java))
                fullScreen = null
            }
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                PortableTab.values().forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                painter = painterResource(tab.icon),
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selected) {
                0 -> HomeTab(onPairingRequired = { showPairingWizard = true })
                1 -> AppsTab()
                2 -> TerminalTab()
                3 -> ModulesTab()
                else -> SettingsTab(onNavigate = { route -> fullScreen = route })
            }
        }
    }
}
