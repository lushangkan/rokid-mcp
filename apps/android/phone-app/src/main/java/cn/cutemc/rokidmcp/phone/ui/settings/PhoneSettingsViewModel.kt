package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.gateway.PhoneAppController
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeSnapshot
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhoneSettingsUiState(
    val deviceId: String = "",
    val authToken: String = "",
    val relayBaseUrl: String = "",
    val targetDeviceAddress: String = "00:11:22:33:44:55",
    val canSave: Boolean = false,
    val canStart: Boolean = false,
    val saveMessage: String? = null,
)

class PhoneSettingsViewModel(
    private val controller: PhoneAppController,
    private val localConfigStore: PhoneLocalConfigStore,
    scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appVersion: String = "1.0",
) {
    private val ownedScope = scope == null
    private val coroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(PhoneSettingsUiState())
    val uiState: StateFlow<PhoneSettingsUiState> = _uiState.asStateFlow()

    val runState: StateFlow<GatewayRunState> = controller.runState
    val runtimeSnapshot: StateFlow<PhoneRuntimeSnapshot> = controller.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = controller.logs

    init {
        coroutineScope.launch {
            val config = withContext(ioDispatcher) { localConfigStore.load() }
            _uiState.value = PhoneSettingsUiState(
                deviceId = config.deviceId,
                authToken = config.authToken.orEmpty(),
                relayBaseUrl = config.relayBaseUrl.orEmpty(),
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
                canSave = PhoneLocalConfig.isValidDeviceId(config.deviceId),
                canStart = isStartEligible(
                    deviceId = config.deviceId,
                    authToken = config.authToken.orEmpty(),
                    relayBaseUrl = config.relayBaseUrl.orEmpty(),
                    runState = runState.value,
                ),
            )
        }

        coroutineScope.launch {
            runState.collectLatest {
                refreshStartEligibility()
            }
        }
    }

    fun onDeviceIdChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            deviceId = value,
            canSave = PhoneLocalConfig.isValidDeviceId(value),
            canStart = isStartEligible(
                deviceId = value,
                authToken = _uiState.value.authToken,
                relayBaseUrl = _uiState.value.relayBaseUrl,
                runState = runState.value,
            ),
            saveMessage = null,
        )
    }

    fun onAuthTokenChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            authToken = value,
            canStart = isStartEligible(
                deviceId = _uiState.value.deviceId,
                authToken = value,
                relayBaseUrl = _uiState.value.relayBaseUrl,
                runState = runState.value,
            ),
            saveMessage = null,
        )
    }

    fun onRelayBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            relayBaseUrl = value,
            canStart = isStartEligible(
                deviceId = _uiState.value.deviceId,
                authToken = _uiState.value.authToken,
                relayBaseUrl = value,
                runState = runState.value,
            ),
            saveMessage = null,
        )
    }

    fun onTargetDeviceAddressChanged(value: String) {
        _uiState.value = _uiState.value.copy(targetDeviceAddress = value)
    }

    fun save(): Boolean {
        val state = _uiState.value
        if (!state.canSave) {
            return false
        }

        coroutineScope.launch {
            val saveResult = runCatching {
                withContext(ioDispatcher) {
                    persistCurrentConfig(state)
                }
            }
            _uiState.value = if (saveResult.isSuccess) {
                _uiState.value.copy(saveMessage = "Saved")
            } else {
                _uiState.value.copy(saveMessage = "Save failed")
            }
        }
        return true
    }

    fun startGateway() {
        if (!_uiState.value.canStart) {
            return
        }
        coroutineScope.launch {
            val currentState = _uiState.value
            val startConfig = toGatewayConfig(currentState)
            withContext(ioDispatcher) {
                persistCurrentConfig(currentState)
            }
            controller.start(
                targetDeviceAddress = currentState.targetDeviceAddress,
                preloadedConfig = startConfig,
            )
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

    private fun refreshStartEligibility() {
        _uiState.value = _uiState.value.copy(
            canStart = isStartEligible(
                deviceId = _uiState.value.deviceId,
                authToken = _uiState.value.authToken,
                relayBaseUrl = _uiState.value.relayBaseUrl,
                runState = runState.value,
            ),
        )
    }

    private fun isStartEligible(
        deviceId: String,
        authToken: String,
        relayBaseUrl: String,
        runState: GatewayRunState,
    ): Boolean {
        val hasRequiredConfig = PhoneLocalConfig.isValidDeviceId(deviceId) &&
            authToken.isNotBlank() &&
            relayBaseUrl.isNotBlank()
        val runStateAllowsStart = runState == GatewayRunState.IDLE ||
            runState == GatewayRunState.STOPPED ||
            runState == GatewayRunState.ERROR
        return hasRequiredConfig && runStateAllowsStart
    }

    private fun persistCurrentConfig(state: PhoneSettingsUiState) {
        localConfigStore.save(
            PhoneLocalConfig(
                deviceId = state.deviceId,
                authToken = state.authToken.ifBlank { null },
                relayBaseUrl = state.relayBaseUrl.ifBlank { null },
            ),
        )
    }

    private fun toGatewayConfig(state: PhoneSettingsUiState): PhoneGatewayConfig {
        return PhoneGatewayConfig(
            deviceId = state.deviceId,
            authToken = state.authToken.ifBlank { null },
            relayBaseUrl = state.relayBaseUrl.ifBlank { null },
            appVersion = appVersion,
        )
    }
}
