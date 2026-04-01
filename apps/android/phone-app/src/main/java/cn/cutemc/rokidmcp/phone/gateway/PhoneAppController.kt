package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

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

    suspend fun start(targetDeviceAddress: String, preloadedConfig: PhoneGatewayConfig? = null) {
        val config = preloadedConfig ?: loadConfig()
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.CONNECTING,
                uplinkState = PhoneUplinkState.OFFLINE,
            ),
        )

        if (config.authToken.isNullOrBlank() || config.relayBaseUrl.isNullOrBlank()) {
            _runState.value = GatewayRunState.ERROR
            Timber.tag("controller").e("missing relay config")
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
        Timber.tag("controller").i("start requested for $targetDeviceAddress")
    }

    suspend fun stop(reason: String) {
        _runState.value = GatewayRunState.STOPPING
        Timber.tag("controller").i("stop requested: $reason")
        _runState.value = GatewayRunState.STOPPED
    }

    fun clearLogs() {
        logStore.clear()
    }
}
