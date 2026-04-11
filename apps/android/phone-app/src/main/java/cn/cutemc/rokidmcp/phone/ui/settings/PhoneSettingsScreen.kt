package cn.cutemc.rokidmcp.phone.ui.settings

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeSnapshot
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSettingsScreen(
    state: PhoneSettingsUiState,
    runState: GatewayRunState,
    runtimeSnapshot: PhoneRuntimeSnapshot,
    logs: List<PhoneLogEntry>,
    onDeviceIdChanged: (String) -> Unit,
    onAuthTokenChanged: (String) -> Unit,
    onRelayBaseUrlChanged: (String) -> Unit,
    onTargetDeviceAddressChanged: (String) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Phone Settings") })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.deviceId,
                    onValueChange = onDeviceIdChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device ID") },
                    isError = !state.canSave,
                )
            }
            item {
                OutlinedTextField(
                    value = state.authToken,
                    onValueChange = onAuthTokenChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Auth Token") },
                )
            }
            item {
                OutlinedTextField(
                    value = state.relayBaseUrl,
                    onValueChange = onRelayBaseUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Relay Base URL") },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = state.canSave) {
                        Text("Save")
                    }
                    state.saveMessage?.let { message ->
                        Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                OutlinedTextField(
                    value = state.targetDeviceAddress,
                    onValueChange = onTargetDeviceAddressChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target Device Address") },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = state.canStart) { Text("Start") }
                    Button(onClick = onStop) { Text("Stop") }
                }
            }
            item {
                Text("Status: $runState / ${runtimeSnapshot.runtimeState}")
                runtimeSnapshot.lastErrorCode?.let {
                    Text("Last Error: $it ${runtimeSnapshot.lastErrorMessage.orEmpty()}")
                }
            }

            item { HorizontalDivider() }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Logs", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onClearLogs, enabled = logs.isNotEmpty()) {
                        Text("Clear")
                    }
                }
            }
            if (logs.isEmpty()) {
                item { Text("No logs yet") }
            } else {
                items(items = logs, key = { entry -> entry.id }) { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${entry.level} ${entry.tag}", style = MaterialTheme.typography.labelLarge)
                        Text(entry.message, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
