package cn.cutemc.rokidmcp.phone.gateway

import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
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
    private val webSocket: RelayWebSocket? = null,
    private val webSocketFactory: RelayWebSocketFactory = OkHttpRelayWebSocketFactory(),
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val internalEvents = MutableSharedFlow<RelaySessionEvent>(extraBufferCapacity = 32)

    val events: Flow<RelaySessionEvent> = internalEvents

    private var activeWebSocket: RelayWebSocket? = webSocket
    private var sessionId: String? = null
    private var heartbeatIntervalMs: Long = 5_000L
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
            """{"version":"1.0","type":"hello","deviceId":"${escapeJson(config.deviceId)}","timestamp":${clock.nowMs()},"payload":{"authToken":"${escapeJson(requireNotNull(config.authToken))}","appVersion":"${escapeJson(config.appVersion)}","phoneInfo":{},"setupState":"${snapshot.setupState.name}","runtimeState":"${snapshot.runtimeState.name}","uplinkState":"${snapshot.uplinkState.name}","capabilities":["display_text","capture_photo"]}}""",
        )
    }

    suspend fun onTextMessage(text: String) {
        when (extractStringField(text, "type")) {
            "hello_ack" -> {
                sessionId = extractStringField(text, "sessionId")
                heartbeatIntervalMs = extractLongField(text, "heartbeatIntervalMs") ?: heartbeatIntervalMs
                internalEvents.emit(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.ONLINE))
                startHeartbeatLoop()
            }
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
        val activeCommandJson = snapshot.activeCommandRequestId?.let {
            "\"${escapeJson(it)}\""
        } ?: "null"
        activeWebSocket?.sendText(
            """{"version":"1.0","type":"heartbeat","deviceId":"${escapeJson(config.deviceId)}","sessionId":"${escapeJson(currentSessionId)}","timestamp":${clock.nowMs()},"payload":{"seq":${heartbeatSeq++},"runtimeState":"${snapshot.runtimeState.name}","uplinkState":"${snapshot.uplinkState.name}","pendingCommandCount":0,"activeCommandRequestId":$activeCommandJson}}""",
        )
    }

    suspend fun sendPhoneStateUpdate(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = sessionId ?: return
        val lastErrorCodeJson = snapshot.lastErrorCode?.let {
            ",\"lastErrorCode\":\"${escapeJson(it)}\""
        }.orEmpty()
        val lastErrorMessageJson = snapshot.lastErrorMessage?.let {
            ",\"lastErrorMessage\":\"${escapeJson(it)}\""
        }.orEmpty()
        val activeCommandJson = "\"activeCommandRequestId\":null"
        activeWebSocket?.sendText(
            """{"version":"1.0","type":"phone_state_update","deviceId":"${escapeJson(config.deviceId)}","sessionId":"${escapeJson(currentSessionId)}","timestamp":${clock.nowMs()},"payload":{"setupState":"${snapshot.setupState.name}","runtimeState":"${snapshot.runtimeState.name}","uplinkState":"${snapshot.uplinkState.name}"$lastErrorCodeJson$lastErrorMessageJson,$activeCommandJson}}""",
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

    private fun extractStringField(text: String, fieldName: String): String? {
        val regex = Regex("\"$fieldName\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun extractLongField(text: String, fieldName: String): Long? {
        val regex = Regex("\"$fieldName\"\\s*:\\s*(\\d+)")
        return regex.find(text)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
