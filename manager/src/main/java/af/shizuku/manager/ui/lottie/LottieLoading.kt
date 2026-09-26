package af.shizuku.manager.ui.lottie

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * 组C - Lottie 可复用加载动画。
 *
 * - 用户在 LottieSettingsScreen 关闭动画时，回退为系统 [CircularProgressIndicator]。
 * - 集成阶段可用于启动页 / 模块安装等待等场景。
 */
object LottiePrefs {
    const val PREFS_NAME = "reshizukux_beta1"
    const val KEY_ENABLED = "lottie_enabled"
    const val KEY_SPEED = "lottie_speed"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1f)

    fun setSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, speed).apply()
    }
}

@Composable
fun LottieLoading(
    modifier: Modifier = Modifier,
    speed: Float = LottiePrefs.getSpeed(LocalContext.current)
) {
    val context = LocalContext.current
    val enabled = remember { LottiePrefs.isEnabled(context) }

    if (!enabled) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading_animation))
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = speed,
        modifier = modifier.size(72.dp)
    )
}
