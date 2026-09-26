package af.shizuku.manager.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.res.painterResource
import af.shizuku.manager.R
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.repository.RepoManager

/**
 * ShizukuX Portable 的 Compose 宿主 Activity。
 *
 * 与现有 View 体系（MainActivity / Fragment / RecyclerView）共存：这里是 Compose 五 Tab
 * 骨架入口（状态 / 授权 / 终端 / 模块 / 设置），不删除、不改造任何既有页面。
 *
 * P6（ReShizukuX UI 整合）：
 *  - 底部导航从「状态/授权/终端/自动化/设置」调整为「状态/授权/终端/模块/设置」。
 *  - 「自动化」不再作为底部 Tab（设计方案 §4.3：手机端 5 Tab 最佳，自动化规则本质是内置模块）；
 *    AutomationTab.kt 文件保留，可从模块 Tab 内的「自动化规则」入口启动 AppProfilesActivity。
 *  - 模块系统（:modules）在主进程首次使用前初始化（ModuleManager / RepoManager）。
 */
class PortableMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // P6：模块系统在主进程初始化（:daemon 进程的 ShizukuDaemonService 也会各自 init，幂等）。
        // RepoManager.init 内部会 ensureDefaultRepo()，首次启动注入官方仓库。
        ModuleManager.init(this)
        RepoManager.init(this)
        setContent {
            // 骨架阶段使用默认 MaterialTheme，不引入自定义主题系统。
            MaterialTheme {
                PortableApp()
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
    // When true, the full-screen ADB pairing wizard replaces the five-tab scaffold.
    var showPairingWizard by rememberSaveable { mutableStateOf(false) }

    if (showPairingWizard) {
        AdbPairingWizard(
            onFinished = { showPairingWizard = false },
            onCancel = { showPairingWizard = false }
        )
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
                else -> SettingsTab()
            }
        }
    }
}
