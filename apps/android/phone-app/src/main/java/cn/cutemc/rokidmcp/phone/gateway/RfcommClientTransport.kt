package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PhoneTransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

sealed interface PhoneTransportEvent {
    data class StateChanged(val state: PhoneTransportState) : PhoneTransportEvent

    data class BytesReceived(val bytes: ByteArray) : PhoneTransportEvent

    data class Failure(val cause: Throwable) : PhoneTransportEvent

    data class ConnectionClosed(val reason: String? = null) : PhoneTransportEvent
}

interface RfcommClientTransport {
    val state: StateFlow<PhoneTransportState>
    val events: Flow<PhoneTransportEvent>

    suspend fun start(targetDeviceAddress: String)
    suspend fun send(bytes: ByteArray)
    suspend fun stop(reason: String)
}

class AndroidRfcommClientTransport : RfcommClientTransport {
    private val internalState = MutableStateFlow(PhoneTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)

    override val state: StateFlow<PhoneTransportState> = internalState
    override val events: Flow<PhoneTransportEvent> = internalEvents

    override suspend fun start(targetDeviceAddress: String) {
        TODO("Implement Bluetooth RFCOMM client connect loop")
    }

    override suspend fun send(bytes: ByteArray) {
        TODO("Implement RFCOMM byte-stream write")
    }

    override suspend fun stop(reason: String) {
        TODO("Implement RFCOMM shutdown and socket cleanup")
    }
}
