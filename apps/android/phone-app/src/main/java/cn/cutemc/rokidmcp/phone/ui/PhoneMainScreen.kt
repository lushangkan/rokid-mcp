package cn.cutemc.rokidmcp.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneMainScreen(
    logs: List<PhoneLogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Phone Logs") },
                actions = {
                    Button(
                        onClick = onClearLogs,
                        enabled = logs.isNotEmpty(),
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text("Clear")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No logs yet",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            items(items = logs, key = { entry -> "${entry.timestampMs}-${entry.tag}-${entry.message}" }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${entry.level} ${entry.tag}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = entry.timestampMs.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    entry.throwableSummary?.let { throwableSummary ->
                        Text(
                            text = throwableSummary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
