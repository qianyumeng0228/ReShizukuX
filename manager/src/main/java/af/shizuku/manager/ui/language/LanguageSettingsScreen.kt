package af.shizuku.manager.ui.language

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import af.shizuku.manager.R

/**
 * 组C - 多语言设置页。
 *
 * 通过 [AppCompatDelegate.setApplicationLocales] 切换应用内语言，
 * 系统会自动重建 Activity 以应用新语言。
 */
private data class LanguageOption(val label: String, val tag: String)

private val LANGUAGES = listOf(
    LanguageOption("跟随系统", ""),
    LanguageOption("简体中文", "zh-rCN"),
    LanguageOption("English", "en"),
    LanguageOption("日本語", "ja"),
    LanguageOption("한국어", "ko"),
    LanguageOption("Português (Brasil)", "pt-rBR"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit) {
    // 当前应用内语言标签（空串 = 跟随系统）。
    var current by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().orEmpty())
    }
    // 语言名以自身语言显示（天然多语言），"跟随系统"走资源。
    val followSystemLabel = stringResource(R.string.rsx_language_follow_system)
    val options = LANGUAGES.map { if (it.tag.isEmpty()) it.copy(label = followSystemLabel) else it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rsx_language_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.rsx_back))
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
        ) {
            Text(
                stringResource(R.string.rsx_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            options.forEach { option ->
                val selected = current == option.tag
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            current = option.tag
                            val locales = if (option.tag.isEmpty()) {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(option.tag)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                            onBack()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null
                    )
                    Text(
                        option.label,
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
