package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.gateway.PhoneAppController
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeSnapshot
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhoneSettingsUiState(
    val deviceId: String = "",
    val authToken: String = "",
    val relayBaseUrl: String = "",
    val targetDeviceAddress: String = "00:11:22:33:44:55",
    val canSave: Boolean = false,
    val saveMessage: String? = null,
)

class PhoneSettingsViewModel(
    private val controller: PhoneAppController,
    private val localConfigStore: PhoneLocalConfigStore,
    scope: CoroutineScope? = null,
) {
    private val ownedScope = scope == null
    private val coroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(PhoneSettingsUiState())
    val uiState: StateFlow<PhoneSettingsUiState> = _uiState.asStateFlow()

    val runState: StateFlow<GatewayRunState> = controller.runState
    val runtimeSnapshot: StateFlow<PhoneRuntimeSnapshot> = controller.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = controller.logs

    init {
        val config = localConfigStore.load()
        _uiState.value = PhoneSettingsUiState(
            deviceId = config.deviceId,
            authToken = config.authToken.orEmpty(),
            relayBaseUrl = config.relayBaseUrl.orEmpty(),
            targetDeviceAddress = _uiState.value.targetDeviceAddress,
            canSave = PhoneLocalConfig.isValidDeviceId(config.deviceId),
        )
    }

    fun onDeviceIdChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            deviceId = value,
            canSave = PhoneLocalConfig.isValidDeviceId(value),
            saveMessage = null,
        )
    }

    fun onAuthTokenChanged(value: String) {
        _uiState.value = _uiState.value.copy(authToken = value, saveMessage = null)
    }

    fun onRelayBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(relayBaseUrl = value, saveMessage = null)
    }

    fun onTargetDeviceAddressChanged(value: String) {
        _uiState.value = _uiState.value.copy(targetDeviceAddress = value)
    }

    fun save(): Boolean {
        val state = _uiState.value
        if (!state.canSave) {
            return false
        }

        localConfigStore.save(
            PhoneLocalConfig(
                deviceId = state.deviceId,
                authToken = state.authToken.ifBlank { null },
                relayBaseUrl = state.relayBaseUrl.ifBlank { null },
            ),
        )
        _uiState.value = state.copy(saveMessage = "Saved")
        return true
    }

    fun startGateway() {
        coroutineScope.launch {
            controller.start(targetDeviceAddress = _uiState.value.targetDeviceAddress)
        }
    }

    fun stopGateway() {
        coroutineScope.launch {
            controller.stop(reason = "manual")
        }
    }

    fun clearLogs() {
        controller.clearLogs()
    }

    fun close() {
        if (ownedScope) {
            coroutineScope.cancel()
        }
    }
}
