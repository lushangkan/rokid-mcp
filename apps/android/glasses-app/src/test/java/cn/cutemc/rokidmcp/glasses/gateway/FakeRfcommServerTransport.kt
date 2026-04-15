package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
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
    var startFailure: Throwable? = null
    var sendFailure: Throwable? = null

    override suspend fun start() {
        startCount += 1
        startFailure?.let { throw it }
        internalState.value = GlassesTransportState.LISTENING
        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.LISTENING))
    }

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        sendFailure?.let { throw it }
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
