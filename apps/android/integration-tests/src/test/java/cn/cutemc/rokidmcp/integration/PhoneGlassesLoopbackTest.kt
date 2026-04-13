package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.gateway.CommandDispatcher
import cn.cutemc.rokidmcp.glasses.gateway.ExclusiveExecutionGuard
import cn.cutemc.rokidmcp.glasses.gateway.GlassesAppController
import cn.cutemc.rokidmcp.glasses.gateway.GlassesLocalLinkSession
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeStore
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState
import cn.cutemc.rokidmcp.glasses.gateway.RfcommServerTransport
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.phone.gateway.PhoneHelloConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalLinkSession
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalSessionEvent
import cn.cutemc.rokidmcp.phone.gateway.PhoneTransportEvent
import cn.cutemc.rokidmcp.phone.gateway.PhoneTransportState
import cn.cutemc.rokidmcp.phone.gateway.RfcommClientTransport
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.CapturingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [32])
class PhoneGlassesLoopbackTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `phone handshake reaches ready against glasses loopback transport`() = runTest {
        val pair = LoopbackRfcommPair()
        val phoneEvents = mutableListOf<PhoneLocalSessionEvent>()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = helloConfig(),
            codec = DefaultLocalFrameCodec(),
            clock = PhoneTestClock(1_717_172_100L),
            sessionScope = backgroundScope,
        )
        backgroundScope.launch {
            phoneSession.events.collect { phoneEvents += it }
        }

        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = GlassesTestClock(1_717_172_100L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, pair.server, GlassesTestClock(1_717_172_100L)),
        )

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        glassesSession.start()
        runCurrent()

        pair.connect()
        runCurrent()

        assertTrue(phoneEvents.contains(PhoneLocalSessionEvent.SessionReady))
        assertEquals(1, phoneEvents.count { it is PhoneLocalSessionEvent.SessionReady })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `phone keepalive ping receives pong through loopback`() = runTest {
        val pair = LoopbackRfcommPair()
        val phoneClock = PhoneTestClock(1_717_172_200L)
        val phoneEvents = mutableListOf<PhoneLocalSessionEvent>()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = helloConfig(),
            codec = DefaultLocalFrameCodec(),
            clock = phoneClock,
            sessionScope = backgroundScope,
        )
        backgroundScope.launch {
            phoneSession.events.collect { phoneEvents += it }
        }

        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = GlassesTestClock(1_717_172_200L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, pair.server, GlassesTestClock(1_717_172_200L)),
        )

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        glassesSession.start()
        runCurrent()

        pair.connect()
        runCurrent()

        phoneClock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        val pongEvent = phoneEvents.last { it is PhoneLocalSessionEvent.PongReceived } as PhoneLocalSessionEvent.PongReceived
        assertEquals(1L, pongEvent.seq)
        assertEquals(1, phoneEvents.count { it is PhoneLocalSessionEvent.PongReceived })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `fragmented server delivery preserves hello ack command status and chunk data frames`() = runTest {
        val pair = LoopbackRfcommPair(
            streamConfig = LoopbackStreamConfig(
                serverToClient = LoopbackByteDeliveryMode.Fragmented(
                    chunkSizes = listOf(1, 2, 3, 5, 8, 13),
                ),
            ),
        )
        val phoneEvents = mutableListOf<PhoneLocalSessionEvent>()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = helloConfig(),
            codec = DefaultLocalFrameCodec(),
            clock = PhoneTestClock(1_717_172_300L),
            sessionScope = backgroundScope,
        )
        backgroundScope.launch {
            phoneSession.events.collect { phoneEvents += it }
        }

        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = GlassesTestClock(1_717_172_300L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, pair.server, GlassesTestClock(1_717_172_300L)),
        )

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        glassesSession.start()
        runCurrent()

        pair.connect()
        runCurrent()

        val imageBytes = "chunk-loopback".encodeToByteArray()
        pair.server.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                requestId = "req_fragmented_1",
                timestamp = 1_717_172_301L,
                payload = CapturingCommandStatus(statusAt = 1_717_172_301L),
            ),
        )
        pair.server.send(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_START,
                requestId = "req_fragmented_1",
                transferId = "trf_fragmented_1",
                timestamp = 1_717_172_302L,
                payload = ChunkStart(
                    totalSize = imageBytes.size.toLong(),
                    width = 640,
                    height = 480,
                ),
            ),
        )
        pair.server.send(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_DATA,
                requestId = "req_fragmented_1",
                transferId = "trf_fragmented_1",
                timestamp = 1_717_172_303L,
                payload = ChunkData(
                    index = 0,
                    offset = 0L,
                    size = imageBytes.size,
                    chunkChecksum = LocalProtocolChecksums.crc32(imageBytes),
                ),
            ),
            body = imageBytes,
        )
        pair.server.send(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_END,
                requestId = "req_fragmented_1",
                transferId = "trf_fragmented_1",
                timestamp = 1_717_172_304L,
                payload = ChunkEnd(
                    totalChunks = 1,
                    totalSize = imageBytes.size.toLong(),
                ),
            ),
        )
        runCurrent()

        val frameEvents = phoneEvents.filterIsInstance<PhoneLocalSessionEvent.FrameReceived>()
        assertTrue(phoneEvents.contains(PhoneLocalSessionEvent.SessionReady))
        assertTrue(frameEvents.any { it.header.type == LocalMessageType.COMMAND_STATUS })
        assertTrue(frameEvents.any { it.header.type == LocalMessageType.CHUNK_DATA && it.body?.contentEquals(imageBytes) == true })
        assertTrue(
            phoneEvents.none {
                it is PhoneLocalSessionEvent.SessionFailed &&
                    it.code == LocalProtocolErrorCodes.BLUETOOTH_PROTOCOL_ERROR
            },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `coalesced server delivery releases hello ack and command status together`() = runTest {
        val pair = LoopbackRfcommPair(
            streamConfig = LoopbackStreamConfig(
                serverToClient = LoopbackByteDeliveryMode.Coalesced(
                    framesPerEmission = 2,
                    chunkSizes = listOf(2, 4, 7, 11),
                ),
            ),
        )
        val phoneEvents = mutableListOf<PhoneLocalSessionEvent>()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            helloConfig = helloConfig(),
            codec = DefaultLocalFrameCodec(),
            clock = PhoneTestClock(1_717_172_400L),
            sessionScope = backgroundScope,
        )
        backgroundScope.launch {
            phoneSession.events.collect { phoneEvents += it }
        }

        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = GlassesTestClock(1_717_172_400L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, pair.server, GlassesTestClock(1_717_172_400L)),
        )

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        glassesSession.start()
        runCurrent()

        pair.connect()
        runCurrent()

        pair.server.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                requestId = "req_coalesced_1",
                timestamp = 1_717_172_401L,
                payload = CapturingCommandStatus(statusAt = 1_717_172_401L),
            ),
        )
        runCurrent()

        val frameEvents = phoneEvents.filterIsInstance<PhoneLocalSessionEvent.FrameReceived>()
        assertTrue(phoneEvents.contains(PhoneLocalSessionEvent.SessionReady))
        assertTrue(frameEvents.any { it.header.type == LocalMessageType.COMMAND_STATUS })
        assertTrue(
            phoneEvents.none {
                it is PhoneLocalSessionEvent.SessionFailed &&
                    it.code == LocalProtocolErrorCodes.BLUETOOTH_PROTOCOL_ERROR
            },
        )
    }

    private fun helloConfig() = PhoneHelloConfig(
        deviceId = "abc12345",
        appVersion = "1.0",
        supportedActions = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
    )

    private fun testCommandDispatcher(
        scope: kotlinx.coroutines.CoroutineScope,
        transport: RfcommServerTransport,
        clock: GlassesTestClock,
    ) = CommandDispatcher(
        clock = clock,
        scope = scope,
        frameSender = GlassesFrameSender(transport::send),
        exclusiveGuard = ExclusiveExecutionGuard(),
        displayTextExecutor = DisplayTextExecutor(
            textRenderer = TextRenderer { _, _ -> Unit },
            clock = clock,
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
                clock = clock,
                frameSender = GlassesFrameSender(transport::send),
            ),
            clock = clock,
            frameSender = GlassesFrameSender(transport::send),
        ),
    )
}

private class LoopbackRfcommPair(
    streamConfig: LoopbackStreamConfig = LoopbackStreamConfig(),
) {
    val client = LoopbackClientTransport(streamConfig.clientToServer)
    val server = LoopbackServerTransport(client, streamConfig.serverToClient)

    init {
        client.bind(server)
    }

    suspend fun connect() {
        server.emitConnected()
        client.emitConnected()
    }

    suspend fun flush() {
        client.flushPending()
        server.flushPending()
    }
}

private class LoopbackClientTransport(
    clientToServerDelivery: LoopbackByteDeliveryMode = LoopbackByteDeliveryMode.ExactFrame,
) : RfcommClientTransport {
    private val _state = MutableStateFlow(PhoneTransportState.IDLE)
    override val state: StateFlow<PhoneTransportState> = _state

    private val _events = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<PhoneTransportEvent> = _events
    private val outgoingStream = LoopbackByteStream(clientToServerDelivery)

    private lateinit var peer: LoopbackServerTransport

    fun bind(peer: LoopbackServerTransport) {
        this.peer = peer
    }

    override suspend fun start(targetDeviceAddress: String) = Unit

    override suspend fun send(bytes: ByteArray) {
        outgoingStream.emitFrame(bytes, peer::receiveClientBytes)
    }

    override suspend fun stop(reason: String) = Unit

    suspend fun emitConnected() {
        _state.value = PhoneTransportState.CONNECTED
        _events.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.CONNECTED))
    }

    suspend fun emitFrameBytes(bytes: ByteArray) {
        _events.emit(PhoneTransportEvent.BytesReceived(bytes))
    }

    suspend fun flushPending() {
        outgoingStream.flush(peer::receiveClientBytes)
    }
}

private class LoopbackServerTransport(
    private val client: LoopbackClientTransport,
    serverToClientDelivery: LoopbackByteDeliveryMode = LoopbackByteDeliveryMode.ExactFrame,
) : RfcommServerTransport {
    private val codec = DefaultLocalFrameCodec()
    private val incomingReassembler = LoopbackFrameReassembler()
    private val _state = MutableStateFlow(GlassesTransportState.IDLE)
    override val state: StateFlow<GlassesTransportState> = _state

    private val _events = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<GlassesTransportEvent> = _events
    private val outgoingStream = LoopbackByteStream(serverToClientDelivery)

    override suspend fun start() = Unit

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        outgoingStream.emitFrame(codec.encode(header, body), client::emitFrameBytes)
    }

    override suspend fun stop(reason: String) = Unit

    suspend fun emitConnected() {
        incomingReassembler.reset()
        _state.value = GlassesTransportState.CONNECTED
        _events.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.CONNECTED))
    }

    suspend fun receiveClientBytes(bytes: ByteArray) {
        for (frameBytes in incomingReassembler.append(bytes)) {
            val decoded = codec.decode(frameBytes)
            _events.emit(GlassesTransportEvent.FrameReceived(decoded.header, decoded.body))
        }
    }

    suspend fun flushPending() {
        outgoingStream.flush(client::emitFrameBytes)
    }
}

private class PhoneTestClock(private var nowMs: Long) : cn.cutemc.rokidmcp.phone.gateway.Clock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}

private class GlassesTestClock(private var nowMs: Long) : cn.cutemc.rokidmcp.glasses.gateway.Clock {
    override fun nowMs(): Long = nowMs
}
