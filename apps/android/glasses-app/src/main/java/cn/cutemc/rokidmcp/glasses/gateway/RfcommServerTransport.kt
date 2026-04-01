package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GlassesTransportState {
    IDLE,
    LISTENING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

sealed interface GlassesTransportEvent {
    data class StateChanged(val state: GlassesTransportState) : GlassesTransportEvent

    data class FrameReceived(
        val header: LocalFrameHeader<*>,
        val body: ByteArray? = null,
    ) : GlassesTransportEvent

    data class Failure(val cause: Throwable) : GlassesTransportEvent

    data class ConnectionClosed(val reason: String? = null) : GlassesTransportEvent
}

interface RfcommServerTransport {
    val state: StateFlow<GlassesTransportState>
    val events: Flow<GlassesTransportEvent>

    suspend fun start()
    suspend fun send(header: LocalFrameHeader<*>, body: ByteArray? = null)
    suspend fun stop(reason: String)
}

class AndroidRfcommServerTransport : RfcommServerTransport {
    private val internalState = MutableStateFlow(GlassesTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)

    override val state: StateFlow<GlassesTransportState> = internalState
    override val events: Flow<GlassesTransportEvent> = internalEvents

    override suspend fun start() {
        TODO("Implement Bluetooth RFCOMM server accept loop")
    }

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        TODO("Implement RFCOMM frame encoding and write")
    }

    override suspend fun stop(reason: String) {
        TODO("Implement RFCOMM shutdown and socket cleanup")
    }
}
