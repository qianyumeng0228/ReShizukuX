package af.shizuku.manager.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import af.shizuku.manager.management.ApplicationManagementActivity

/**
 * 授权 Tab（占位）：不重新实现授权列表，直接跳转到现有
 * [ApplicationManagementActivity]。
 */
@Composable
fun AppsTab() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "授权管理",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "管理已授权应用。",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = {
            context.startActivity(Intent(context, ApplicationManagementActivity::class.java))
        }) {
            Text("打开授权管理")
        }
    }
}
