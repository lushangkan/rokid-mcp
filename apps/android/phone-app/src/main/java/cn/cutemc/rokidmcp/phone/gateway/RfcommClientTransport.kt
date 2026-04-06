package cn.cutemc.rokidmcp.phone.gateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val serviceUuid = UUID.fromString(LocalProtocolConstants.RFCOMM_SERVICE_UUID)

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readLoopJob: Job? = null

    override val state: StateFlow<PhoneTransportState> = internalState
    override val events: Flow<PhoneTransportEvent> = internalEvents

    @SuppressLint("MissingPermission")
    override suspend fun start(targetDeviceAddress: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("bluetooth adapter is unavailable")
        val remoteDevice = adapter.getRemoteDevice(targetDeviceAddress)

        internalState.value = PhoneTransportState.CONNECTING
        internalEvents.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.CONNECTING))

        try {
            closeSocketSilently()
            val createdSocket = withContext(Dispatchers.IO) {
                adapter.cancelDiscovery()
                remoteDevice.createRfcommSocketToServiceRecord(serviceUuid).also { it.connect() }
            }
            socket = createdSocket
            inputStream = createdSocket.inputStream
            outputStream = createdSocket.outputStream
            internalState.value = PhoneTransportState.CONNECTED
            internalEvents.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.CONNECTED))
            startReadLoop(createdSocket)
        } catch (error: Throwable) {
            closeSocketSilently()
            internalState.value = PhoneTransportState.ERROR
            internalEvents.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.ERROR))
            internalEvents.emit(PhoneTransportEvent.Failure(IOException("failed to connect RFCOMM client", error)))
            throw error
        }
    }

    override suspend fun send(bytes: ByteArray) {
        val currentOutput = outputStream ?: throw IllegalStateException("rfcomm client is not connected")
        writeMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    currentOutput.write(bytes)
                    currentOutput.flush()
                }
            } catch (error: Throwable) {
                internalState.value = PhoneTransportState.ERROR
                internalEvents.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.ERROR))
                internalEvents.emit(PhoneTransportEvent.Failure(IOException("failed to write RFCOMM bytes", error)))
                throw error
            }
        }
    }

    override suspend fun stop(reason: String) {
        closeSocket(reason, emitClosedEvent = true)
    }

    private fun startReadLoop(connectedSocket: BluetoothSocket) {
        readLoopJob?.cancel()
        readLoopJob = transportScope.launch {
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = connectedSocket.inputStream.read(buffer)
                    if (count < 0) {
                        handleRemoteClose("rfcomm client stream ended")
                        return@launch
                    }

                    internalEvents.emit(PhoneTransportEvent.BytesReceived(buffer.copyOf(count)))
                }
            } catch (error: IOException) {
                handleReadFailure(error)
            } catch (error: Throwable) {
                handleReadFailure(IOException("rfcomm client read loop failed", error))
            }
        }
    }

    private suspend fun handleRemoteClose(reason: String) {
        closeSocket(reason, emitClosedEvent = true)
    }

    private suspend fun handleReadFailure(error: IOException) {
        closeSocket(error.message ?: "rfcomm client read failure", emitClosedEvent = false)
        internalState.value = PhoneTransportState.ERROR
        internalEvents.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.ERROR))
        internalEvents.emit(PhoneTransportEvent.Failure(error))
    }

    private suspend fun closeSocket(reason: String, emitClosedEvent: Boolean) {
        readLoopJob?.cancel()
        readLoopJob = null
        closeSocketSilently()
        internalState.value = PhoneTransportState.DISCONNECTED
        internalEvents.emit(PhoneTransportEvent.StateChanged(PhoneTransportState.DISCONNECTED))
        if (emitClosedEvent) {
            internalEvents.emit(PhoneTransportEvent.ConnectionClosed(reason))
        }
    }

    private fun closeSocketSilently() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        inputStream = null
        outputStream = null
        socket = null
    }
}
