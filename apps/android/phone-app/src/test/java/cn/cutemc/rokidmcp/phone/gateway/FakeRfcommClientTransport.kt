package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeRfcommClientTransport : RfcommClientTransport {
    private val internalState = MutableStateFlow(PhoneTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)

    override val state: StateFlow<PhoneTransportState> = internalState
    override val events: Flow<PhoneTransportEvent> = internalEvents

    val sentBytes: MutableList<ByteArray> = mutableListOf()
    val stopReasons: MutableList<String> = mutableListOf()
    val startAddresses: MutableList<String> = mutableListOf()
    var startCount: Int = 0

    override suspend fun start(targetDeviceAddress: String) {
        startCount += 1
        startAddresses += targetDeviceAddress
        updateState(PhoneTransportState.CONNECTING)
    }

    override suspend fun send(bytes: ByteArray) {
        sentBytes += bytes
    }

    override suspend fun stop(reason: String) {
        stopReasons += reason
        updateState(PhoneTransportState.DISCONNECTED)
        internalEvents.emit(PhoneTransportEvent.ConnectionClosed(reason))
    }

    suspend fun updateState(state: PhoneTransportState) {
        internalState.value = state
        internalEvents.emit(PhoneTransportEvent.StateChanged(state))
    }

    suspend fun emitBytes(bytes: ByteArray) {
        internalEvents.emit(PhoneTransportEvent.BytesReceived(bytes))
    }

    suspend fun emit(event: PhoneTransportEvent) {
        internalEvents.emit(event)
    }
}
