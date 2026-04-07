package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.gateway.Clock as GlassesClock
import cn.cutemc.rokidmcp.glasses.gateway.CommandDispatcher
import cn.cutemc.rokidmcp.glasses.gateway.ExclusiveExecutionGuard
import cn.cutemc.rokidmcp.glasses.gateway.GlassesAppController
import cn.cutemc.rokidmcp.glasses.gateway.GlassesLocalLinkSession
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeStore
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState
import cn.cutemc.rokidmcp.glasses.gateway.RfcommServerTransport
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.phone.gateway.ActiveCommandRegistry
import cn.cutemc.rokidmcp.phone.gateway.Clock
import cn.cutemc.rokidmcp.phone.gateway.IncomingImageAssembler
import cn.cutemc.rokidmcp.phone.gateway.LocalCommandForwarder
import cn.cutemc.rokidmcp.phone.gateway.LocalFrameSender
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneHelloConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalLinkSession
import cn.cutemc.rokidmcp.phone.gateway.PhoneTransportEvent
import cn.cutemc.rokidmcp.phone.gateway.PhoneTransportState
import cn.cutemc.rokidmcp.phone.gateway.RelayCommandBridge
import cn.cutemc.rokidmcp.phone.gateway.RelayHttpExecutor
import cn.cutemc.rokidmcp.phone.gateway.RelayHttpResponse
import cn.cutemc.rokidmcp.phone.gateway.RelayImageUploader
import cn.cutemc.rokidmcp.phone.gateway.RelaySessionClient
import cn.cutemc.rokidmcp.phone.gateway.RelaySessionEvent
import cn.cutemc.rokidmcp.phone.gateway.RelayWebSocket
import cn.cutemc.rokidmcp.phone.gateway.RfcommClientTransport
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [32])
class DisplayTextCommandLoopbackTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loopback display text flows from relay to glasses and back`() = runTest {
        val webSocket = RecordingDisplayRelayWebSocket()
        val rendered = mutableListOf<Pair<String, Long>>()
        val relayClient = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeStore(),
            clock = LoopbackDisplayPhoneClock(1_717_194_000L),
            config = PhoneGatewayConfig(
                deviceId = "phone-device",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
        )
        relayClient.onConnected()
        relayClient.onTextMessage(
            """
                {
                  "version":"1.0",
                  "type":"hello_ack",
                  "deviceId":"phone-device",
                  "timestamp":1717194000,
                  "payload":{
                    "sessionId":"ses_loopback",
                    "serverTime":1717194000,
                    "heartbeatIntervalMs":5000,
                    "heartbeatTimeoutMs":15000,
                    "limits":{
                      "maxPendingCommands":1,
                      "maxImageUploadSizeBytes":10485760,
                      "acceptedImageContentTypes":["image/jpeg"]
                    }
                  }
                }
            """.trimIndent(),
        )

        val pair = DisplayLoopbackPair()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = PhoneHelloConfig(
                deviceId = "phone-device",
                appVersion = "1.0",
                supportedActions = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
            ),
            codec = DefaultLocalFrameCodec(),
            clock = LoopbackDisplayPhoneClock(1_717_194_000L),
            sessionScope = backgroundScope,
        )
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = LoopbackDisplayPhoneClock(1_717_194_000L),
            relaySessionClient = relayClient,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = LoopbackDisplayPhoneClock(1_717_194_000L),
                sender = LocalFrameSender { header, body -> phoneSession.sendFrame(header, body) },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(
                httpExecutor = RelayHttpExecutor {
                    RelayHttpResponse(code = 200, body = "{\"ok\":true,\"timestamp\":1717194001}")
                },
            ),
            runtimeUpdater = { _, _, _ -> Unit },
        )
        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = LoopbackDisplayGlassesClock(1_717_194_000L),
            sessionScope = backgroundScope,
            commandDispatcher = CommandDispatcher(
                clock = LoopbackDisplayGlassesClock(1_717_194_000L),
                scope = backgroundScope,
                frameSender = GlassesFrameSender(pair.server::send),
                exclusiveGuard = ExclusiveExecutionGuard(),
                displayTextExecutor = DisplayTextExecutor(
                    textRenderer = TextRenderer { text, durationMs -> rendered += text to durationMs },
                    clock = LoopbackDisplayGlassesClock(1_717_194_000L),
                ),
                capturePhotoExecutor = CapturePhotoExecutor(
                    cameraAdapter = object : CameraAdapter {
                        override suspend fun capture(quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?) = CameraCapture(
                            bytes = "jpeg-loopback".encodeToByteArray(),
                            width = 640,
                            height = 480,
                        )
                    },
                    checksumCalculator = ChecksumCalculator(),
                    imageChunkSender = ImageChunkSender(
                        clock = LoopbackDisplayGlassesClock(1_717_194_000L),
                        frameSender = GlassesFrameSender(pair.server::send),
                    ),
                    clock = LoopbackDisplayGlassesClock(1_717_194_000L),
                    frameSender = GlassesFrameSender(pair.server::send),
                ),
            ),
        )

        backgroundScope.launch {
            phoneSession.events.collect { event ->
                bridge.handleLocalSessionEvent(event)
            }
        }

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        glassesSession.start()
        runCurrent()
        pair.connect()
        runCurrent()

        bridge.handleRelaySessionEvent(
            RelaySessionEvent.CommandDispatched(
                CommandDispatchMessage(
                    deviceId = "phone-device",
                    requestId = "req_display_1",
                    sessionId = "ses_loopback",
                    timestamp = 1_717_194_001L,
                    payload = DisplayTextCommandDispatchPayload(
                        timeoutMs = 30_000L,
                        params = DisplayTextCommandPayload(
                            text = "hello glasses",
                            durationMs = 3_000L,
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(listOf("hello glasses" to 3_000L), rendered)
        val displayingIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"status\":\"displaying\"") }
        val resultIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"type\":\"command_result\"") }
        assertTrue(displayingIndex >= 0)
        assertTrue(resultIndex > displayingIndex)
    }
}

private class DisplayLoopbackPair {
    val client = DisplayLoopbackClientTransport()
    val server = DisplayLoopbackServerTransport(client)

    init {
        client.bind(server)
    }

    suspend fun connect() {
        server.emitConnected()
        client.emitConnected()
    }
}

private class DisplayLoopbackClientTransport : RfcommClientTransport {
    private val _state = MutableStateFlow(PhoneTransportState.IDLE)
    override val state: StateFlow<PhoneTransportState> = _state

    private val _events = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<PhoneTransportEvent> = _events

    private lateinit var peer: DisplayLoopbackServerTransport

    fun bind(peer: DisplayLoopbackServerTransport) {
        this.peer = peer
    }

    override suspend fun start(targetDeviceAddress: String) = Unit

    override suspend fun send(bytes: ByteArray) {
        peer.receiveFromClient(bytes)
    }

    override suspend fun stop(reason: String) = Unit

    suspend fun emitConnected() {
        _state.value = PhoneTransportState.CONNECTED
        _events.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.CONNECTED))
    }

    suspend fun receiveFromServer(bytes: ByteArray) {
        _events.emit(PhoneTransportEvent.BytesReceived(bytes))
    }
}

private class DisplayLoopbackServerTransport(
    private val client: DisplayLoopbackClientTransport,
) : RfcommServerTransport {
    private val codec = DefaultLocalFrameCodec()
    private val _state = MutableStateFlow(GlassesTransportState.IDLE)
    override val state: StateFlow<GlassesTransportState> = _state

    private val _events = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<GlassesTransportEvent> = _events

    override suspend fun start() = Unit

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        client.receiveFromServer(codec.encode(header, body))
    }

    override suspend fun stop(reason: String) = Unit

    suspend fun emitConnected() {
        _state.value = GlassesTransportState.CONNECTED
        _events.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.CONNECTED))
    }

    suspend fun receiveFromClient(bytes: ByteArray) {
        val decoded = codec.decode(bytes)
        _events.emit(GlassesTransportEvent.FrameReceived(decoded.header, decoded.body))
    }
}

private class RecordingDisplayRelayWebSocket : RelayWebSocket {
    val sentTexts: MutableList<String> = mutableListOf()

    override fun sendText(text: String) {
        sentTexts += text
    }

    override fun close(code: Int, reason: String) = Unit
}

private class LoopbackDisplayPhoneClock(private val nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

private class LoopbackDisplayGlassesClock(private val nowMs: Long) : GlassesClock {
    override fun nowMs(): Long = nowMs
}
