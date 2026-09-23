package af.shizuku.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 自动化 Tab（纯占位）：后续接入自动化规则与脚本。
 */
@Composable
fun AutomationTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "自动化",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "自动化规则与脚本（开发中）。",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
