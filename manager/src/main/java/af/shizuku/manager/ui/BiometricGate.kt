package af.shizuku.manager.ui

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import af.shizuku.manager.security.BiometricLockManager

/**
 * 生物识别锁拦截包装器（ReShizukuX beta1 组B）。
 *
 * 集成阶段在 PortableMainActivity.setContent 外层包裹：
 * ```
 * BiometricGate {
 *     PortableApp()
 * }
 * ```
 *
 *  - 若 [BiometricLockManager.isLockEnabled] 为 true，启动时弹出 BiometricPrompt。
 *  - 验证成功后显示 [content]；失败/错误时显示锁定界面（提供重试按钮）。
 *  - 若未启用，直接显示 [content]。
 *
 * 注意：androidx.biometric 1.1.0 的 BiometricPrompt 需要 FragmentActivity；
 * 集成阶段需将 PortableMainActivity 改为 FragmentActivity（或在调用处适配）。
 */
@Composable
fun BiometricGate(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { BiometricLockManager.getInstance() }

    val lockEnabled = remember(context) { manager.isLockEnabled(context) }
    // unlocked=true 时直接放行；未启用锁时默认 unlocked。
    var unlocked by remember { mutableStateOf(!lockEnabled) }
    var needsAuth by remember { mutableStateOf(lockEnabled) }

    if (!unlocked) {
        // 首次进入或重试时拉起系统 BiometricPrompt。
        LaunchedEffect(needsAuth) {
            if (needsAuth && !unlocked) {
                val activity = context as? FragmentActivity
                if (activity == null) {
                    // 宿主不是 FragmentActivity，无法弹出系统弹窗——直接放行避免卡死。
                    unlocked = true
                    return@LaunchedEffect
                }
                manager.authenticate(activity, object : BiometricLockManager.Callback {
                    override fun onSuccess() {
                        unlocked = true
                    }

                    override fun onError(errorCode: Int, errString: CharSequence) {
                        // 用户取消 / 系统错误：留在锁定界面，等用户点重试。
                    }

                    override fun onFailed() {
                        // 单次指纹未匹配，留在锁定界面。
                    }
                })
            }
        }

        LockedScreen(
            onRetry = { needsAuth = !needsAuth }
        )
    } else {
        content()
    }
}

@Composable
private fun LockedScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
            Text(
                text = "已锁定",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "请使用生物识别或设备凭据解锁",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Text("重新验证")
            }
        }
    }
}
