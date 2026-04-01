package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PhoneGatewayConfig(
    val deviceId: String,
    val authToken: String?,
    val relayBaseUrl: String?,
    val appVersion: String,
)

enum class GatewayRunState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}

class PhoneAppController(
    private val runtimeStore: PhoneRuntimeStore,
    private val logStore: PhoneLogStore,
    private val loadConfig: () -> PhoneGatewayConfig,
) {
    private val _runState = MutableStateFlow(GatewayRunState.IDLE)
    val runState: StateFlow<GatewayRunState> = _runState
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = runtimeStore.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = logStore.entries

    suspend fun start(targetDeviceAddress: String) {
        val config = loadConfig()
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.CONNECTING,
                uplinkState = PhoneUplinkState.OFFLINE,
            ),
        )

        if (config.authToken.isNullOrBlank() || config.relayBaseUrl.isNullOrBlank()) {
            _runState.value = GatewayRunState.ERROR
            logStore.append("controller", "missing relay config")
            runtimeStore.replace(
                runtimeStore.snapshot.value.copy(
                    runtimeState = PhoneRuntimeState.ERROR,
                    lastErrorCode = "PHONE_CONFIG_INCOMPLETE",
                    lastErrorMessage = "authToken or relayBaseUrl is missing",
                ),
            )
            return
        }

        _runState.value = GatewayRunState.STARTING
        logStore.append("controller", "start requested for $targetDeviceAddress")
    }

    suspend fun stop(reason: String) {
        _runState.value = GatewayRunState.STOPPING
        logStore.append("controller", "stop requested: $reason")
        _runState.value = GatewayRunState.STOPPED
    }

    fun clearLogs() {
        logStore.clear()
    }
}
