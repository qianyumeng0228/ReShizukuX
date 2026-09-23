package af.shizuku.manager.home.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.ActivityLogRecord
import af.shizuku.manager.utils.AppIconCache
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.*

@Composable
fun ServerMetricsScreen() {
    val context = LocalContext.current
    var uptimeText by remember { mutableStateOf("00:00:00") }
    var clientCountText by remember { mutableStateOf(context.getString(R.string.server_clients_connected, 0)) }
    var memoryProgress by remember { mutableFloatStateOf(0f) }
    var memoryDetails by remember { mutableStateOf("0 MB / 0 MB") }

    LaunchedEffect(Unit) {
        // Server runtime metrics were previously sourced from the removed AICore+
        // diagnostic bridge. The cards retain their default values until an
        // alternative metrics source is wired in.
        while (true) {
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MetricCard(title = stringResource(R.string.server_uptime), value = uptimeText)
        MetricCard(title = stringResource(R.string.active_connections), value = clientCountText)
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(stringResource(R.string.server_memory_usage), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { memoryProgress },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(memoryDetails, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
fun ActivityLogScreen() {
    val logs by ActivityLogManager.logs.collectAsState(initial = emptyList())
    // Include the date, not just the time - logs persist across days (matches the RecyclerView
    // implementation of this same screen in ActivityLogActivity.kt).
    val dateFormat = remember {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM, Locale.getDefault())
    }

    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_empty_log_24), contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.empty_state_title_activity_log_empty), style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs, key = { it.timestamp.toString() + it.packageName }) { record ->
                ActivityLogRow(record, dateFormat)
            }
        }
    }
}

@Composable
private fun ActivityLogRow(record: ActivityLogRecord, dateFormat: java.text.DateFormat) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var appName by remember(record.packageName) { mutableStateOf(record.appName.ifEmpty { record.packageName }) }
    var iconBitmap by remember(record.packageName) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }

    // pm.getApplicationInfo() is a real IPC and loadIcon() a synchronous decode - doing both inline
    // during composition (as this screen used to) blocks the UI thread on every scroll/recompose,
    // and handing an already-decoded Drawable to AsyncImage defeated its own caching pipeline.
    // AppIconCache (used by the RecyclerView version of this same screen) is already cached+async.
    LaunchedEffect(record.packageName) {
        val pm = context.packageManager
        val sizePx = with(density) { 40.dp.roundToPx() }
        val ai = withContext(Dispatchers.IO) {
            try { pm.getApplicationInfo(record.packageName, 0) } catch (e: Exception) { null }
        } ?: return@LaunchedEffect
        appName = AppIconCache.getLabel(context, ai)
        val bitmap = withContext(Dispatchers.IO) {
            AppIconCache.getOrLoadBitmap(context, ai, ai.uid / 100000, sizePx)
        }
        if (bitmap != null) iconBitmap = bitmap.asImageBitmap()
    }

    // mergeDescendants: without this, TalkBack stops on each child (icon/name/package/timestamp)
    // separately when swiping through a long log list - one stop per row instead of four.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = iconBitmap
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Icon(painterResource(R.drawable.ic_system_icon), contentDescription = null, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(appName, style = MaterialTheme.typography.titleMedium)
            Text(record.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(record.action, style = MaterialTheme.typography.bodyMedium)
        }
        Text(dateFormat.format(Date(record.timestamp)), style = MaterialTheme.typography.labelSmall)
    }
}
