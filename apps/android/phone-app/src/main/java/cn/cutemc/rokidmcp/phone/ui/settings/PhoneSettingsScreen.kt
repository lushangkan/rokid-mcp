package cn.cutemc.rokidmcp.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
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
    onReconnectDelayMsChanged: (String) -> Unit,
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
                    isError = !PhoneLocalConfig.isValidDeviceId(state.deviceId),
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
                OutlinedTextField(
                    value = state.targetDeviceAddress,
                    onValueChange = onTargetDeviceAddressChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target Device Address") },
                    isError = !isValidTargetDeviceAddress(state.targetDeviceAddress),
                    supportingText = {
                        if (!isValidTargetDeviceAddress(state.targetDeviceAddress)) {
                            Text("Use format AA:BB:CC:DD:EE:FF")
                        }
                    },
                )
            }
            item {
                OutlinedTextField(
                    value = state.reconnectDelayMs,
                    onValueChange = onReconnectDelayMsChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reconnect Delay (ms)") },
                    isError = !isValidReconnectDelayMs(state.reconnectDelayMs),
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = state.canStart) { Text("Start") }
                    Button(onClick = onStop, enabled = isStopActionEnabled(runState)) { Text("Stop") }
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
            item {
                PhoneLogPanel(
                    logs = logs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PhoneLogPanel(
    logs: List<PhoneLogEntry>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val logText = remember(logs) { buildPhoneLogText(logs) }

    LaunchedEffect(logText) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    // Keep the log viewport to a single scrollable text surface so large log volumes
    // do not create one composable subtree per entry inside the settings list.
    Surface(
        modifier = modifier.height(240.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = logText,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
