package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.RelayProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.SetupState
import cn.cutemc.rokidmcp.share.protocol.relay.CommandAckMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandAckPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandCancelMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandErrorMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandErrorPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandResultMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandResultPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandStatusMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandStatusPayload
import cn.cutemc.rokidmcp.share.protocol.relay.RelayHeartbeatMessage
import cn.cutemc.rokidmcp.share.protocol.relay.RelayHeartbeatPayload
import cn.cutemc.rokidmcp.share.protocol.relay.RelayHelloAckMessage
import cn.cutemc.rokidmcp.share.protocol.relay.RelayHelloMessage
import cn.cutemc.rokidmcp.share.protocol.relay.RelayHelloPayload
import cn.cutemc.rokidmcp.share.protocol.relay.RelayMessageType
import cn.cutemc.rokidmcp.share.protocol.relay.RelayPhoneInfo
import cn.cutemc.rokidmcp.share.protocol.relay.RelayPhoneStateUpdateMessage
import cn.cutemc.rokidmcp.share.protocol.relay.RelayPhoneStateUpdatePayload
import cn.cutemc.rokidmcp.share.protocol.relay.RelayProtocolJson
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

sealed interface RelaySessionEvent {
    data object Connected : RelaySessionEvent

    data class UplinkStateChanged(
        val state: PhoneUplinkState,
    ) : RelaySessionEvent

    data class Failed(
        val message: String,
    ) : RelaySessionEvent

    data class CommandDispatched(
        val message: CommandDispatchMessage,
    ) : RelaySessionEvent

    data class CommandCancelled(
        val message: CommandCancelMessage,
    ) : RelaySessionEvent

    data class ConnectionClosed(
        val code: Int,
        val reason: String,
    ) : RelaySessionEvent
}

interface RelayWebSocket {
    fun sendText(text: String)
    fun close(code: Int = 1000, reason: String = "normal closure")
}

fun interface RelayWebSocketFactory {
    fun connect(url: String, callbacks: RelayWebSocketCallbacks): RelayWebSocket
}

interface RelayWebSocketCallbacks {
    fun onOpen()
    fun onTextMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

class OkHttpRelayWebSocketFactory(
    private val client: OkHttpClient = OkHttpClient(),
) : RelayWebSocketFactory {
    override fun connect(url: String, callbacks: RelayWebSocketCallbacks): RelayWebSocket {
        var socket: WebSocket? = null
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    callbacks.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    callbacks.onTextMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    callbacks.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    callbacks.onFailure(t)
                }
            },
        )

        return object : RelayWebSocket {
            override fun sendText(text: String) {
                socket.send(text)
            }

            override fun close(code: Int, reason: String) {
                socket.close(code, reason)
            }
        }
    }
}

class RelaySessionClient(
    private val runtimeStore: PhoneRuntimeStore,
    private val clock: Clock,
    private val config: PhoneGatewayConfig,
    private val supportedActions: List<CommandAction> = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
    private val webSocket: RelayWebSocket? = null,
    private val webSocketFactory: RelayWebSocketFactory = OkHttpRelayWebSocketFactory(),
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val json: Json = RelayProtocolJson.default,
) {
    private companion object {
        const val LOG_TAG = "relay-session"
    }

    private data class IncomingRelayMessage(
        val root: JsonObject,
        val rawType: String?,
        val messageType: RelayMessageType?,
    )

    /**
     * Guards callback delivery for the most recent websocket connection so stale or duplicate
     * terminal callbacks cannot mutate the current session.
     */
    private class ConnectionTracker {
        private var nextConnectionId: Long = 0L
        private var activeConnectionId: Long = 0L
        private var handledTerminalConnectionId: Long? = null

        @Synchronized
        fun register(): Long {
            nextConnectionId += 1
            activeConnectionId = nextConnectionId
            handledTerminalConnectionId = null
            return activeConnectionId
        }

        @Synchronized
        fun shouldHandle(connectionId: Long): Boolean {
            return connectionId == activeConnectionId && handledTerminalConnectionId != connectionId
        }

        @Synchronized
        fun claimTerminalCallback(connectionId: Long): Boolean {
            if (connectionId != activeConnectionId || handledTerminalConnectionId == connectionId) {
                return false
            }

            handledTerminalConnectionId = connectionId
            return true
        }

        @Synchronized
        fun markClosedByClient() {
            if (activeConnectionId == 0L) {
                return
            }

            handledTerminalConnectionId = activeConnectionId
            activeConnectionId = 0L
        }

        @Synchronized
        fun clear(connectionId: Long) {
            if (activeConnectionId == connectionId || activeConnectionId == 0L) {
                activeConnectionId = 0L
            }
        }
    }

    private val internalEvents = MutableSharedFlow<RelaySessionEvent>(extraBufferCapacity = 32)
    private val connectionTracker = ConnectionTracker()

    val events: Flow<RelaySessionEvent> = internalEvents

    private var activeWebSocket: RelayWebSocket? = webSocket
    private var sessionId: String? = null
    private var heartbeatIntervalMs: Long = RelayProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS
    private var isManualDisconnect: Boolean = false
    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Long = 0L

    private val relayWebSocketUrl: String
        get() = buildRelayWebSocketUrl(config.relayBaseUrl ?: error("relayBaseUrl is required"))

    suspend fun connect() {
        isManualDisconnect = false
        if (activeWebSocket == null) {
            val relayUrl = relayWebSocketUrl
            val connectionId = connectionTracker.register()
            Timber.tag(LOG_TAG).i("connecting relay websocket to %s", relayUrl.toSafeRelayUrlSummary())
            activeWebSocket = webSocketFactory.connect(
                relayUrl,
                createCallbacks(connectionId, relayUrl),
            )
        }

        if (webSocket != null) {
            handleConnected()
        }
    }

    suspend fun disconnect(reason: String) {
        isManualDisconnect = true
        clearSessionState()
        connectionTracker.markClosedByClient()
        activeWebSocket?.close(reason = reason)
        restoreConfiguredWebSocket()
        emitUplinkState(PhoneUplinkState.OFFLINE)
    }

    suspend fun onConnected() {
        handleConnected()
    }

    suspend fun onTextMessage(text: String) {
        handleIncomingTextMessage(text)
    }

    suspend fun onClosed(code: Int, reason: String) {
        handleClosed(code, reason.redactRelaySecrets())
    }

    suspend fun onFailure(error: Throwable) {
        handleFailure(error.redactRelaySecrets())
    }

    private fun createCallbacks(connectionId: Long, relayUrl: String): RelayWebSocketCallbacks {
        return object : RelayWebSocketCallbacks {
            override fun onOpen() {
                if (!connectionTracker.shouldHandle(connectionId)) {
                    return
                }

                Timber.tag(LOG_TAG).i("relay websocket opened")
                controllerScope.launch { handleConnected() }
            }

            override fun onTextMessage(text: String) {
                if (!connectionTracker.shouldHandle(connectionId)) {
                    return
                }

                controllerScope.launch { handleIncomingTextMessage(text) }
            }

            override fun onClosed(code: Int, reason: String) {
                if (!connectionTracker.claimTerminalCallback(connectionId)) {
                    return
                }

                val safeReason = reason.redactRelaySecrets()
                Timber.tag(LOG_TAG).w("relay websocket closed code=%d reason=%s", code, safeReason.ifBlank { "(empty)" })
                controllerScope.launch { handleClosed(connectionId, code, safeReason) }
            }

            override fun onFailure(error: Throwable) {
                if (!connectionTracker.claimTerminalCallback(connectionId)) {
                    return
                }

                val safeError = error.redactRelaySecrets()
                Timber.tag(LOG_TAG).e(
                    safeError,
                    "relay websocket failure url=%s",
                    relayUrl.toSafeRelayUrlSummary(),
                )
                controllerScope.launch { handleFailure(connectionId, safeError) }
            }
        }
    }

    private suspend fun handleConnected() {
        internalEvents.emit(RelaySessionEvent.Connected)
        emitUplinkState(PhoneUplinkState.CONNECTING)

        val snapshot = runtimeStore.snapshot.value
        Timber.tag(LOG_TAG).i(
            "sending relay HELLO deviceId=%s setupState=%s runtimeState=%s url=%s",
            config.deviceId,
            snapshot.setupState,
            snapshot.runtimeState,
            relayWebSocketUrl.toSafeRelayUrlSummary(),
        )
        sendEncodedMessage(
            serializer = RelayHelloMessage.serializer(),
            message = RelayHelloMessage(
                version = RelayProtocolConstants.PROTOCOL_VERSION,
                deviceId = config.deviceId,
                timestamp = clock.nowMs(),
                payload = RelayHelloPayload(
                    authToken = requireNotNull(config.authToken),
                    appVersion = config.appVersion,
                    phoneInfo = RelayPhoneInfo(),
                    setupState = snapshot.setupState.toRelaySetupState(),
                    runtimeState = snapshot.runtimeState.toRelayRuntimeState(),
                    capabilities = supportedActions,
                ),
            ),
        )
    }

    private suspend fun handleIncomingTextMessage(text: String) {
        val message = parseIncomingMessage(text) ?: return

        when (message.messageType) {
            RelayMessageType.HELLO_ACK -> handleHelloAck(message)
            RelayMessageType.COMMAND -> handleCommand(message)
            RelayMessageType.COMMAND_CANCEL -> handleCommandCancel(message)
            else -> Timber.tag(LOG_TAG).w(
                "received relay message with unknown type=%s",
                message.rawType ?: "(missing)",
            )
        }
    }

    private suspend fun handleHelloAck(message: IncomingRelayMessage) {
        val ack = try {
            json.decodeFromJsonElement(RelayHelloAckMessage.serializer(), message.root)
        } catch (error: SerializationException) {
            if (message.root.payloadSessionIdOrNull().isNullOrBlank()) {
                Timber.tag(LOG_TAG).e("relay HELLO_ACK failed: missing sessionId")
                failHandshake("relay hello_ack missing sessionId")
                return
            }

            Timber.tag(LOG_TAG).e(error, "relay HELLO_ACK failed: invalid payload")
            failHandshake(error.message ?: "relay hello_ack parse failure")
            return
        }

        sessionId = ack.payload.sessionId
        heartbeatIntervalMs = ack.payload.heartbeatIntervalMs
        Timber.tag(LOG_TAG).i(
            "relay HELLO_ACK accepted sessionId=%s heartbeatIntervalMs=%d",
            ack.payload.sessionId,
            heartbeatIntervalMs,
        )
        emitUplinkState(PhoneUplinkState.ONLINE)
        startHeartbeatLoop()
    }

    private suspend fun handleCommand(message: IncomingRelayMessage) {
        val command = decodeIncomingMessage(
            serializer = CommandDispatchMessage.serializer(),
            message = message,
            errorMessage = "failed to decode relay command payload",
        ) ?: return

        Timber.tag(LOG_TAG).i(
            "received relay command requestId=%s action=%s sessionId=%s",
            command.requestId,
            command.payload.action,
            command.sessionId,
        )
        internalEvents.emit(RelaySessionEvent.CommandDispatched(command))
    }

    private suspend fun handleCommandCancel(message: IncomingRelayMessage) {
        val commandCancel = decodeIncomingMessage(
            serializer = CommandCancelMessage.serializer(),
            message = message,
            errorMessage = "failed to decode relay command_cancel payload",
        ) ?: return

        Timber.tag(LOG_TAG).i(
            "received relay command_cancel requestId=%s action=%s sessionId=%s",
            commandCancel.requestId,
            commandCancel.payload.action,
            commandCancel.sessionId,
        )
        internalEvents.emit(RelaySessionEvent.CommandCancelled(commandCancel))
    }

    private suspend fun handleClosed(code: Int, reason: String) {
        clearSessionState()
        if (!isManualDisconnect) {
            internalEvents.emit(RelaySessionEvent.ConnectionClosed(code, reason))
        }
        restoreConfiguredWebSocket()
        emitUplinkState(PhoneUplinkState.OFFLINE)
    }

    private suspend fun handleFailure(error: Throwable) {
        Timber.tag(LOG_TAG).e(error, "relay websocket failure")
        clearSessionState()
        restoreConfiguredWebSocket()
        internalEvents.emit(RelaySessionEvent.Failed(error.message ?: "relay websocket failure"))
    }

    private suspend fun handleClosed(connectionId: Long, code: Int, reason: String) {
        connectionTracker.clear(connectionId)
        handleClosed(code, reason)
    }

    private suspend fun handleFailure(connectionId: Long, error: Throwable) {
        connectionTracker.clear(connectionId)
        handleFailure(error)
    }

    private fun parseIncomingMessage(text: String): IncomingRelayMessage? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: SerializationException) {
            Timber.tag(LOG_TAG).e(error, "failed to parse relay websocket message envelope")
            return null
        }

        val rawType = root["type"]?.jsonPrimitive?.contentOrNull
        return IncomingRelayMessage(
            root = root,
            rawType = rawType,
            messageType = rawType?.toRelayMessageType(),
        )
    }

    private fun <T> decodeIncomingMessage(
        serializer: KSerializer<T>,
        message: IncomingRelayMessage,
        errorMessage: String,
    ): T? {
        return try {
            json.decodeFromJsonElement(serializer, message.root)
        } catch (error: SerializationException) {
            Timber.tag(LOG_TAG).e(error, errorMessage)
            null
        }
    }

    private suspend fun failHandshake(message: String) {
        clearSessionState()
        internalEvents.emit(RelaySessionEvent.Failed(message))
        emitUplinkState(PhoneUplinkState.OFFLINE)
    }

    private fun clearSessionState() {
        stopHeartbeat()
        sessionId = null
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun restoreConfiguredWebSocket() {
        activeWebSocket = webSocket
    }

    private suspend fun emitUplinkState(state: PhoneUplinkState) {
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(state))
    }

    fun canSendStateUpdate(): Boolean = sessionId != null

    suspend fun sendHeartbeat(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = sessionId ?: return
        val nextHeartbeatSeq = heartbeatSeq
        Timber.tag(LOG_TAG).v(
            "sent relay heartbeat sessionId=%s seq=%d runtimeState=%s activeCommandRequestId=%s",
            currentSessionId,
            nextHeartbeatSeq,
            snapshot.runtimeState,
            snapshot.activeCommandRequestId ?: "none",
        )
        sendEncodedMessage(
            serializer = RelayHeartbeatMessage.serializer(),
            message = RelayHeartbeatMessage(
                version = RelayProtocolConstants.PROTOCOL_VERSION,
                deviceId = config.deviceId,
                sessionId = currentSessionId,
                timestamp = clock.nowMs(),
                payload = RelayHeartbeatPayload(
                    seq = heartbeatSeq++,
                    runtimeState = snapshot.runtimeState.toRelayRuntimeState(),
                    pendingCommandCount = 0,
                    activeCommandRequestId = snapshot.activeCommandRequestId,
                ),
            ),
        )
    }

    suspend fun sendPhoneStateUpdate(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = sessionId ?: return
        Timber.tag(LOG_TAG).i(
            "sending relay phone_state_update sessionId=%s setupState=%s runtimeState=%s activeCommandRequestId=%s",
            currentSessionId,
            snapshot.setupState,
            snapshot.runtimeState,
            snapshot.activeCommandRequestId ?: "none",
        )
        sendEncodedMessage(
            serializer = RelayPhoneStateUpdateMessage.serializer(),
            message = RelayPhoneStateUpdateMessage(
                version = RelayProtocolConstants.PROTOCOL_VERSION,
                deviceId = config.deviceId,
                sessionId = currentSessionId,
                timestamp = clock.nowMs(),
                payload = RelayPhoneStateUpdatePayload(
                    setupState = snapshot.setupState.toRelaySetupState(),
                    runtimeState = snapshot.runtimeState.toRelayRuntimeState(),
                    lastErrorCode = snapshot.lastErrorCode,
                    lastErrorMessage = snapshot.lastErrorMessage,
                    activeCommandRequestId = snapshot.activeCommandRequestId,
                ),
            ),
        )
    }

    suspend fun sendCommandAck(requestId: String, payload: CommandAckPayload) {
        Timber.tag(LOG_TAG).i(
            "sending relay command_ack requestId=%s action=%s sessionId=%s",
            requestId,
            payload.action,
            sessionId ?: "none",
        )
        sendSessionMessage(
            serializer = CommandAckMessage.serializer(),
            message = CommandAckMessage(
                deviceId = config.deviceId,
                requestId = requestId,
                sessionId = sessionId,
                timestamp = clock.nowMs(),
                payload = payload,
            ),
        )
    }

    suspend fun sendCommandStatus(requestId: String, payload: CommandStatusPayload) {
        Timber.tag(LOG_TAG).i(
            "sending relay command_status requestId=%s action=%s status=%s sessionId=%s",
            requestId,
            payload.action,
            payload.status,
            sessionId ?: "none",
        )
        sendSessionMessage(
            serializer = CommandStatusMessage.serializer(),
            message = CommandStatusMessage(
                deviceId = config.deviceId,
                requestId = requestId,
                sessionId = sessionId,
                timestamp = clock.nowMs(),
                payload = payload,
            ),
        )
    }

    suspend fun sendCommandResult(requestId: String, payload: CommandResultPayload) {
        Timber.tag(LOG_TAG).i(
            "sending relay command_result requestId=%s action=%s sessionId=%s",
            requestId,
            payload.result.action,
            sessionId ?: "none",
        )
        sendSessionMessage(
            serializer = CommandResultMessage.serializer(),
            message = CommandResultMessage(
                deviceId = config.deviceId,
                requestId = requestId,
                sessionId = sessionId,
                timestamp = clock.nowMs(),
                payload = payload,
            ),
        )
    }

    suspend fun sendCommandError(requestId: String, payload: CommandErrorPayload) {
        Timber.tag(LOG_TAG).i(
            "sending relay command_error requestId=%s action=%s errorCode=%s sessionId=%s",
            requestId,
            payload.action,
            payload.error.code,
            sessionId ?: "none",
        )
        sendSessionMessage(
            serializer = CommandErrorMessage.serializer(),
            message = CommandErrorMessage(
                deviceId = config.deviceId,
                requestId = requestId,
                sessionId = sessionId,
                timestamp = clock.nowMs(),
                payload = payload,
            ),
        )
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        Timber.tag(LOG_TAG).i(
            "starting relay heartbeat loop sessionId=%s intervalMs=%d",
            sessionId ?: "none",
            heartbeatIntervalMs,
        )
        heartbeatJob = controllerScope.launch {
            while (true) {
                delay(heartbeatIntervalMs)
                sendHeartbeat(runtimeStore.snapshot.value)
            }
        }
    }

    private fun buildRelayWebSocketUrl(baseUrl: String): String {
        val uri = URI(baseUrl)
        val scheme = when (uri.scheme?.lowercase()) {
            "http" -> "ws"
            "https" -> "wss"
            "ws", "wss" -> uri.scheme.lowercase()
            else -> error("unsupported relay base url scheme: ${uri.scheme}")
        }
        val normalizedPath = uri.path.orEmpty().trimEnd('/')
        val routePath = if (normalizedPath.isBlank()) "/ws/device" else "$normalizedPath/ws/device"
        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            routePath,
            uri.query,
            uri.fragment,
        ).toString()
    }

    private fun <T> sendEncodedMessage(serializer: KSerializer<T>, message: T) {
        activeWebSocket?.sendText(json.encodeToString(serializer, message))
    }

    private fun <T> sendSessionMessage(serializer: KSerializer<T>, message: T) {
        if (sessionId == null) {
            return
        }

        sendEncodedMessage(serializer, message)
    }

    private fun PhoneSetupState.toRelaySetupState(): SetupState {
        return when (this) {
            PhoneSetupState.UNINITIALIZED -> SetupState.UNINITIALIZED
            PhoneSetupState.INITIALIZED -> SetupState.INITIALIZED
        }
    }

    private fun PhoneRuntimeState.toRelayRuntimeState(): RuntimeState {
        return when (this) {
            PhoneRuntimeState.DISCONNECTED -> RuntimeState.DISCONNECTED
            PhoneRuntimeState.CONNECTING -> RuntimeState.CONNECTING
            PhoneRuntimeState.READY -> RuntimeState.READY
            PhoneRuntimeState.BUSY -> RuntimeState.BUSY
            PhoneRuntimeState.ERROR -> RuntimeState.ERROR
        }
    }

}

private fun JsonObject.payloadSessionIdOrNull(): String? {
    return this["payload"]
        ?.jsonObject
        ?.get("sessionId")
        ?.jsonPrimitive
        ?.contentOrNull
}

private fun String.toRelayMessageType(): RelayMessageType? {
    return when (this) {
        "hello" -> RelayMessageType.HELLO
        "hello_ack" -> RelayMessageType.HELLO_ACK
        "heartbeat" -> RelayMessageType.HEARTBEAT
        "phone_state_update" -> RelayMessageType.PHONE_STATE_UPDATE
        "command" -> RelayMessageType.COMMAND
        "command_cancel" -> RelayMessageType.COMMAND_CANCEL
        "command_ack" -> RelayMessageType.COMMAND_ACK
        "command_status" -> RelayMessageType.COMMAND_STATUS
        "command_result" -> RelayMessageType.COMMAND_RESULT
        "command_error" -> RelayMessageType.COMMAND_ERROR
        else -> null
    }
}

private fun String.toSafeRelayUrlSummary(): String {
    val uri = URI(this)
    val safePath = uri.path.orEmpty().ifBlank { "/" }
    return URI(uri.scheme, null, uri.host, -1, safePath, null, null).toString()
}
