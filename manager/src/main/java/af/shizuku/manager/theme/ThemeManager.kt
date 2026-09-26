package af.shizuku.manager.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 主题个性化管理器（ReShizukuX beta1 组B）。
 *
 *  - 主题模式：跟随系统 / 浅色 / 深色（SharedPreferences "theme_mode"，0/1/2）。
 *  - 强调色：6 种预设（teal / purple / blue / green / orange / red），每种 light/dark 各一套 ColorScheme。
 *  - 动态颜色开关（Android 12+，使用系统动态配色）。
 *
 * 不引入 materialthemebuilder 新配置；配色直接用 Material3 ColorScheme 手写。
 */
class ThemeManager private constructor() {

    private val prefsName = "reshizukux_theme"
    private val keyMode = "theme_mode"
    private val keyAccent = "theme_accent"
    private val keyDynamic = "theme_dynamic"

    enum class ThemeMode(val value: Int, val label: String) {
        SYSTEM(0, "跟随系统"),
        LIGHT(1, "浅色"),
        DARK(2, "深色");

        companion object {
            fun fromValue(v: Int): ThemeMode = entries.firstOrNull { it.value == v } ?: SYSTEM
        }
    }

    enum class Accent(
        val id: String,
        val label: String,
        val swatch: Color,
        val light: ColorScheme,
        val dark: ColorScheme
    ) {
        TEAL(
            id = "teal",
            label = "青",
            swatch = Color(0xFF00A9A5),
            light = lightColorScheme(
                primary = Color(0xFF006A6A),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFF6FF7F6),
                onPrimaryContainer = Color(0xFF002020),
                secondary = Color(0xFF4A6363),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFCCE8E7),
                onSecondaryContainer = Color(0xFF051F1F)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF4FDAD9),
                onPrimary = Color(0xFF003737),
                primaryContainer = Color(0xFF004F4F),
                onPrimaryContainer = Color(0xFF6FF7F6),
                secondary = Color(0xFFB0CCCB),
                onSecondary = Color(0xFF1B3434),
                secondaryContainer = Color(0xFF324B4A),
                onSecondaryContainer = Color(0xFFCCE8E7)
            )
        ),
        PURPLE(
            id = "purple",
            label = "紫",
            swatch = Color(0xFF6750A4),
            light = lightColorScheme(
                primary = Color(0xFF6750A4),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFEADDFF),
                onPrimaryContainer = Color(0xFF21005D),
                secondary = Color(0xFF625B71),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFE8DEF8),
                onSecondaryContainer = Color(0xFF1D192B)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFD0BCFF),
                onPrimary = Color(0xFF381E72),
                primaryContainer = Color(0xFF4F378B),
                onPrimaryContainer = Color(0xFFEADDFF),
                secondary = Color(0xFFC9BEE0),
                onSecondary = Color(0xFF332D41),
                secondaryContainer = Color(0xFF4A4458),
                onSecondaryContainer = Color(0xFFE8DEF8)
            )
        ),
        BLUE(
            id = "blue",
            label = "蓝",
            swatch = Color(0xFF0061A4),
            light = lightColorScheme(
                primary = Color(0xFF0061A4),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFD1E4FF),
                onPrimaryContainer = Color(0xFF001D36),
                secondary = Color(0xFF535F70),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFD7E3F7),
                onSecondaryContainer = Color(0xFF101C2B)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF9ECAFF),
                onPrimary = Color(0xFF003258),
                primaryContainer = Color(0xFF00497D),
                onPrimaryContainer = Color(0xFFD1E4FF),
                secondary = Color(0xFFBBC7DB),
                onSecondary = Color(0xFF253140),
                secondaryContainer = Color(0xFF3B4858),
                onSecondaryContainer = Color(0xFFD7E3F7)
            )
        ),
        GREEN(
            id = "green",
            label = "绿",
            swatch = Color(0xFF2E7D32),
            light = lightColorScheme(
                primary = Color(0xFF2E6B2F),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFB2F0A9),
                onPrimaryContainer = Color(0xFF0B2010),
                secondary = Color(0xFF52634F),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFD5E8CF),
                onSecondaryContainer = Color(0xFF101F10)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF95DA92),
                onPrimary = Color(0xFF113816),
                primaryContainer = Color(0xFF1E5221),
                onPrimaryContainer = Color(0xFFB2F0A9),
                secondary = Color(0xFFB9CCB2),
                onSecondary = Color(0xFF243424),
                secondaryContainer = Color(0xFF3A4B39),
                onSecondaryContainer = Color(0xFFD5E8CF)
            )
        ),
        ORANGE(
            id = "orange",
            label = "橙",
            swatch = Color(0xFFE07B00),
            light = lightColorScheme(
                primary = Color(0xFF8C4A00),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFFFDDB6),
                onPrimaryContainer = Color(0xFF2D1600),
                secondary = Color(0xFF725A42),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFFFDDBA),
                onSecondaryContainer = Color(0xFF291806)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFFFB675),
                onPrimary = Color(0xFF4A2500),
                primaryContainer = Color(0xFF6A3700),
                onPrimaryContainer = Color(0xFFFFDDB6),
                secondary = Color(0xFFE0BE92),
                onSecondary = Color(0xFF412D16),
                secondaryContainer = Color(0xFF59442B),
                onSecondaryContainer = Color(0xFFFFDDBA)
            )
        ),
        RED(
            id = "red",
            label = "红",
            swatch = Color(0xFFBA1A1A),
            light = lightColorScheme(
                primary = Color(0xFFBA1A1A),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFFFDAD6),
                onPrimaryContainer = Color(0xFF410002),
                secondary = Color(0xFF775652),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFFFDAD4),
                onSecondaryContainer = Color(0xFF2C1512)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFFFB4AB),
                onPrimary = Color(0xFF690005),
                primaryContainer = Color(0xFF93000A),
                onPrimaryContainer = Color(0xFFFFDAD6),
                secondary = Color(0xFFE7BDB6),
                onSecondary = Color(0xFF442925),
                secondaryContainer = Color(0xFF5D3F39),
                onSecondaryContainer = Color(0xFFFFDAD4)
            )
        );

        companion object {
            fun fromId(id: String?): Accent = entries.firstOrNull { it.id == id } ?: TEAL
        }
    }

    fun getThemeMode(context: Context): ThemeMode =
        ThemeMode.fromValue(prefs(context).getInt(keyMode, ThemeMode.SYSTEM.value))

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putInt(keyMode, mode.value).apply()
    }

    fun getAccent(context: Context): Accent =
        Accent.fromId(prefs(context).getString(keyAccent, null))

    fun setAccent(context: Context, accent: Accent) {
        prefs(context).edit().putString(keyAccent, accent.id).apply()
    }

    fun isDynamicColorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(keyDynamic, false)

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(keyDynamic, enabled).apply()
    }

    fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 根据当前设置返回 ColorScheme。
     *
     * @param dark 是否深色（由调用方根据系统配置 / 用户选择决定）。
     */
    fun getColorScheme(context: Context, dark: Boolean): ColorScheme {
        val dynamic = isDynamicColorEnabled(context) && isDynamicColorAvailable()
        if (dynamic) {
            return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        val accent = getAccent(context)
        return if (dark) accent.dark else accent.light
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var INSTANCE: ThemeManager? = null

        fun getInstance(): ThemeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThemeManager().also { INSTANCE = it }
            }
    }
}
