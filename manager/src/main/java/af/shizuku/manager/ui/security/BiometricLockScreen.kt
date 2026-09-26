package af.shizuku.manager.ui.security

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
 * 生物识别锁设置页（ReShizukuX beta1 组B）。
 *
 *  - 开关：启用生物识别锁。
 *  - 启用时立即触发一次 BiometricPrompt 验证，验证通过才真正写入启用状态。
 *  - 显示支持的验证方式（指纹 / 面容 / 设备凭据）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricLockScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val manager = remember { BiometricLockManager.getInstance() }
    var enabled by remember { mutableStateOf(manager.isLockEnabled(context)) }
    var verifying by remember { mutableStateOf(false) }

    val available = manager.isAvailable(context)
    val methods = remember(context) { manager.availableMethods(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生物识别锁") },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(text = "启用生物识别锁", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "启动应用时需要指纹 / 面容 / 设备凭据验证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        enabled = available && !verifying,
                        onCheckedChange = { wantEnable ->
                            if (wantEnable) {
                                // 立即触发一次验证，通过后才真正写入启用状态。
                                val activity = context as? FragmentActivity
                                if (activity == null) {
                                    Toast.makeText(context, "当前环境不支持生物识别验证", Toast.LENGTH_SHORT).show()
                                    return@Switch
                                }
                                verifying = true
                                manager.authenticate(activity, object : BiometricLockManager.Callback {
                                    override fun onSuccess() {
                                        verifying = false
                                        manager.setLockEnabled(context, true)
                                        enabled = true
                                        Toast.makeText(context, "生物识别锁已启用", Toast.LENGTH_SHORT).show()
                                    }

                                    override fun onError(errorCode: Int, errString: CharSequence) {
                                        verifying = false
                                        Toast.makeText(context, "验证失败：$errString", Toast.LENGTH_SHORT).show()
                                    }

                                    override fun onFailed() {
                                        verifying = false
                                        Toast.makeText(context, "验证未通过", Toast.LENGTH_SHORT).show()
                                    }
                                })
                            } else {
                                manager.setLockEnabled(context, false)
                                enabled = false
                            }
                        }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "支持的验证方式",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!available) {
                        Text(
                            text = "当前设备未设置任何生物识别或设备凭据。请在系统设置中先录入指纹/面容或设置锁屏密码。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        methods.forEach { m ->
                            Text(
                                text = "· $m",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "开启后，每次启动 ReShizukuX 都需要完成一次生物识别或设备凭据验证才能进入主界面。验证方式由系统提供，应用不会存储任何生物特征数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
