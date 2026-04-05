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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val createRelaySessionClient: (PhoneGatewayConfig) -> RelaySessionClient = { config ->
        RelaySessionClient(
            runtimeStore = runtimeStore,
            clock = clock,
            config = config,
            supportedActions = supportedActions,
            controllerScope = controllerScope,
        )
    },
) {
    private val _runState = MutableStateFlow(GatewayRunState.IDLE)
    val runState: StateFlow<GatewayRunState> = _runState
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = runtimeStore.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = logStore.entries

    private var transport: RfcommClientTransport? = null
    private var localSession: PhoneLocalLinkSession? = null
    private var relaySessionClient: RelaySessionClient? = null
    private var transportEventsJob: Job? = null
    private var sessionEventsJob: Job? = null
    private var relayEventsJob: Job? = null
    private var lastReportedSnapshot: PhoneRuntimeSnapshot? = null
    private var currentTransportState: PhoneTransportState = PhoneTransportState.IDLE
    private var isLocalSessionReady: Boolean = false
    private val reportMutex = Mutex()

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
        lastReportedSnapshot = null
        currentTransportState = PhoneTransportState.CONNECTING
        isLocalSessionReady = false

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
        val createdRelaySessionClient = createRelaySessionClient(config)

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

        relayEventsJob?.cancel()
        relayEventsJob = controllerScope.launch {
            createdRelaySessionClient.events.collect(::handleRelaySessionEvent)
        }

        transport = createdTransport
        localSession = createdSession
        relaySessionClient = createdRelaySessionClient
        try {
            createdRelaySessionClient.connect()
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
        currentTransportState = state
        if (state != PhoneTransportState.CONNECTED) {
            isLocalSessionReady = false
        }

        val nextRuntime = when (state) {
            PhoneTransportState.CONNECTING,
            PhoneTransportState.CONNECTED,
            -> PhoneRuntimeState.CONNECTING
            PhoneTransportState.IDLE,
            PhoneTransportState.DISCONNECTED,
            -> PhoneRuntimeState.DISCONNECTED
            PhoneTransportState.ERROR -> PhoneRuntimeState.ERROR
        }

        val current = runtimeStore.snapshot.value
        val desiredRuntime = projectRuntimeState(current.uplinkState, nextRuntime)

        runtimeStore.replace(
            current.copy(
                runtimeState = desiredRuntime,
                lastErrorCode = if (desiredRuntime == PhoneRuntimeState.ERROR) {
                    current.lastErrorCode
                } else {
                    null
                },
                lastErrorMessage = if (desiredRuntime == PhoneRuntimeState.ERROR) {
                    current.lastErrorMessage
                } else {
                    null
                },
            ),
        )
        controllerScope.launch {
            reportIfNeeded(runtimeStore.snapshot.value)
        }
    }

    suspend fun handleLocalSessionEvent(event: PhoneLocalSessionEvent) {
        when (event) {
            PhoneLocalSessionEvent.SessionReady -> {
                _runState.value = GatewayRunState.RUNNING
                isLocalSessionReady = true
                val current = runtimeStore.snapshot.value
                val next = current.copy(
                    runtimeState = projectRuntimeState(current.uplinkState, PhoneRuntimeState.CONNECTING),
                    lastErrorCode = null,
                    lastErrorMessage = null,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
            }

            is PhoneLocalSessionEvent.HelloRejected -> {
                _runState.value = GatewayRunState.STOPPED
                isLocalSessionReady = false
                stopActiveSession("session failed")
                val next = runtimeStore.snapshot.value.copy(
                        runtimeState = PhoneRuntimeState.DISCONNECTED,
                        lastErrorCode = event.code,
                        lastErrorMessage = event.message,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
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
                isLocalSessionReady = false
                stopActiveSession("session failed")
                val next = runtimeStore.snapshot.value.copy(
                        runtimeState = PhoneRuntimeState.DISCONNECTED,
                        lastErrorCode = event.code,
                        lastErrorMessage = event.message,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
            }
        }
    }

    suspend fun handleRelaySessionEvent(event: RelaySessionEvent) {
        when (event) {
            RelaySessionEvent.Connected -> Unit
            is RelaySessionEvent.UplinkStateChanged -> {
                val current = runtimeStore.snapshot.value
                val next = current.copy(
                    uplinkState = event.state,
                    runtimeState = projectRuntimeState(event.state, current.runtimeState),
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
            }
            is RelaySessionEvent.Failed -> {
                val current = runtimeStore.snapshot.value
                val next = current.copy(
                    uplinkState = PhoneUplinkState.ERROR,
                    runtimeState = projectRuntimeState(PhoneUplinkState.ERROR, current.runtimeState),
                    lastErrorCode = "RELAY_SESSION_ERROR",
                    lastErrorMessage = event.message,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
            }
        }
    }

    private suspend fun stopActiveSession(reason: String) {
        relaySessionClient?.disconnect(reason)
        localSession?.stop(reason)
        clearActiveSession()
    }

    private suspend fun terminateActiveSession(reason: String) {
        relaySessionClient?.disconnect(reason)
        localSession?.terminate(reason)
        clearActiveSession()
    }

    private fun cancelObservers() {
        sessionEventsJob?.cancel()
        sessionEventsJob = null
        relayEventsJob?.cancel()
        relayEventsJob = null
        transportEventsJob?.cancel()
        transportEventsJob = null
    }

    private fun clearActiveSession() {
        cancelObservers()
        transport = null
        localSession = null
        relaySessionClient = null
        currentTransportState = PhoneTransportState.IDLE
        isLocalSessionReady = false
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

    internal fun shouldReportSnapshotForTest(previous: PhoneRuntimeSnapshot?, next: PhoneRuntimeSnapshot): Boolean {
        if (previous == null) {
            return true
        }

        return previous.setupState != next.setupState ||
            previous.runtimeState != next.runtimeState ||
            previous.uplinkState != next.uplinkState ||
            previous.lastErrorCode != next.lastErrorCode ||
            previous.lastErrorMessage != next.lastErrorMessage ||
            previous.activeCommandRequestId != next.activeCommandRequestId
    }

    internal suspend fun reportSnapshotForTest(next: PhoneRuntimeSnapshot) {
        reportIfNeeded(next)
    }

    private fun projectRuntimeState(
        uplinkState: PhoneUplinkState,
        fallbackRuntime: PhoneRuntimeState,
    ): PhoneRuntimeState {
        return when {
            fallbackRuntime == PhoneRuntimeState.ERROR -> PhoneRuntimeState.ERROR
            currentTransportState == PhoneTransportState.IDLE || currentTransportState == PhoneTransportState.DISCONNECTED -> PhoneRuntimeState.DISCONNECTED
            currentTransportState == PhoneTransportState.ERROR -> PhoneRuntimeState.ERROR
            isLocalSessionReady && uplinkState == PhoneUplinkState.ONLINE -> PhoneRuntimeState.READY
            isLocalSessionReady && uplinkState == PhoneUplinkState.OFFLINE -> PhoneRuntimeState.DISCONNECTED
            isLocalSessionReady && uplinkState == PhoneUplinkState.ERROR -> PhoneRuntimeState.ERROR
            else -> fallbackRuntime
        }
    }

    private suspend fun reportIfNeeded(next: PhoneRuntimeSnapshot) {
        reportMutex.withLock {
            if (!shouldReportSnapshotForTest(lastReportedSnapshot, next)) {
                return
            }

            val relayClient = relaySessionClient
            if (relayClient?.canSendStateUpdate() != true) {
                return
            }

            relayClient.sendPhoneStateUpdate(next)
            lastReportedSnapshot = next
        }
    }
}
