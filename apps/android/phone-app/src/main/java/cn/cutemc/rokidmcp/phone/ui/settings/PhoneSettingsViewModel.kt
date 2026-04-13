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
import timber.log.Timber

data class PhoneSettingsUiState(
    val deviceId: String = "",
    val authToken: String = "",
    val relayBaseUrl: String = "",
    val reconnectDelayMs: String = PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS.toString(),
    val targetDeviceAddress: String = PhoneLocalConfig.DEFAULT_TARGET_DEVICE_ADDRESS,
    val canSave: Boolean = false,
    val canStart: Boolean = false,
    val saveMessage: String? = null,
)

internal fun isValidReconnectDelayMs(value: String): Boolean {
    return parseReconnectDelayMs(value) != null
}

internal fun isValidTargetDeviceAddress(value: String): Boolean {
    return PhoneLocalConfig.isValidTargetDeviceAddress(value)
}

private fun parseReconnectDelayMs(value: String): Long? {
    return value.toLongOrNull()?.takeIf { it > 0L }
}

private fun isSaveEligible(
    deviceId: String,
    reconnectDelayMs: String,
    targetDeviceAddress: String,
): Boolean {
    return PhoneLocalConfig.isValidDeviceId(deviceId) &&
        isValidReconnectDelayMs(reconnectDelayMs) &&
        isValidTargetDeviceAddress(targetDeviceAddress)
}

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
                reconnectDelayMs = config.reconnectDelayMs.toString(),
                targetDeviceAddress = config.targetDeviceAddress,
                canSave = isSaveEligible(
                    deviceId = config.deviceId,
                    reconnectDelayMs = config.reconnectDelayMs.toString(),
                    targetDeviceAddress = config.targetDeviceAddress,
                ),
                canStart = isStartEligible(
                    deviceId = config.deviceId,
                    authToken = config.authToken.orEmpty(),
                    relayBaseUrl = config.relayBaseUrl.orEmpty(),
                    reconnectDelayMs = config.reconnectDelayMs.toString(),
                    targetDeviceAddress = config.targetDeviceAddress,
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
            canSave = isSaveEligible(
                deviceId = value,
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
            ),
            canStart = isStartEligible(
                deviceId = value,
                authToken = _uiState.value.authToken,
                relayBaseUrl = _uiState.value.relayBaseUrl,
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
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
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
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
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
                runState = runState.value,
            ),
            saveMessage = null,
        )
    }

    fun onReconnectDelayMsChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            reconnectDelayMs = value,
            canSave = isSaveEligible(
                deviceId = _uiState.value.deviceId,
                reconnectDelayMs = value,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
            ),
            canStart = isStartEligible(
                deviceId = _uiState.value.deviceId,
                authToken = _uiState.value.authToken,
                relayBaseUrl = _uiState.value.relayBaseUrl,
                reconnectDelayMs = value,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
                runState = runState.value,
            ),
            saveMessage = null,
        )
    }

    fun onTargetDeviceAddressChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            targetDeviceAddress = value,
            canSave = isSaveEligible(
                deviceId = _uiState.value.deviceId,
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = value,
            ),
            canStart = isStartEligible(
                deviceId = _uiState.value.deviceId,
                authToken = _uiState.value.authToken,
                relayBaseUrl = _uiState.value.relayBaseUrl,
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = value,
                runState = runState.value,
            ),
            saveMessage = null,
        )
    }

    fun save(): Boolean {
        val state = _uiState.value
        if (!state.canSave) {
            return false
        }

        coroutineScope.launch {
            val normalizedState = normalizeTargetDeviceAddress(state)
            val saveResult = runCatching {
                withContext(ioDispatcher) {
                    persistCurrentConfig(normalizedState)
                }
            }
            saveResult.exceptionOrNull()?.let { error ->
                Timber.tag("settings").e(error, "failed to save phone settings")
            }
            _uiState.value = if (saveResult.isSuccess) {
                _uiState.value.copy(
                    targetDeviceAddress = normalizedState.targetDeviceAddress,
                    saveMessage = "Saved",
                )
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
            val currentState = normalizeTargetDeviceAddress(_uiState.value)
            val startConfig = toGatewayConfig(currentState)
            withContext(ioDispatcher) {
                persistCurrentConfig(currentState)
            }
            _uiState.value = _uiState.value.copy(targetDeviceAddress = currentState.targetDeviceAddress)
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
                reconnectDelayMs = _uiState.value.reconnectDelayMs,
                targetDeviceAddress = _uiState.value.targetDeviceAddress,
                runState = runState.value,
            ),
        )
    }

    private fun isStartEligible(
        deviceId: String,
        authToken: String,
        relayBaseUrl: String,
        reconnectDelayMs: String,
        targetDeviceAddress: String,
        runState: GatewayRunState,
    ): Boolean {
        val hasRequiredConfig = PhoneLocalConfig.isValidDeviceId(deviceId) &&
            authToken.isNotBlank() &&
            relayBaseUrl.isNotBlank() &&
            isValidTargetDeviceAddress(targetDeviceAddress)
        val hasValidDelay = parseReconnectDelayMs(reconnectDelayMs) != null
        val runStateAllowsStart = runState == GatewayRunState.IDLE ||
            runState == GatewayRunState.STOPPED ||
            runState == GatewayRunState.ERROR
        return hasRequiredConfig && hasValidDelay && runStateAllowsStart
    }

    private fun normalizeTargetDeviceAddress(state: PhoneSettingsUiState): PhoneSettingsUiState {
        return state.copy(
            targetDeviceAddress = PhoneLocalConfig.normalizeTargetDeviceAddress(state.targetDeviceAddress),
        )
    }

    private fun persistCurrentConfig(state: PhoneSettingsUiState) {
        localConfigStore.save(
            PhoneLocalConfig(
                deviceId = state.deviceId,
                authToken = state.authToken.ifBlank { null },
                relayBaseUrl = state.relayBaseUrl.ifBlank { null },
                reconnectDelayMs = parseReconnectDelayMs(state.reconnectDelayMs)
                    ?: PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS,
                targetDeviceAddress = state.targetDeviceAddress,
            ),
        )
    }

    private fun toGatewayConfig(state: PhoneSettingsUiState): PhoneGatewayConfig {
        return PhoneGatewayConfig(
            deviceId = state.deviceId,
            authToken = state.authToken.ifBlank { null },
            relayBaseUrl = state.relayBaseUrl.ifBlank { null },
            appVersion = appVersion,
            reconnectDelayMs = parseReconnectDelayMs(state.reconnectDelayMs)
                ?: PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS,
        )
    }
}
