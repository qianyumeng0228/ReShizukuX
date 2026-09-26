package af.shizuku.manager.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.ai.AiClient
import af.shizuku.manager.ai.AiSettings
import af.shizuku.manager.ai.AiSettings.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 助手全屏页面（ReShizukuX beta1 组A）：脚本追踪与解释。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf(AiSettings.getHistory(context)) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var apiKey by remember { mutableStateOf(AiSettings.getApiKey(context)) }
    var endpoint by remember { mutableStateOf(AiSettings.getEndpoint(context)) }
    var model by remember { mutableStateOf(AiSettings.getModel(context)) }

    val listState = rememberLazyListState()
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
    }

    fun send() {
        val q = input.trim()
        if (q.isBlank() || sending) return
        if (!AiSettings.hasApiKey(context)) {
            showSettings = true
            return
        }
        input = ""
        sending = true
        scope.launch {
            val userItem = HistoryItem("user", q, System.currentTimeMillis())
            history = history + userItem
            AiSettings.appendHistory(context, userItem)

            val result = AiClient.explain(
                context,
                AiClient.AiRequest(command = q, context = context.getString(R.string.rsx_ai_shell_env))
            )
            sending = false
            val reply = result.getOrElse { context.getString(R.string.rsx_ai_request_failed, it.message) }
            val aiItem = HistoryItem("ai", reply, System.currentTimeMillis())
            history = history + aiItem
            AiSettings.appendHistory(context, aiItem)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.rsx_ai_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(af.shizuku.manager.R.drawable.ic_back_24), contentDescription = stringResource(R.string.rsx_back))
                }
            },
            actions = {
                TextButton(onClick = { showSettings = true }) { Text(stringResource(R.string.rsx_ai_settings)) }
                TextButton(onClick = {
                    AiSettings.clearHistory(context)
                    history = emptyList()
                }) { Text(stringResource(R.string.rsx_ai_clear)) }
            }
        )

        if (!AiSettings.hasApiKey(context)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rsx_ai_no_key), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.rsx_ai_need_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { showSettings = true }) { Text(stringResource(R.string.rsx_ai_configure)) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history) { item -> ChatBubble(item) }
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                        Text(stringResource(R.string.rsx_ai_thinking), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.rsx_ai_placeholder)) },
                maxLines = 3
            )
            Button(onClick = { send() }, enabled = !sending) { Text(stringResource(R.string.rsx_ai_send)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                // 占位：后续接入 TerminalTab 当前命令追踪
                input = input // no-op
            }) { Text(stringResource(R.string.rsx_ai_track)) }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.rsx_ai_service_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.rsx_ai_api_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(stringResource(R.string.rsx_ai_endpoint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringResource(R.string.rsx_ai_model)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AiSettings.setApiKey(context, apiKey.trim())
                    AiSettings.setEndpoint(context, endpoint.trim().ifBlank { AiSettings.DEFAULT_ENDPOINT })
                    AiSettings.setModel(context, model.trim().ifBlank { AiSettings.DEFAULT_MODEL })
                    showSettings = false
                }) { Text(stringResource(R.string.rsx_ai_save)) }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.rsx_cancel)) } }
        )
    }
}

@Composable
private fun ChatBubble(item: HistoryItem) {
    val isUser = item.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = item.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
