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

    private val internalEvents = MutableSharedFlow<RelaySessionEvent>(extraBufferCapacity = 32)

    val events: Flow<RelaySessionEvent> = internalEvents

    private var activeWebSocket: RelayWebSocket? = webSocket
    private var sessionId: String? = null
    private var heartbeatIntervalMs: Long = RelayProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS
    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Long = 0L
    private var nextConnectionId: Long = 0L
    private var activeConnectionId: Long = 0L
    private var handledTerminalConnectionId: Long? = null

    suspend fun connect() {
        if (activeWebSocket == null) {
            val relayUrl = buildRelayWebSocketUrl(config.relayBaseUrl ?: error("relayBaseUrl is required"))
            val connectionId = registerConnection()
            Timber.tag(LOG_TAG).i("connecting relay websocket to %s", relayUrl.toSafeRelayUrlSummary())
            activeWebSocket = webSocketFactory.connect(
                relayUrl,
                object : RelayWebSocketCallbacks {
                    override fun onOpen() {
                        if (!shouldHandleActiveCallback(connectionId)) {
                            return
                        }
                        Timber.tag(LOG_TAG).i("relay websocket opened")
                        controllerScope.launch { onConnected() }
                    }

                    override fun onTextMessage(text: String) {
                        if (!shouldHandleActiveCallback(connectionId)) {
                            return
                        }
                        controllerScope.launch { onTextMessage(text) }
                    }

                    override fun onClosed(code: Int, reason: String) {
                        if (!claimTerminalCallback(connectionId)) {
                            return
                        }
                        Timber.tag(LOG_TAG).w("relay websocket closed code=%d reason=%s", code, reason.ifBlank { "(empty)" })
                        controllerScope.launch { onClosed(connectionId, code, reason) }
                    }

                    override fun onFailure(error: Throwable) {
                        if (!claimTerminalCallback(connectionId)) {
                            return
                        }
                        Timber.tag(LOG_TAG).e(
                            error,
                            "relay websocket failure url=%s",
                            relayUrl.toSafeRelayUrlSummary(),
                        )
                        controllerScope.launch { onFailure(connectionId, error) }
                    }
                },
            )
        }

        if (webSocket != null) {
            onConnected()
        }
    }

    suspend fun disconnect(reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionId = null
        markConnectionClosedByClient()
        activeWebSocket?.close(reason = reason)
        activeWebSocket = webSocket
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
    }

    suspend fun onConnected() {
        internalEvents.emit(RelaySessionEvent.Connected)
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.CONNECTING))

        val snapshot = runtimeStore.snapshot.value
        Timber.tag(LOG_TAG).i(
            "sending relay HELLO deviceId=%s setupState=%s runtimeState=%s url=%s",
            config.deviceId,
            snapshot.setupState,
            snapshot.runtimeState,
            buildRelayWebSocketUrl(config.relayBaseUrl ?: error("relayBaseUrl is required")).toSafeRelayUrlSummary(),
        )
        activeWebSocket?.sendText(
            json.encodeToString(
                RelayHelloMessage.serializer(),
                RelayHelloMessage(
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
            ),
        )
    }

    suspend fun onTextMessage(text: String) {
        val rawType = try {
            text.toRelayRawMessageType(json)
        } catch (error: SerializationException) {
            Timber.tag(LOG_TAG).e(error, "failed to parse relay websocket message envelope")
            return
        }

        val messageType = rawType?.toRelayMessageType()

        when (messageType) {
            RelayMessageType.HELLO_ACK -> {
                val ackSessionId = try {
                    json.parseToJsonElement(text)
                        .jsonObject["payload"]
                        ?.jsonObject
                        ?.get("sessionId")
                        ?.jsonPrimitive
                        ?.contentOrNull
                } catch (error: Exception) {
                    Timber.tag(LOG_TAG).e(error, "failed to extract relay sessionId from HELLO_ACK")
                    null
                }
                if (ackSessionId.isNullOrBlank()) {
                    Timber.tag(LOG_TAG).e("relay HELLO_ACK failed: missing sessionId")
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    sessionId = null
                    internalEvents.emit(RelaySessionEvent.Failed("relay hello_ack missing sessionId"))
                    internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
                    return
                }

                val ack = try {
                    json.decodeFromString(RelayHelloAckMessage.serializer(), text)
                } catch (error: SerializationException) {
                    Timber.tag(LOG_TAG).e(error, "relay HELLO_ACK failed: invalid payload")
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    sessionId = null
                    internalEvents.emit(RelaySessionEvent.Failed(error.message ?: "relay hello_ack parse failure"))
                    internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
                    return
                }

                sessionId = ackSessionId
                heartbeatIntervalMs = ack.payload.heartbeatIntervalMs
                Timber.tag(LOG_TAG).i(
                    "relay HELLO_ACK accepted sessionId=%s heartbeatIntervalMs=%d",
                    ackSessionId,
                    heartbeatIntervalMs,
                )
                internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.ONLINE))
                startHeartbeatLoop()
            }

            RelayMessageType.COMMAND -> {
                val command = json.decodeFromString(CommandDispatchMessage.serializer(), text)
                Timber.tag(LOG_TAG).i(
                    "received relay command requestId=%s action=%s sessionId=%s",
                    command.requestId,
                    command.payload.action,
                    command.sessionId,
                )
                internalEvents.emit(
                    RelaySessionEvent.CommandDispatched(
                        command,
                    ),
                )
            }

            RelayMessageType.COMMAND_CANCEL -> {
                val commandCancel = json.decodeFromString(CommandCancelMessage.serializer(), text)
                Timber.tag(LOG_TAG).i(
                    "received relay command_cancel requestId=%s action=%s sessionId=%s",
                    commandCancel.requestId,
                    commandCancel.payload.action,
                    commandCancel.sessionId,
                )
                internalEvents.emit(
                    RelaySessionEvent.CommandCancelled(
                        commandCancel,
                    ),
                )
            }

            else -> Timber.tag(LOG_TAG).w(
                "received relay message with unknown type=%s",
                rawType ?: "(missing)",
            )
        }
    }

    suspend fun onClosed(code: Int, reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionId = null
        val wasActiveSocket = activeWebSocket != null
        activeWebSocket = webSocket

        if (wasActiveSocket) {
            internalEvents.emit(RelaySessionEvent.ConnectionClosed(code, reason))
        }
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
    }

    suspend fun onFailure(error: Throwable) {
        Timber.tag(LOG_TAG).e(error, "relay websocket failure")
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionId = null
        activeWebSocket = webSocket
        internalEvents.emit(RelaySessionEvent.Failed(error.message ?: "relay websocket failure"))
    }

    private suspend fun onClosed(connectionId: Long, code: Int, reason: String) {
        clearConnectionTracking(connectionId)
        onClosed(code, reason)
    }

    private suspend fun onFailure(connectionId: Long, error: Throwable) {
        clearConnectionTracking(connectionId)
        onFailure(error)
    }

    @Synchronized
    private fun registerConnection(): Long {
        nextConnectionId += 1
        activeConnectionId = nextConnectionId
        handledTerminalConnectionId = null
        return activeConnectionId
    }

    @Synchronized
    private fun shouldHandleActiveCallback(connectionId: Long): Boolean {
        return connectionId == activeConnectionId && handledTerminalConnectionId != connectionId
    }

    @Synchronized
    private fun claimTerminalCallback(connectionId: Long): Boolean {
        if (connectionId != activeConnectionId || handledTerminalConnectionId == connectionId) {
            return false
        }

        handledTerminalConnectionId = connectionId
        return true
    }

    @Synchronized
    private fun markConnectionClosedByClient() {
        if (activeConnectionId == 0L) {
            return
        }

        handledTerminalConnectionId = activeConnectionId
        activeConnectionId = 0L
    }

    @Synchronized
    private fun clearConnectionTracking(connectionId: Long) {
        if (activeConnectionId == connectionId || activeConnectionId == 0L) {
            activeConnectionId = 0L
        }
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
        activeWebSocket?.sendText(
            json.encodeToString(
                RelayHeartbeatMessage.serializer(),
                RelayHeartbeatMessage(
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
        activeWebSocket?.sendText(
            json.encodeToString(
                RelayPhoneStateUpdateMessage.serializer(),
                RelayPhoneStateUpdateMessage(
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

    private fun <T> sendSessionMessage(serializer: KSerializer<T>, message: T) {
        if (sessionId == null) {
            return
        }

        activeWebSocket?.sendText(json.encodeToString(serializer, message))
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

private fun String.toRelayRawMessageType(json: Json): String? {
    return json.parseToJsonElement(this)
        .jsonObject["type"]
        ?.jsonPrimitive
        ?.content
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
