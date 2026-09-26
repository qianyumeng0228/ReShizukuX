package af.shizuku.manager.ui.lottie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import af.shizuku.manager.R

/**
 * 组C - Lottie 动画设置页：开关 + 实时预览 + 速度调节。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LottieSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LottiePrefs.isEnabled(context)) }
    var speed by remember { mutableFloatStateOf(LottiePrefs.getSpeed(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("动画效果") },
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
            Card {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用动画效果", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "开启动画可提升视觉体验，关闭可节省性能。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            LottiePrefs.setEnabled(context, it)
                        }
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))

            // 预览
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("动画预览", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    if (enabled) {
                        val composition by rememberLottieComposition(
                            LottieCompositionSpec.RawRes(R.raw.loading_animation)
                        )
                        LottieAnimation(
                            composition = composition,
                            iterations = LottieConstants.IterateForever,
                            speed = speed,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Text("动画已关闭", style = MaterialTheme.typography.bodyMedium)
                    }

                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(0.5f to "0.5x", 1f to "1x", 1.5f to "1.5x").forEach { (value, label) ->
                            FilterChip(
                                selected = speed == value,
                                onClick = {
                                    speed = value
                                    LottiePrefs.setSpeed(context, value)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }
    }
}
