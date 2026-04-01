package cn.cutemc.rokidmcp.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsScreen
import cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsViewModel
import cn.cutemc.rokidmcp.phone.ui.theme.RokidMCPPhoneTheme

class MainActivity : ComponentActivity() {
    private val phoneApp: PhoneApp
        get() = application as PhoneApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RokidMCPPhoneTheme {
                val viewModel = remember {
                    PhoneSettingsViewModel(
                        controller = phoneApp.appController,
                        localConfigStore = phoneApp.localConfigStore,
                    )
                }
                DisposableEffect(Unit) {
                    onDispose { viewModel.close() }
                }
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val runState by viewModel.runState.collectAsStateWithLifecycle()
                val runtimeSnapshot by viewModel.runtimeSnapshot.collectAsStateWithLifecycle()
                val logs by viewModel.logs.collectAsStateWithLifecycle()

                PhoneSettingsScreen(
                    state = uiState,
                    runState = runState,
                    runtimeSnapshot = runtimeSnapshot,
                    logs = logs,
                    onDeviceIdChanged = viewModel::onDeviceIdChanged,
                    onAuthTokenChanged = viewModel::onAuthTokenChanged,
                    onRelayBaseUrlChanged = viewModel::onRelayBaseUrlChanged,
                    onTargetDeviceAddressChanged = viewModel::onTargetDeviceAddressChanged,
                    onSave = viewModel::save,
                    onStart = viewModel::startGateway,
                    onStop = viewModel::stopGateway,
                    onClearLogs = viewModel::clearLogs,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
