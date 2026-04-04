package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    private val createTransport: () -> RfcommClientTransport = { AndroidRfcommClientTransport() },
    private val createLocalSession: (RfcommClientTransport, PhoneHelloConfig, Clock, CoroutineScope) -> PhoneLocalLinkSession = { transport, helloConfig, clock, scope ->
        PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = DefaultLocalFrameCodec(),
            clock = clock,
            sessionScope = scope,
        )
    },
    private val clock: Clock = SystemClock,
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val supportedActions: List<LocalAction> = listOf(LocalAction.DISPLAY_TEXT),
) {
    private val _runState = MutableStateFlow(GatewayRunState.IDLE)
    val runState: StateFlow<GatewayRunState> = _runState
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = runtimeStore.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = logStore.entries

    private var transport: RfcommClientTransport? = null
    private var localSession: PhoneLocalLinkSession? = null
    private var transportEventsJob: Job? = null
    private var sessionEventsJob: Job? = null

    suspend fun start(targetDeviceAddress: String, preloadedConfig: PhoneGatewayConfig? = null) {
        if (_runState.value != GatewayRunState.IDLE && localSession != null) {
            stopActiveSession("restarting controller session")
        }

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

        val createdTransport = try {
            createTransport()
        } catch (error: Throwable) {
            markStartupFailure(
                code = "BLUETOOTH_TRANSPORT_UNAVAILABLE",
                message = error.message ?: "bluetooth transport unavailable",
            )
            return
        }
        val helloConfig = PhoneHelloConfig(
            deviceId = config.deviceId,
            appVersion = config.appVersion,
            supportedActions = supportedActions,
        )
        val createdSession = createLocalSession(createdTransport, helloConfig, clock, controllerScope)

        transportEventsJob?.cancel()
        transportEventsJob = controllerScope.launch {
            createdTransport.events.collect { event ->
                when (event) {
                    is PhoneTransportEvent.StateChanged -> applyTransportState(event.state)
                    is PhoneTransportEvent.Failure -> {
                        _runState.value = GatewayRunState.ERROR
                        terminateActiveSession("transport ended")
                        runtimeStore.replace(
                            runtimeStore.snapshot.value.copy(
                                runtimeState = PhoneRuntimeState.ERROR,
                                lastErrorCode = "BLUETOOTH_TRANSPORT_ERROR",
                                lastErrorMessage = event.cause.message ?: "transport failure",
                            ),
                        )
                    }
                    is PhoneTransportEvent.ConnectionClosed -> {
                        _runState.value = GatewayRunState.STOPPED
                        terminateActiveSession("transport ended")
                        runtimeStore.replace(
                            runtimeStore.snapshot.value.copy(
                                runtimeState = PhoneRuntimeState.DISCONNECTED,
                                lastErrorCode = null,
                                lastErrorMessage = null,
                            ),
                        )
                    }
                    is PhoneTransportEvent.BytesReceived -> Unit
                }
            }
        }

        sessionEventsJob?.cancel()
        sessionEventsJob = controllerScope.launch {
            createdSession.events.collect(::handleLocalSessionEvent)
        }

        transport = createdTransport
        localSession = createdSession
        try {
            createdSession.start(targetDeviceAddress)
        } catch (error: Throwable) {
            markStartupFailure(
                code = "BLUETOOTH_TRANSPORT_UNAVAILABLE",
                message = error.message ?: "bluetooth transport unavailable",
            )
            terminateActiveSession("startup failed")
        }
    }

    suspend fun stop(reason: String) {
        _runState.value = GatewayRunState.STOPPING
        Timber.tag("controller").i("stop requested: $reason")
        stopActiveSession(reason)
        _runState.value = GatewayRunState.STOPPED
    }

    fun clearLogs() {
        logStore.clear()
    }

    fun applyTransportState(state: PhoneTransportState) {
        val nextRuntime = when (state) {
            PhoneTransportState.CONNECTING,
            PhoneTransportState.CONNECTED,
            -> PhoneRuntimeState.CONNECTING
            PhoneTransportState.IDLE,
            PhoneTransportState.DISCONNECTED,
            -> PhoneRuntimeState.DISCONNECTED
            PhoneTransportState.ERROR -> PhoneRuntimeState.ERROR
        }

        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = nextRuntime,
                lastErrorCode = if (nextRuntime == PhoneRuntimeState.ERROR) {
                    runtimeStore.snapshot.value.lastErrorCode
                } else {
                    null
                },
                lastErrorMessage = if (nextRuntime == PhoneRuntimeState.ERROR) {
                    runtimeStore.snapshot.value.lastErrorMessage
                } else {
                    null
                },
            ),
        )
    }

    suspend fun handleLocalSessionEvent(event: PhoneLocalSessionEvent) {
        when (event) {
            PhoneLocalSessionEvent.SessionReady -> {
                _runState.value = GatewayRunState.RUNNING
                runtimeStore.replace(
                    runtimeStore.snapshot.value.copy(
                        runtimeState = PhoneRuntimeState.READY,
                        lastErrorCode = null,
                        lastErrorMessage = null,
                    ),
                )
            }

            is PhoneLocalSessionEvent.HelloRejected -> {
                _runState.value = GatewayRunState.STOPPED
                stopActiveSession("session failed")
                runtimeStore.replace(
                    runtimeStore.snapshot.value.copy(
                        runtimeState = PhoneRuntimeState.DISCONNECTED,
                        lastErrorCode = event.code,
                        lastErrorMessage = event.message,
                    ),
                )
            }

            is PhoneLocalSessionEvent.PongReceived -> {
                runtimeStore.replace(
                    runtimeStore.snapshot.value.copy(
                        lastSeenAt = event.receivedAt,
                    ),
                )
            }

            is PhoneLocalSessionEvent.SessionFailed -> {
                _runState.value = GatewayRunState.STOPPED
                stopActiveSession("session failed")
                runtimeStore.replace(
                    runtimeStore.snapshot.value.copy(
                        runtimeState = PhoneRuntimeState.DISCONNECTED,
                        lastErrorCode = event.code,
                        lastErrorMessage = event.message,
                    ),
                )
            }
        }
    }

    private suspend fun stopActiveSession(reason: String) {
        localSession?.stop(reason)
        clearActiveSession()
    }

    private suspend fun terminateActiveSession(reason: String) {
        localSession?.terminate(reason)
        clearActiveSession()
    }

    private fun cancelObservers() {
        sessionEventsJob?.cancel()
        sessionEventsJob = null
        transportEventsJob?.cancel()
        transportEventsJob = null
    }

    private fun clearActiveSession() {
        cancelObservers()
        transport = null
        localSession = null
    }

    private fun markStartupFailure(code: String, message: String) {
        _runState.value = GatewayRunState.ERROR
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = PhoneRuntimeState.ERROR,
                lastErrorCode = code,
                lastErrorMessage = message,
            ),
        )
    }
}
