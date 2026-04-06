package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.phone.gateway.ActiveCommandRegistry
import cn.cutemc.rokidmcp.phone.gateway.Clock
import cn.cutemc.rokidmcp.phone.gateway.IncomingImageAssembler
import cn.cutemc.rokidmcp.phone.gateway.LocalCommandForwarder
import cn.cutemc.rokidmcp.phone.gateway.LocalFrameSender
import cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneHelloConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalLinkSession
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalSessionEvent
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
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoOutcome
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
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
class RelayCommandBridgeLoopbackTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loopback capture reports terminal result after image upload`() = runTest {
        val imageBytes = "jpeg-loopback".encodeToByteArray()
        val webSocket = RecordingRelayWebSocket()
        val relayClient = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = PhoneRuntimeStore(),
            clock = LoopbackPhoneClock(1_717_173_000L),
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
                  "timestamp":1717173000,
                  "payload":{
                    "sessionId":"ses_loopback",
                    "serverTime":1717173000,
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

        val pair = BridgeLoopbackPair()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = PhoneHelloConfig(
                deviceId = "phone-device",
                appVersion = "1.0",
                supportedActions = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
            ),
            codec = DefaultLocalFrameCodec(),
            clock = LoopbackPhoneClock(1_717_173_000L),
            sessionScope = backgroundScope,
        )
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = LoopbackPhoneClock(1_717_173_000L),
            relaySessionClient = relayClient,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = LoopbackPhoneClock(1_717_173_000L),
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
                                "uploadedAt":1717173005
                              },
                              "timestamp":1717173006
                            }
                        """.trimIndent(),
                    )
                },
            ),
            runtimeUpdater = { _, _, _ -> Unit },
        )
        backgroundScope.launch {
            phoneSession.events.collect { event ->
                bridge.handleLocalSessionEvent(event)
            }
        }

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        pair.connect()
        runCurrent()

        bridge.handleRelaySessionEvent(
            RelaySessionEvent.CommandDispatched(
                CommandDispatchMessage(
                    deviceId = "phone-device",
                    requestId = "req_loopback_1",
                    sessionId = "ses_loopback",
                    timestamp = 1_717_173_001L,
                    payload = CapturePhotoCommandDispatchPayload(
                        timeoutMs = 90_000L,
                        params = CapturePhotoCommandPayload(),
                        image = CommandDispatchImage(
                            imageId = "img_loopback_1",
                            transferId = "trf_loopback_1",
                            uploadToken = "upl_loopback_1",
                            expiresAt = 1_717_273_001L,
                            maxSizeBytes = 8_192L,
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        pair.server.sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ACK,
                requestId = "req_loopback_1",
                timestamp = 1_717_173_002L,
                payload = CommandAck(
                    action = CommandAction.CAPTURE_PHOTO,
                    acceptedAt = 1_717_173_002L,
                    runtimeState = LocalRuntimeState.BUSY,
                ),
            ),
        )
        pair.server.sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_RESULT,
                requestId = "req_loopback_1",
                timestamp = 1_717_173_003L,
                payload = CapturePhotoResult(
                    completedAt = 1_717_173_003L,
                    result = CapturePhotoOutcome(
                        size = imageBytes.size.toLong(),
                        width = 800,
                        height = 600,
                        sha256 = imageBytes.sha256Hex(),
                    ),
                ),
            ),
        )
        runCurrent()
        assertTrue(webSocket.sentTexts.none { it.contains("\"type\":\"command_result\"") })

        pair.server.sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_START,
                requestId = "req_loopback_1",
                transferId = "trf_loopback_1",
                timestamp = 1_717_173_004L,
                payload = ChunkStart(
                    totalSize = imageBytes.size.toLong(),
                    width = 800,
                    height = 600,
                    sha256 = imageBytes.sha256Hex(),
                ),
            ),
        )
        pair.server.sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_DATA,
                requestId = "req_loopback_1",
                transferId = "trf_loopback_1",
                timestamp = 1_717_173_005L,
                payload = ChunkData(
                    index = 0,
                    offset = 0L,
                    size = imageBytes.size,
                    chunkChecksum = LocalProtocolChecksums.crc32(imageBytes),
                ),
            ),
            body = imageBytes,
        )
        pair.server.sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_END,
                requestId = "req_loopback_1",
                transferId = "trf_loopback_1",
                timestamp = 1_717_173_006L,
                payload = ChunkEnd(totalChunks = 1, totalSize = imageBytes.size.toLong(), sha256 = imageBytes.sha256Hex()),
            ),
        )
        runCurrent()

        val resultIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"type\":\"command_result\"") }
        val uploadedIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"status\":\"image_uploaded\"") }
        assertTrue(uploadedIndex >= 0)
        assertTrue(resultIndex > uploadedIndex)
    }
}

private class BridgeLoopbackPair {
    val client = BridgeLoopbackClientTransport()
    val server = BridgeLoopbackServerTransport(client)

    init {
        client.bind(server)
    }

    suspend fun connect() {
        server.emitConnected()
        client.emitConnected()
    }
}

private class BridgeLoopbackClientTransport : RfcommClientTransport {
    private val _state = MutableStateFlow(PhoneTransportState.IDLE)
    override val state: StateFlow<PhoneTransportState> = _state

    private val _events = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<PhoneTransportEvent> = _events

    private lateinit var peer: BridgeLoopbackServerTransport

    fun bind(peer: BridgeLoopbackServerTransport) {
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

private class BridgeLoopbackServerTransport(
    private val client: BridgeLoopbackClientTransport,
) {
    private val codec = DefaultLocalFrameCodec()

    suspend fun emitConnected() = Unit

    suspend fun receiveFromClient(bytes: ByteArray) {
        val frame = codec.decode(bytes)
        when (frame.header.type) {
            LocalMessageType.HELLO -> {
                sendFrame(
                    LocalFrameHeader(
                        type = LocalMessageType.HELLO_ACK,
                        timestamp = 1_717_173_000L,
                        payload = HelloAckPayload(
                            accepted = true,
                            role = LinkRole.GLASSES,
                            capabilities = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
                            runtimeState = LocalRuntimeState.READY,
                        ),
                    ),
                )
            }

            LocalMessageType.PING -> {
                val ping = frame.header.payload as PingPayload
                sendFrame(
                    LocalFrameHeader(
                        type = LocalMessageType.PONG,
                        timestamp = 1_717_173_000L,
                        payload = PongPayload(seq = ping.seq, nonce = ping.nonce),
                    ),
                )
            }

            else -> Unit
        }
    }

    suspend fun sendFrame(header: LocalFrameHeader<*>, body: ByteArray? = null) {
        client.receiveFromServer(codec.encode(header, body))
    }
}

private class RecordingRelayWebSocket : RelayWebSocket {
    val sentTexts: MutableList<String> = mutableListOf()

    override fun sendText(text: String) {
        sentTexts += text
    }

    override fun close(code: Int, reason: String) = Unit
}

private class LoopbackPhoneClock(private val nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
