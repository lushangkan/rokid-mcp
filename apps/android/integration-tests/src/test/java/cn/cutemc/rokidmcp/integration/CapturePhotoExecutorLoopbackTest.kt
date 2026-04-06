package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.gateway.Clock as GlassesClock
import cn.cutemc.rokidmcp.glasses.gateway.GlassesAppController
import cn.cutemc.rokidmcp.glasses.gateway.GlassesLocalLinkSession
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeStore
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState
import cn.cutemc.rokidmcp.glasses.gateway.RfcommServerTransport
import cn.cutemc.rokidmcp.glasses.sender.EncodedLocalFrameSender
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
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeStore
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
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchImage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [32])
class CapturePhotoExecutorLoopbackTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loopback capture uses glasses executor and uploads before terminal result`() = runTest {
        val imageBytes = "jpeg-loopback".encodeToByteArray()
        val webSocket = ExecutorRelayWebSocket()
        val relayClient = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = PhoneRuntimeStore(),
            clock = ExecutorLoopbackPhoneClock(1_717_183_000L),
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
                  "timestamp":1717183000,
                  "payload":{
                    "sessionId":"ses_loopback",
                    "serverTime":1717183000,
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

        val pair = ExecutorLoopbackPair()
        val localCodec = DefaultLocalFrameCodec()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = PhoneHelloConfig(
                deviceId = "phone-device",
                appVersion = "1.0",
                supportedActions = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
            ),
            codec = localCodec,
            clock = ExecutorLoopbackPhoneClock(1_717_183_000L),
            sessionScope = backgroundScope,
        )
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = ExecutorLoopbackPhoneClock(1_717_183_000L),
            relaySessionClient = relayClient,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = ExecutorLoopbackPhoneClock(1_717_183_000L),
                sender = LocalFrameSender { header, body -> phoneSession.sendFrame(header, body) },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(
                httpExecutor = RelayHttpExecutor {
                    RelayHttpResponse(
                        code = 200,
                        body = """
                            {
                              "ok":true,
                              "image":{
                                "imageId":"img_loopback_1",
                                "transferId":"trf_loopback_1",
                                "status":"UPLOADED",
                                "mimeType":"image/jpeg",
                                "size":${imageBytes.size},
                                "sha256":"${imageBytes.sha256Hex()}",
                                "uploadedAt":1717183005
                              },
                              "timestamp":1717183006
                            }
                        """.trimIndent(),
                    )
                },
            ),
            runtimeUpdater = { _, _, _ -> Unit },
        )
        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = ExecutorLoopbackGlassesClock(1_717_183_000L),
            sessionScope = backgroundScope,
            capturePhotoExecutor = CapturePhotoExecutor(
                cameraAdapter = object : CameraAdapter {
                    override suspend fun capture(quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?) =
                        CameraCapture(
                            bytes = imageBytes,
                            width = 800,
                            height = 600,
                        )
                },
                checksumCalculator = ChecksumCalculator(),
                imageChunkSender = ImageChunkSender(
                    codec = localCodec,
                    clock = ExecutorLoopbackGlassesClock(1_717_183_000L),
                    frameSender = EncodedLocalFrameSender { frameBytes ->
                        val decoded = localCodec.decode(frameBytes)
                        pair.server.send(decoded.header, decoded.body)
                    },
                    chunkSizeBytes = 4,
                ),
                clock = ExecutorLoopbackGlassesClock(1_717_183_000L),
                frameSender = GlassesFrameSender(pair.server::send),
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
                    requestId = "req_loopback_1",
                    sessionId = "ses_loopback",
                    timestamp = 1_717_183_001L,
                    payload = CapturePhotoCommandDispatchPayload(
                        timeoutMs = 90_000L,
                        params = CapturePhotoCommandPayload(),
                        image = CommandDispatchImage(
                            imageId = "img_loopback_1",
                            transferId = "trf_loopback_1",
                            uploadToken = "upl_loopback_1",
                            expiresAt = 1_717_283_001L,
                            maxSizeBytes = 8_192L,
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        val resultIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"type\":\"command_result\"") }
        val uploadedIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"status\":\"image_uploaded\"") }
        assertTrue(uploadedIndex >= 0)
        assertTrue(resultIndex > uploadedIndex)
    }
}

private class ExecutorLoopbackPair {
    val client = ExecutorLoopbackClientTransport()
    val server = ExecutorLoopbackServerTransport(client)

    init {
        client.bind(server)
    }

    suspend fun connect() {
        server.emitConnected()
        client.emitConnected()
    }
}

private class ExecutorLoopbackClientTransport : RfcommClientTransport {
    private val _state = MutableStateFlow(PhoneTransportState.IDLE)
    override val state: StateFlow<PhoneTransportState> = _state

    private val _events = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<PhoneTransportEvent> = _events

    private lateinit var peer: ExecutorLoopbackServerTransport

    fun bind(peer: ExecutorLoopbackServerTransport) {
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

private class ExecutorLoopbackServerTransport(
    private val client: ExecutorLoopbackClientTransport,
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

private class ExecutorRelayWebSocket : RelayWebSocket {
    val sentTexts: MutableList<String> = mutableListOf()

    override fun sendText(text: String) {
        sentTexts += text
    }

    override fun close(code: Int, reason: String) = Unit
}

private class ExecutorLoopbackPhoneClock(private val nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

private class ExecutorLoopbackGlassesClock(private val nowMs: Long) : GlassesClock {
    override fun nowMs(): Long = nowMs
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
