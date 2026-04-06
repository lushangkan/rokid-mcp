package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.glasses.gateway.GlassesAppController
import cn.cutemc.rokidmcp.glasses.gateway.GlassesLocalLinkSession
import cn.cutemc.rokidmcp.glasses.gateway.GlassesRuntimeStore
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent
import cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState
import cn.cutemc.rokidmcp.glasses.gateway.RfcommServerTransport
import cn.cutemc.rokidmcp.phone.gateway.PhoneHelloConfig
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalLinkSession
import cn.cutemc.rokidmcp.phone.gateway.PhoneLocalSessionEvent
import cn.cutemc.rokidmcp.phone.gateway.PhoneTransportEvent
import cn.cutemc.rokidmcp.phone.gateway.PhoneTransportState
import cn.cutemc.rokidmcp.phone.gateway.RfcommClientTransport
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
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
        )

        phoneSession.start(targetDeviceAddress = "00:11:22:33:44:55")
        glassesSession.start()
        runCurrent()

        pair.connect()
        runCurrent()

        assertTrue(phoneEvents.contains(PhoneLocalSessionEvent.SessionReady))
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
    }

    private fun helloConfig() = PhoneHelloConfig(
        deviceId = "abc12345",
        appVersion = "1.0",
        supportedActions = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
    )
}

private class LoopbackRfcommPair {
    val client = LoopbackClientTransport()
    val server = LoopbackServerTransport(client)

    init {
        client.bind(server)
    }

    suspend fun connect() {
        server.emitConnected()
        client.emitConnected()
    }
}

private class LoopbackClientTransport : RfcommClientTransport {
    private val _state = MutableStateFlow(PhoneTransportState.IDLE)
    override val state: StateFlow<PhoneTransportState> = _state

    private val _events = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<PhoneTransportEvent> = _events

    private lateinit var peer: LoopbackServerTransport

    fun bind(peer: LoopbackServerTransport) {
        this.peer = peer
    }

    override suspend fun start(targetDeviceAddress: String) = Unit

    override suspend fun send(bytes: ByteArray) {
        peer.emitFrameBytes(bytes)
    }

    override suspend fun stop(reason: String) = Unit

    suspend fun emitConnected() {
        _state.value = PhoneTransportState.CONNECTED
        _events.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.CONNECTED))
    }

    suspend fun emitFrameBytes(bytes: ByteArray) {
        _events.emit(PhoneTransportEvent.BytesReceived(bytes))
    }
}

private class LoopbackServerTransport(
    private val client: LoopbackClientTransport,
) : RfcommServerTransport {
    private val codec = DefaultLocalFrameCodec()
    private val _state = MutableStateFlow(GlassesTransportState.IDLE)
    override val state: StateFlow<GlassesTransportState> = _state

    private val _events = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)
    override val events: Flow<GlassesTransportEvent> = _events

    override suspend fun start() = Unit

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        client.emitFrameBytes(codec.encode(header, body))
    }

    override suspend fun stop(reason: String) = Unit

    suspend fun emitConnected() {
        _state.value = GlassesTransportState.CONNECTED
        _events.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.CONNECTED))
    }

    suspend fun emitFrameBytes(bytes: ByteArray) {
        val decoded = codec.decode(bytes)
        _events.emit(GlassesTransportEvent.FrameReceived(decoded.header, decoded.body))
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
