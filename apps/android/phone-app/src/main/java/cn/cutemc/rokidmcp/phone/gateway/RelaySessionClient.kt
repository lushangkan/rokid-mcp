package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.RelayProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.SetupState
import cn.cutemc.rokidmcp.share.protocol.constants.UplinkState
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
                socket?.send(text)
            }

            override fun close(code: Int, reason: String) {
                socket?.close(code, reason)
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
    private val internalEvents = MutableSharedFlow<RelaySessionEvent>(extraBufferCapacity = 32)

    val events: Flow<RelaySessionEvent> = internalEvents

    private var activeWebSocket: RelayWebSocket? = webSocket
    private var sessionId: String? = null
    private var heartbeatIntervalMs: Long = RelayProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS
    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Long = 0L

    suspend fun connect() {
        if (activeWebSocket == null) {
            activeWebSocket = webSocketFactory.connect(
                buildRelayWebSocketUrl(config.relayBaseUrl ?: error("relayBaseUrl is required")),
                object : RelayWebSocketCallbacks {
                    override fun onOpen() {
                        controllerScope.launch { onConnected() }
                    }

                    override fun onTextMessage(text: String) {
                        controllerScope.launch { onTextMessage(text) }
                    }

                    override fun onClosed(code: Int, reason: String) {
                        controllerScope.launch { onClosed(code, reason) }
                    }

                    override fun onFailure(error: Throwable) {
                        controllerScope.launch { onFailure(error) }
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
        activeWebSocket?.close(reason = reason)
        activeWebSocket = webSocket
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
    }

    suspend fun onConnected() {
        internalEvents.emit(RelaySessionEvent.Connected)
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.CONNECTING))

        val snapshot = runtimeStore.snapshot.value
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
                        uplinkState = snapshot.uplinkState.toRelayUplinkState(),
                        capabilities = supportedActions,
                    ),
                ),
            ),
        )
    }

    suspend fun onTextMessage(text: String) {
        val messageType = try {
            text.toRelayMessageType(json)
        } catch (_: SerializationException) {
            null
        }

        when (messageType) {
            RelayMessageType.HELLO_ACK -> {
                val ackSessionId = try {
                    json.parseToJsonElement(text)
                        .jsonObject["payload"]
                        ?.jsonObject
                        ?.get("sessionId")
                        ?.jsonPrimitive
                        ?.contentOrNull
                } catch (_: Exception) {
                    null
                }
                if (ackSessionId.isNullOrBlank()) {
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
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    sessionId = null
                    internalEvents.emit(RelaySessionEvent.Failed(error.message ?: "relay hello_ack parse failure"))
                    internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
                    return
                }

                sessionId = ackSessionId
                heartbeatIntervalMs = ack.payload.heartbeatIntervalMs ?: heartbeatIntervalMs
                internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.ONLINE))
                startHeartbeatLoop()
            }

            RelayMessageType.COMMAND -> {
                internalEvents.emit(
                    RelaySessionEvent.CommandDispatched(
                        json.decodeFromString(CommandDispatchMessage.serializer(), text),
                    ),
                )
            }

            RelayMessageType.COMMAND_CANCEL -> {
                internalEvents.emit(
                    RelaySessionEvent.CommandCancelled(
                        json.decodeFromString(CommandCancelMessage.serializer(), text),
                    ),
                )
            }

            else -> Unit
        }
    }

    suspend fun onClosed(code: Int, reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionId = null
        internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
    }

    suspend fun onFailure(error: Throwable) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionId = null
        internalEvents.emit(RelaySessionEvent.Failed(error.message ?: "relay websocket failure"))
    }

    fun canSendStateUpdate(): Boolean = sessionId != null

    suspend fun sendHeartbeat(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = sessionId ?: return
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
                        uplinkState = snapshot.uplinkState.toRelayUplinkState(),
                        pendingCommandCount = 0,
                        activeCommandRequestId = snapshot.activeCommandRequestId,
                    ),
                ),
            ),
        )
    }

    suspend fun sendPhoneStateUpdate(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = sessionId ?: return
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
                        uplinkState = snapshot.uplinkState.toRelayUplinkState(),
                        lastErrorCode = snapshot.lastErrorCode,
                        lastErrorMessage = snapshot.lastErrorMessage,
                        activeCommandRequestId = snapshot.activeCommandRequestId,
                    ),
                ),
            ),
        )
    }

    suspend fun sendCommandAck(requestId: String, payload: CommandAckPayload) {
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

    private fun PhoneUplinkState.toRelayUplinkState(): UplinkState {
        return when (this) {
            PhoneUplinkState.OFFLINE -> UplinkState.OFFLINE
            PhoneUplinkState.CONNECTING -> UplinkState.CONNECTING
            PhoneUplinkState.ONLINE -> UplinkState.ONLINE
            PhoneUplinkState.ERROR -> UplinkState.ERROR
        }
    }

}

private fun String.toRelayMessageType(json: Json): RelayMessageType? {
    val rawType = json.parseToJsonElement(this)
        .jsonObject["type"]
        ?.jsonPrimitive
        ?.content
        ?: return null

    return when (rawType) {
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
