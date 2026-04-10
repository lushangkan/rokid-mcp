package cn.cutemc.rokidmcp.glasses.gateway

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import cn.cutemc.rokidmcp.glasses.BluetoothPermission
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
import timber.log.Timber

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

class AndroidRfcommServerTransport(
    private val context: Context,
) : RfcommServerTransport {
    private val internalState = MutableStateFlow(GlassesTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val codec = DefaultLocalFrameCodec()
    private val serviceUuid = UUID.fromString(LocalProtocolConstants.RFCOMM_SERVICE_UUID)

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var acceptLoopJob: Job? = null
    private var readLoopJob: Job? = null

    override val state: StateFlow<GlassesTransportState> = internalState
    override val events: Flow<GlassesTransportEvent> = internalEvents

    override suspend fun start() {
        check(BluetoothPermission.hasRequiredPermission(context)) {
            "bluetooth connect permission is not granted"
        }

        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: throw IllegalStateException("bluetooth adapter is unavailable")

            closeSocketsSilently()
            serverSocket = withContext(Dispatchers.IO) {
                adapter.listenUsingRfcommWithServiceRecord(
                    LocalProtocolConstants.RFCOMM_SERVICE_NAME,
                    serviceUuid,
                )
            }
            internalState.value = GlassesTransportState.LISTENING
            internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.LISTENING))

            acceptLoopJob?.cancel()
            acceptLoopJob = transportScope.launch {
                while (true) {
                    val listeningSocket = serverSocket ?: return@launch
                    try {
                        val acceptedSocket = listeningSocket.accept()
                        if (acceptedSocket.remoteDevice?.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                            closeResourceSilently("unbonded RFCOMM client socket") { acceptedSocket.close() }
                            continue
                        }

                        attachClient(acceptedSocket)
                    } catch (error: IOException) {
                        if (serverSocket == null) {
                            return@launch
                        }

                        Timber.tag("rfcomm-server").e(error, "RFCOMM server accept loop failed")
                        internalState.value = GlassesTransportState.ERROR
                        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.ERROR))
                        internalEvents.emit(GlassesTransportEvent.Failure(IOException("rfcomm server accept failed", error)))
                        return@launch
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }

            Timber.tag("rfcomm-server").e(error, "failed to start RFCOMM server transport")
            closeSocketsSilently()
            internalState.value = GlassesTransportState.ERROR
            internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.ERROR))
            internalEvents.emit(GlassesTransportEvent.Failure(IOException("rfcomm server start failed", error)))
            throw error
        }
    }

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        writeMutex.withLock {
            try {
                val currentClient = clientSocket ?: throw IllegalStateException("rfcomm server has no connected client")
                val encoded = codec.encode(header, body)
                withContext(Dispatchers.IO) {
                    currentClient.outputStream.write(encoded)
                    currentClient.outputStream.flush()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Timber.tag("rfcomm-server").e(error, "failed to write RFCOMM frame to connected client")
                internalState.value = GlassesTransportState.ERROR
                internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.ERROR))
                internalEvents.emit(GlassesTransportEvent.Failure(IOException("failed to write RFCOMM frame", error)))
                throw error
            }
        }
    }

    override suspend fun stop(reason: String) {
        acceptLoopJob?.cancel()
        acceptLoopJob = null
        readLoopJob?.cancel()
        readLoopJob = null
        closeSocketsSilently()
        internalState.value = GlassesTransportState.DISCONNECTED
        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.DISCONNECTED))
        internalEvents.emit(GlassesTransportEvent.ConnectionClosed(reason))
    }

    private suspend fun attachClient(socket: BluetoothSocket) {
        closeResourceSilently("previous RFCOMM client socket") { clientSocket?.close() }
        clientSocket = socket
        internalState.value = GlassesTransportState.CONNECTED
        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.CONNECTED))

        readLoopJob?.cancel()
        readLoopJob = transportScope.launch {
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = socket.inputStream.read(buffer)
                    if (count < 0) {
                        handleClientDisconnected("rfcomm server client disconnected")
                        return@launch
                    }

                    val decoded = codec.decode(buffer.copyOf(count))
                    internalEvents.emit(GlassesTransportEvent.FrameReceived(decoded.header, decoded.body))
                }
            } catch (error: IOException) {
                handleClientFailure(IOException("rfcomm server read failed", error))
            } catch (error: Throwable) {
                handleClientFailure(IOException("rfcomm server frame decode failed", error))
            }
        }
    }

    private suspend fun handleClientDisconnected(reason: String) {
        closeResourceSilently("RFCOMM client socket after disconnect") { clientSocket?.close() }
        clientSocket = null
        internalState.value = GlassesTransportState.LISTENING
        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.LISTENING))
        internalEvents.emit(GlassesTransportEvent.ConnectionClosed(reason))
    }

    private suspend fun handleClientFailure(error: IOException) {
        Timber.tag("rfcomm-server").e(error, "RFCOMM server client connection failed")
        closeResourceSilently("RFCOMM client socket after failure") { clientSocket?.close() }
        clientSocket = null
        internalState.value = GlassesTransportState.ERROR
        internalEvents.emit(GlassesTransportEvent.StateChanged(GlassesTransportState.ERROR))
        internalEvents.emit(GlassesTransportEvent.Failure(error))
    }

    private fun closeSocketsSilently() {
        closeResourceSilently("RFCOMM client socket") { clientSocket?.close() }
        closeResourceSilently("RFCOMM server socket") { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
    }

    private inline fun closeResourceSilently(resourceName: String, closeAction: () -> Unit) {
        runCatching(closeAction).onFailure { error ->
            Timber.tag("rfcomm-server").w(error, "failed to close $resourceName")
        }
    }
}
