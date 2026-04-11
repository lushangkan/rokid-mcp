package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.logging.PhoneLogEntry
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.constants.PhoneGatewayErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    val reconnectDelayMs: Long = PhoneLocalConfig.DEFAULT_RECONNECT_DELAY_MS,
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
    private val supportedActions: List<CommandAction> = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
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
    private var relayCommandBridge: RelayCommandBridge? = null
    private var lastReportedSnapshot: PhoneRuntimeSnapshot? = null
    private var currentTransportState: PhoneTransportState = PhoneTransportState.IDLE
    private var isLocalSessionReady: Boolean = false
    private val reportMutex = Mutex()
    private var lastStartTargetDeviceAddress: String? = null
    private var lastEffectiveConfig: PhoneGatewayConfig? = null
    private var pendingReconnectJob: Job? = null

    suspend fun start(targetDeviceAddress: String, preloadedConfig: PhoneGatewayConfig? = null) {
        Timber.tag("controller").i("start requested target=%s", maskBluetoothAddress(targetDeviceAddress))
        cancelPendingReconnect()
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
            setRunState(GatewayRunState.ERROR)
            Timber.tag("controller").e("missing relay config")
            runtimeStore.replace(
                runtimeStore.snapshot.value.copy(
                    runtimeState = PhoneRuntimeState.ERROR,
                    lastErrorCode = PhoneGatewayErrorCodes.PHONE_CONFIG_INCOMPLETE,
                    lastErrorMessage = "authToken or relayBaseUrl is missing",
                ),
            )
            return
        }

        setRunState(GatewayRunState.STARTING)
        lastStartTargetDeviceAddress = targetDeviceAddress
        lastEffectiveConfig = config
        setRunState(GatewayRunState.STARTING)
        lastReportedSnapshot = null
        currentTransportState = PhoneTransportState.CONNECTING
        isLocalSessionReady = false

        val createdTransport = try {
            createTransport()
        } catch (error: Throwable) {
            Timber.tag("controller").e(error, "failed to create bluetooth transport")
            markStartupFailure(
                code = PhoneGatewayErrorCodes.BLUETOOTH_TRANSPORT_UNAVAILABLE,
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
        val createdRelayCommandBridge = RelayCommandBridge(
            relayBaseUrl = requireNotNull(config.relayBaseUrl),
            deviceId = config.deviceId,
            clock = clock,
            relaySessionClient = createdRelaySessionClient,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = clock,
                sender = LocalFrameSender { header, body -> createdSession.sendFrame(header, body) },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(),
            runtimeUpdater = ::applyBridgeCommandState,
        )

        Timber.tag("controller").i("starting relay and local sessions target=%s", maskBluetoothAddress(targetDeviceAddress))

        transportEventsJob?.cancel()
        transportEventsJob = controllerScope.launch {
            createdTransport.events.collect { event ->
                when (event) {
                    is PhoneTransportEvent.StateChanged -> applyTransportState(event.state)
                    is PhoneTransportEvent.Failure -> {
                        Timber.tag("controller").e(event.cause, "phone transport failure")
                        setRunState(GatewayRunState.ERROR)
                        relayCommandBridge?.failActiveCommand(
                            code = LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
                            message = event.cause.message ?: "transport failure",
                        )
                        terminateActiveSession("transport ended")
                        val next = runtimeStore.snapshot.value.copy(
                            runtimeState = PhoneRuntimeState.ERROR,
                            lastErrorCode = PhoneGatewayErrorCodes.BLUETOOTH_TRANSPORT_ERROR,
                            lastErrorMessage = event.cause.message ?: "transport failure",
                        )
                        runtimeStore.replace(next)
                        scheduleFullReconnect("transport failure: ${event.cause.message}")
                    }
                    is PhoneTransportEvent.ConnectionClosed -> {
                        setRunState(GatewayRunState.STOPPED)
                        relayCommandBridge?.failActiveCommand(
                            code = LocalProtocolErrorCodes.BLUETOOTH_DISCONNECTED,
                            message = event.reason ?: "transport disconnected",
                        )
                        terminateActiveSession("transport ended")
                        val next = runtimeStore.snapshot.value.copy(
                            runtimeState = PhoneRuntimeState.DISCONNECTED,
                            lastErrorCode = null,
                            lastErrorMessage = null,
                        )
                        runtimeStore.replace(next)
                        scheduleFullReconnect("transport closed")
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
        relayCommandBridge = createdRelayCommandBridge
        try {
            createdRelaySessionClient.connect()
            createdSession.start(targetDeviceAddress)
        } catch (error: Throwable) {
            Timber.tag("controller").e(error, "gateway startup failed for $targetDeviceAddress")
            markStartupFailure(
                code = PhoneGatewayErrorCodes.BLUETOOTH_TRANSPORT_UNAVAILABLE,
                message = error.message ?: "bluetooth transport unavailable",
            )
            terminateActiveSession("startup failed")
        }
    }

    suspend fun stop(reason: String) {
        Timber.tag("controller").i("stop requested reason=%s", reason)
        setRunState(GatewayRunState.STOPPING)
        cancelPendingReconnect()
        stopActiveSession(reason)
        setRunState(GatewayRunState.STOPPED)
    }

    fun clearLogs() {
        logStore.clear()
    }

    fun applyTransportState(state: PhoneTransportState) {
        if (currentTransportState != state) {
            Timber.tag("controller").d("transport state %s -> %s", currentTransportState.name, state.name)
        }
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
                Timber.tag("controller").i("local session ready")
                setRunState(GatewayRunState.RUNNING)
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
                Timber.tag("controller").w("hello rejected code=%s message=%s", event.code, event.message)
                setRunState(GatewayRunState.STOPPED)
                isLocalSessionReady = false
                relayCommandBridge?.failActiveCommand(event.code, event.message)
                stopActiveSession("session failed")
                val next = runtimeStore.snapshot.value.copy(
                    runtimeState = PhoneRuntimeState.DISCONNECTED,
                    lastErrorCode = event.code,
                    lastErrorMessage = event.message,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
                // Note: HelloRejected is non-retryable - user must resolve manually
            }

            is PhoneLocalSessionEvent.PongReceived -> {
                runtimeStore.replace(
                    runtimeStore.snapshot.value.copy(
                        lastSeenAt = event.receivedAt,
                    ),
                )
            }

            is PhoneLocalSessionEvent.SessionFailed -> {
                Timber.tag("controller").w("session failed code=%s message=%s", event.code, event.message)
                setRunState(GatewayRunState.STOPPED)
                isLocalSessionReady = false
                relayCommandBridge?.failActiveCommand(event.code, event.message)
                stopActiveSession("session failed")
                val next = runtimeStore.snapshot.value.copy(
                    runtimeState = PhoneRuntimeState.DISCONNECTED,
                    lastErrorCode = event.code,
                    lastErrorMessage = event.message,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
                scheduleFullReconnect("session failed: ${event.code}")
            }

            is PhoneLocalSessionEvent.FrameReceived -> {
                relayCommandBridge?.handleLocalSessionEvent(event)
            }
        }
    }

    suspend fun handleRelaySessionEvent(event: RelaySessionEvent) {
        when (event) {
            RelaySessionEvent.Connected -> Unit
            is RelaySessionEvent.CommandCancelled,
            is RelaySessionEvent.CommandDispatched,
            -> relayCommandBridge?.handleRelaySessionEvent(event)
            is RelaySessionEvent.UplinkStateChanged -> {
                val current = runtimeStore.snapshot.value
                if (current.uplinkState != event.state) {
                    Timber.tag("controller").d("uplink state %s -> %s", current.uplinkState.name, event.state.name)
                }
                val next = current.copy(
                    uplinkState = event.state,
                    runtimeState = projectRuntimeState(event.state, current.runtimeState),
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
            }

            is RelaySessionEvent.Failed -> {
                val safeMessage = event.message.redactRelaySecrets()
                Timber.tag("controller").e("relay session failed: %s", safeMessage)
                val current = runtimeStore.snapshot.value
                val next = current.copy(
                    uplinkState = PhoneUplinkState.ERROR,
                    runtimeState = projectRuntimeState(PhoneUplinkState.ERROR, current.runtimeState),
                    lastErrorCode = PhoneGatewayErrorCodes.RELAY_SESSION_ERROR,
                    lastErrorMessage = safeMessage,
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
                scheduleRelayReconnect("relay failed: $safeMessage")
            }
            is RelaySessionEvent.ConnectionClosed -> {
                val safeReason = event.reason.redactRelaySecrets()
                val current = runtimeStore.snapshot.value
                val next = current.copy(
                    uplinkState = PhoneUplinkState.OFFLINE,
                    runtimeState = projectRuntimeState(PhoneUplinkState.OFFLINE, current.runtimeState),
                )
                runtimeStore.replace(next)
                reportIfNeeded(next)
                scheduleRelayReconnect("relay closed: ${event.code} $safeReason")
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
        relayCommandBridge = null
        currentTransportState = PhoneTransportState.IDLE
        isLocalSessionReady = false
    }

    private fun scheduleRelayReconnect(reason: String) {
        val config = lastEffectiveConfig ?: return
        val delayMs = config.reconnectDelayMs

        pendingReconnectJob?.cancel()

        Timber.tag("controller").i("scheduling relay reconnect in ${delayMs}ms due to: $reason")

        pendingReconnectJob = controllerScope.launch {
            delay(delayMs)
            Timber.tag("controller").i("executing scheduled relay reconnect")
            relaySessionClient?.connect()
        }
    }

    private fun scheduleFullReconnect(reason: String) {
        val target = lastStartTargetDeviceAddress ?: return
        val config = lastEffectiveConfig ?: return
        val delayMs = config.reconnectDelayMs

        pendingReconnectJob?.cancel()

        Timber.tag("controller").i("scheduling full reconnect in ${delayMs}ms due to: $reason")

        pendingReconnectJob = controllerScope.launch {
            delay(delayMs)
            Timber.tag("controller").i("executing scheduled full reconnect")
            start(target, config)
        }
    }

    private fun cancelPendingReconnect() {
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
    }

    private fun markStartupFailure(code: String, message: String) {
        setRunState(GatewayRunState.ERROR)
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

            Timber.tag("controller").d(
                "reporting snapshot setup=%s runtime=%s uplink=%s command=%s",
                next.setupState.name,
                next.runtimeState.name,
                next.uplinkState.name,
                next.activeCommandRequestId ?: "<none>",
            )
            relayClient.sendPhoneStateUpdate(next)
            lastReportedSnapshot = next
        }
    }

    private suspend fun applyBridgeCommandState(
        activeCommandRequestId: String?,
        errorCode: String?,
        errorMessage: String?,
    ) {
        val current = runtimeStore.snapshot.value
        val nextRuntime = if (activeCommandRequestId != null) {
            PhoneRuntimeState.BUSY
        } else {
            projectRuntimeState(current.uplinkState, PhoneRuntimeState.CONNECTING)
        }
        val next = current.copy(
            runtimeState = nextRuntime,
            activeCommandRequestId = activeCommandRequestId,
            lastErrorCode = errorCode,
            lastErrorMessage = errorMessage,
        )
        runtimeStore.replace(next)
        reportIfNeeded(next)
    }

    private fun setRunState(next: GatewayRunState) {
        val current = _runState.value
        if (current == next) {
            return
        }

        Timber.tag("controller").d("run state %s -> %s", current.name, next.name)
        _runState.value = next
    }
}
