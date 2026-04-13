package cn.cutemc.rokidmcp.phone.gateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val RFCOMM_CLIENT_TAG = "rfcomm-client"

internal fun maskBluetoothAddress(address: String): String {
    val octets = address.split(":")
    if (octets.size < 2) {
        return address.takeLast(5).padStart(address.length, '*')
    }

    return List(octets.size - 2) { "**" }
        .plus(octets.takeLast(2))
        .joinToString(":")
}

internal fun interface BluetoothAdapterAvailabilityProvider {
    fun isAvailable(): Boolean
}

internal interface RfcommClientSocket {
    val inputStream: InputStream
    val outputStream: OutputStream

    fun connect()
    fun close()
}

internal fun interface RfcommClientSocketFactory {
    fun create(targetDeviceAddress: String, serviceUuid: UUID): RfcommClientSocket
}

private class AndroidRfcommClientSocketFactory : RfcommClientSocketFactory {
    @SuppressLint("MissingPermission")
    override fun create(targetDeviceAddress: String, serviceUuid: UUID): RfcommClientSocket {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("bluetooth adapter is unavailable")
        val remoteDevice = adapter.getRemoteDevice(targetDeviceAddress)
        val socket = remoteDevice.createRfcommSocketToServiceRecord(serviceUuid)
        return AndroidRfcommClientSocket(socket)
    }
}

private class AndroidRfcommClientSocket(
    private val bluetoothSocket: BluetoothSocket,
) : RfcommClientSocket {
    override val inputStream: InputStream
        get() = bluetoothSocket.inputStream

    override val outputStream: OutputStream
        get() = bluetoothSocket.outputStream

    override fun connect() {
        bluetoothSocket.connect()
    }

    override fun close() {
        bluetoothSocket.close()
    }
}

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

internal class AndroidRfcommClientTransport(
    private val adapterAvailabilityProvider: BluetoothAdapterAvailabilityProvider =
        BluetoothAdapterAvailabilityProvider { BluetoothAdapter.getDefaultAdapter() != null },
    private val socketFactory: RfcommClientSocketFactory = AndroidRfcommClientSocketFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transportScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RfcommClientTransport {
    private companion object {
        /**
         * Keeps the blocking socket reader ahead of frame parsing/event handling during bursty chunk
         * transfers. The queue is intentionally unbounded because the local protocol already caps the
         * total in-flight image size, and dropping bytes here would corrupt the stream.
         */
        val RAW_BYTE_QUEUE_CAPACITY = Channel.UNLIMITED
    }

    private val internalState = MutableStateFlow(PhoneTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<PhoneTransportEvent>(extraBufferCapacity = 32)
    private val writeMutex = Mutex()
    private val closeMutex = Mutex()
    private val serviceUuid = UUID.fromString(LocalProtocolConstants.RFCOMM_SERVICE_UUID)

    private var socket: RfcommClientSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readLoopJob: Job? = null
    private var byteDispatchJob: Job? = null
    private var rawByteQueue: Channel<ByteArray>? = null
    @Volatile
    private var shutdownReason: String? = null

    override val state: StateFlow<PhoneTransportState> = internalState
    override val events: Flow<PhoneTransportEvent> = internalEvents

    override suspend fun start(targetDeviceAddress: String) {
        if (!adapterAvailabilityProvider.isAvailable()) {
            Timber.tag(RFCOMM_CLIENT_TAG).e("bluetooth adapter unavailable")
            throw IllegalStateException("bluetooth adapter is unavailable")
        }
        Timber.tag(RFCOMM_CLIENT_TAG).i("bluetooth adapter available")

        val maskedTargetAddress = maskBluetoothAddress(targetDeviceAddress)
        Timber.tag(RFCOMM_CLIENT_TAG).i("looking up target device address=%s", maskedTargetAddress)

        emitStateTransition(PhoneTransportState.CONNECTING)

        try {
            closeSocketSilently()
            val createdSocket = withContext(ioDispatcher) {
                // This client connects to a known bonded device and never starts discovery itself.
                // Skipping cancelDiscovery avoids Android 12+ treating the call as a scan operation
                // that would otherwise require BLUETOOTH_SCAN at runtime.
                socketFactory.create(targetDeviceAddress, serviceUuid)
            }
            Timber.tag(RFCOMM_CLIENT_TAG).i("socket connect starting target=%s", maskedTargetAddress)
            withContext(ioDispatcher) {
                createdSocket.connect()
            }
            shutdownReason = null
            socket = createdSocket
            inputStream = createdSocket.inputStream
            outputStream = createdSocket.outputStream
            Timber.tag(RFCOMM_CLIENT_TAG).i("socket connect succeeded target=%s", maskedTargetAddress)
            emitStateTransition(PhoneTransportState.CONNECTED)
            startReadLoop(createdSocket, maskedTargetAddress)
        } catch (error: Throwable) {
            Timber.tag(RFCOMM_CLIENT_TAG).e(error, "socket connect failed target=%s", maskedTargetAddress)
            closeSocketSilently()
            emitStateTransition(PhoneTransportState.ERROR, error)
            internalEvents.emit(PhoneTransportEvent.Failure(IOException("failed to connect RFCOMM client", error)))
            throw error
        }
    }

    override suspend fun send(bytes: ByteArray) {
        val currentOutput = outputStream ?: throw IllegalStateException("rfcomm client is not connected")
        writeMutex.withLock {
            try {
                withContext(ioDispatcher) {
                    currentOutput.write(bytes)
                    currentOutput.flush()
                }
                Timber.tag(RFCOMM_CLIENT_TAG).v("wrote %d RFCOMM bytes", bytes.size)
            } catch (error: Throwable) {
                Timber.tag(RFCOMM_CLIENT_TAG).e(error, "failed to write RFCOMM bytes to glasses")
                emitStateTransition(PhoneTransportState.ERROR, error)
                internalEvents.emit(PhoneTransportEvent.Failure(IOException("failed to write RFCOMM bytes", error)))
                throw error
            }
        }
    }

    override suspend fun stop(reason: String) {
        closeSocket(reason, emitClosedEvent = true)
    }

    private fun startReadLoop(connectedSocket: RfcommClientSocket, maskedTargetAddress: String) {
        readLoopJob?.cancel()
        byteDispatchJob?.cancel()

        val byteQueue = Channel<ByteArray>(RAW_BYTE_QUEUE_CAPACITY)
        rawByteQueue = byteQueue
        startByteDispatchLoop(byteQueue)

        readLoopJob = transportScope.launch {
            val buffer = ByteArray(64 * 1024)
            Timber.tag(RFCOMM_CLIENT_TAG).i("read loop started target=%s", maskedTargetAddress)
            try {
                while (true) {
                    val count = withContext(ioDispatcher) {
                        connectedSocket.inputStream.read(buffer)
                    }
                    if (count < 0) {
                        Timber.tag(RFCOMM_CLIENT_TAG).i("remote closed RFCOMM stream target=%s", maskedTargetAddress)
                        handleRemoteClose("rfcomm client stream ended")
                        return@launch
                    }

                    Timber.tag(RFCOMM_CLIENT_TAG).v("read %d RFCOMM bytes", count)
                    byteQueue.send(buffer.copyOf(count))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                handleReadFailure(connectedSocket, maskedTargetAddress, error)
            } catch (error: Throwable) {
                handleReadFailure(
                    connectedSocket,
                    maskedTargetAddress,
                    IOException("rfcomm client read loop failed", error),
                )
            }
        }
    }

    private fun startByteDispatchLoop(byteQueue: Channel<ByteArray>) {
        byteDispatchJob = transportScope.launch {
            try {
                for (bytes in byteQueue) {
                    internalEvents.emit(PhoneTransportEvent.BytesReceived(bytes))
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private suspend fun handleRemoteClose(reason: String) {
        closeSocket(reason, emitClosedEvent = true)
    }

    private suspend fun handleReadFailure(
        connectedSocket: RfcommClientSocket,
        maskedTargetAddress: String,
        error: IOException,
    ) {
        val expectedShutdown = closeMutex.withLock {
            when {
                socket !== connectedSocket -> shutdownReason ?: "socket already closed"
                shutdownReason != null -> shutdownReason
                else -> null
            }
        }
        if (expectedShutdown != null) {
            Timber.tag(RFCOMM_CLIENT_TAG).i(
                "ignoring RFCOMM read failure after shutdown target=%s reason=%s",
                maskedTargetAddress,
                expectedShutdown,
            )
            return
        }

        Timber.tag(RFCOMM_CLIENT_TAG).e(error, "RFCOMM client read loop failed")
        closeSocket(error.message ?: "rfcomm client read failure", emitClosedEvent = false)
        emitStateTransition(PhoneTransportState.ERROR, error)
        internalEvents.emit(PhoneTransportEvent.Failure(error))
    }

    private suspend fun closeSocket(reason: String, emitClosedEvent: Boolean) {
        val currentJob = currentCoroutineContext()[Job]
        val cleanup = closeMutex.withLock {
            val trackedReadLoop = readLoopJob
            val trackedByteDispatch = byteDispatchJob
            val trackedByteQueue = rawByteQueue
            if (trackedReadLoop == null && inputStream == null && outputStream == null && socket == null) {
                return
            }

            readLoopJob = null
            byteDispatchJob = null
            rawByteQueue = null
            shutdownReason = reason
            Timber.tag(RFCOMM_CLIENT_TAG).i("cleaning up RFCOMM resources reason=%s", reason)
            closeSocketSilently(logCleanup = false)
            TransportCleanup(
                readLoopJob = trackedReadLoop,
                byteDispatchJob = trackedByteDispatch,
                rawByteQueue = trackedByteQueue,
            )
        }

        emitStateTransition(PhoneTransportState.DISCONNECTED)
        if (emitClosedEvent) {
            internalEvents.emit(PhoneTransportEvent.ConnectionClosed(reason))
        }
        cleanup.rawByteQueue?.close()
        cleanup.readLoopJob?.takeUnless { it === currentJob }?.cancel()
        cleanup.byteDispatchJob?.takeUnless { it === currentJob }?.cancel()
    }

    private data class TransportCleanup(
        val readLoopJob: Job?,
        val byteDispatchJob: Job?,
        val rawByteQueue: Channel<ByteArray>?,
    )

    private fun closeSocketSilently(logCleanup: Boolean = true) {
        if (logCleanup && (inputStream != null || outputStream != null || socket != null)) {
            Timber.tag(RFCOMM_CLIENT_TAG).i("cleaning up RFCOMM resources")
        }
        closeResourceSilently("RFCOMM input stream") { inputStream?.close() }
        closeResourceSilently("RFCOMM output stream") { outputStream?.close() }
        closeResourceSilently("RFCOMM socket") { socket?.close() }
        inputStream = null
        outputStream = null
        socket = null
    }

    private inline fun closeResourceSilently(resourceName: String, closeAction: () -> Unit) {
        runCatching(closeAction).onFailure { error ->
            Timber.tag(RFCOMM_CLIENT_TAG).w(error, "failed to close %s", resourceName)
        }
    }

    private suspend fun emitStateTransition(state: PhoneTransportState, error: Throwable? = null) {
        when (state) {
            PhoneTransportState.CONNECTING,
            PhoneTransportState.CONNECTED,
            PhoneTransportState.DISCONNECTED
            -> Timber.tag(RFCOMM_CLIENT_TAG).i("transport state -> %s", state)
            PhoneTransportState.ERROR -> {
                if (error != null) {
                    Timber.tag(RFCOMM_CLIENT_TAG).e(error, "transport state -> ERROR")
                } else {
                    Timber.tag(RFCOMM_CLIENT_TAG).e("transport state -> ERROR")
                }
            }
            PhoneTransportState.IDLE -> Unit
        }

        internalState.value = state
        internalEvents.emit(PhoneTransportEvent.StateChanged(state))
    }
}
