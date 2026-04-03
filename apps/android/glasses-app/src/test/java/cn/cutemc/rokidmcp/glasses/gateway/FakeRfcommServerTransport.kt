package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SentFrame(
    val header: LocalFrameHeader<*>,
    val body: ByteArray? = null,
)

class FakeRfcommServerTransport : RfcommServerTransport {
    private val internalState = MutableStateFlow(GlassesTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)

    override val state: StateFlow<GlassesTransportState> = internalState
    override val events: Flow<GlassesTransportEvent> = internalEvents

    val sentFrames: MutableList<SentFrame> = mutableListOf()
    val stopReasons: MutableList<String> = mutableListOf()
    var startCount: Int = 0

    override suspend fun start() {
        startCount += 1
        internalState.value = GlassesTransportState.LISTENING
        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.LISTENING))
    }

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        sentFrames += SentFrame(header = header, body = body)
    }

    override suspend fun stop(reason: String) {
        stopReasons += reason
        internalState.value = GlassesTransportState.DISCONNECTED
        internalEvents.emit(GlassesTransportEvent.ConnectionClosed(reason))
    }

    suspend fun emit(event: GlassesTransportEvent) {
        internalEvents.emit(event)
    }
}
