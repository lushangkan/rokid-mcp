package cn.cutemc.rokidmcp.glasses.gateway

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import cn.cutemc.rokidmcp.glasses.BluetoothPermission
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.IncrementalFrameExtractor
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

private const val RFCOMM_SERVER_LOG_TAG = "rfcomm-server"

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

internal interface RfcommBluetoothAdapter {
    fun listen(serviceName: String, serviceUuid: UUID): RfcommServerSocketHandle
}

internal interface RfcommServerSocketHandle {
    fun accept(): RfcommClientSocketHandle
    fun close()
}

internal interface RfcommClientSocketHandle {
    val remoteDevice: RfcommRemoteDeviceInfo?
    val inputStream: InputStream
    val outputStream: OutputStream

    fun close()
}

internal data class RfcommRemoteDeviceInfo(
    val address: String?,
    val bondState: Int,
)

private class PlatformRfcommBluetoothAdapter(
    private val adapter: BluetoothAdapter,
) : RfcommBluetoothAdapter {
    override fun listen(serviceName: String, serviceUuid: UUID): RfcommServerSocketHandle {
        return PlatformRfcommServerSocketHandle(
            adapter.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid),
        )
    }
}

private class PlatformRfcommServerSocketHandle(
    private val serverSocket: BluetoothServerSocket,
) : RfcommServerSocketHandle {
    override fun accept(): RfcommClientSocketHandle = PlatformRfcommClientSocketHandle(serverSocket.accept())

    override fun close() {
        serverSocket.close()
    }
}

private class PlatformRfcommClientSocketHandle(
    private val socket: BluetoothSocket,
) : RfcommClientSocketHandle {
    override val remoteDevice: RfcommRemoteDeviceInfo?
        get() = socket.remoteDevice?.let { device ->
            RfcommRemoteDeviceInfo(
                address = device.address,
                bondState = device.bondState,
            )
        }

    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    override fun close() {
        socket.close()
    }
}

class AndroidRfcommServerTransport internal constructor(
    private val permissionChecker: () -> Boolean,
    private val adapterProvider: () -> RfcommBluetoothAdapter?,
    private val ioDispatcher: CoroutineDispatcher,
    private val transportScope: CoroutineScope,
    private val codec: LocalFrameCodec,
) : RfcommServerTransport {
    constructor(context: Context) : this(
        permissionChecker = { BluetoothPermission.hasRequiredPermission(context) },
        adapterProvider = {
            BluetoothAdapter.getDefaultAdapter()?.let(::PlatformRfcommBluetoothAdapter)
        },
        ioDispatcher = Dispatchers.IO,
        transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        codec = DefaultLocalFrameCodec(),
    )

    private val internalState = MutableStateFlow(GlassesTransportState.IDLE)
    private val internalEvents = MutableSharedFlow<GlassesTransportEvent>(extraBufferCapacity = 32)
    private val writeMutex = Mutex()
    private val closeMutex = Mutex()
    private val incomingFrameExtractor = IncrementalFrameExtractor()
    private val serviceUuid = UUID.fromString(LocalProtocolConstants.RFCOMM_SERVICE_UUID)

    private var serverSocket: RfcommServerSocketHandle? = null
    private var clientSocket: RfcommClientSocketHandle? = null
    private var acceptLoopJob: Job? = null
    private var readLoopJob: Job? = null
    @Volatile
    private var shutdownReason: String? = null

    override val state: StateFlow<GlassesTransportState> = internalState
    override val events: Flow<GlassesTransportEvent> = internalEvents

    override suspend fun start() {
        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("validating BLUETOOTH_CONNECT permission")
        check(permissionChecker()) {
            Timber.tag(RFCOMM_SERVER_LOG_TAG).w("RFCOMM start blocked: BLUETOOTH_CONNECT permission denied")
            "bluetooth connect permission is not granted"
        }
        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("BLUETOOTH_CONNECT permission granted")

        try {
            Timber.tag(RFCOMM_SERVER_LOG_TAG).d("checking bluetooth adapter availability")
            val adapter = adapterProvider()
                ?: throw IllegalStateException("bluetooth adapter is unavailable")
            Timber.tag(RFCOMM_SERVER_LOG_TAG).d("bluetooth adapter available")

            closeMutex.withLock {
                shutdownReason = null
                closeSocketsSilently("restart-before-listen")
            }
            Timber.tag(RFCOMM_SERVER_LOG_TAG).d(
                "creating RFCOMM listen socket service=%s uuid=%s",
                LocalProtocolConstants.RFCOMM_SERVICE_NAME,
                serviceUuid,
            )
            serverSocket = withContext(ioDispatcher) {
                adapter.listen(
                    LocalProtocolConstants.RFCOMM_SERVICE_NAME,
                    serviceUuid,
                )
            }
            Timber.tag(RFCOMM_SERVER_LOG_TAG).d("RFCOMM listen socket created")
            transitionTo(GlassesTransportState.LISTENING, reason = "server socket ready")

            acceptLoopJob?.cancel()
            acceptLoopJob = transportScope.launch {
                while (true) {
                    val listeningSocket = serverSocket ?: return@launch
                    try {
                        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("waiting for RFCOMM client")
                        val acceptedSocket = listeningSocket.accept()
                        val remoteDevice = acceptedSocket.remoteDevice
                        val maskedDevice = remoteDevice.maskedAddress()
                        Timber.tag(RFCOMM_SERVER_LOG_TAG).d(
                            "accepted RFCOMM socket device=%s bondState=%s",
                            maskedDevice,
                            remoteDevice?.bondState ?: "unknown",
                        )
                        if (remoteDevice?.bondState != BluetoothDevice.BOND_BONDED) {
                            Timber.tag(RFCOMM_SERVER_LOG_TAG).w(
                                "rejected unbonded RFCOMM client device=%s bondState=%s",
                                maskedDevice,
                                remoteDevice?.bondState ?: "unknown",
                            )
                            closeResourceSilently("unbonded RFCOMM client socket") { acceptedSocket.close() }
                            continue
                        }

                        Timber.tag(RFCOMM_SERVER_LOG_TAG).d(
                            "accepted bonded RFCOMM client device=%s",
                            maskedDevice,
                        )

                        attachClient(acceptedSocket)
                    } catch (error: IOException) {
                        if (handleAcceptFailure(listeningSocket, error)) {
                            return@launch
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }

            Timber.tag(RFCOMM_SERVER_LOG_TAG).e(error, "failed to start RFCOMM server transport")
            closeSocketsSilently("start-failure")
            transitionTo(GlassesTransportState.ERROR, reason = "start failed", error = error)
            internalEvents.emit(GlassesTransportEvent.Failure(IOException("rfcomm server start failed", error)))
            throw error
        }
    }

    override suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?) {
        writeMutex.withLock {
            try {
                val currentClient = clientSocket ?: throw IllegalStateException("rfcomm server has no connected client")
                val encoded = codec.encode(header, body)
                logFrameMetadata(direction = "sent", header = header, body = body, frameBytes = encoded.size)
                withContext(ioDispatcher) {
                    currentClient.outputStream.write(encoded)
                    currentClient.outputStream.flush()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Timber.tag(RFCOMM_SERVER_LOG_TAG).e(error, "failed to write RFCOMM frame to connected client")
                handleClientFailure(IOException("failed to write RFCOMM frame", error))
                throw error
            }
        }
    }

    override suspend fun stop(reason: String) {
        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("stopping RFCOMM server transport reason=%s", reason)
        val currentJob = currentCoroutineContext()[Job]
        val cleanup = closeMutex.withLock {
            shutdownReason = reason
            val trackedAcceptLoop = acceptLoopJob
            val trackedReadLoop = readLoopJob
            acceptLoopJob = null
            readLoopJob = null
            closeSocketsSilently("stop:$reason")
            TransportCleanup(
                acceptLoopJob = trackedAcceptLoop,
                readLoopJob = trackedReadLoop,
            )
        }

        cleanup.acceptLoopJob?.takeUnless { it === currentJob }?.cancel()
        cleanup.readLoopJob?.takeUnless { it === currentJob }?.cancel()
        transitionTo(GlassesTransportState.DISCONNECTED, reason = reason)
        internalEvents.emit(GlassesTransportEvent.ConnectionClosed(reason))
    }

    private suspend fun attachClient(socket: RfcommClientSocketHandle) {
        val maskedDevice = socket.remoteDevice.maskedAddress()
        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("attaching RFCOMM client device=%s", maskedDevice)
        incomingFrameExtractor.reset()
        closeResourceSilently("previous RFCOMM client socket") { clientSocket?.close() }
        clientSocket = socket
        transitionTo(GlassesTransportState.CONNECTED, reason = "client attached device=$maskedDevice")

        readLoopJob?.cancel()
        readLoopJob = transportScope.launch {
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = socket.inputStream.read(buffer)
                    if (count < 0) {
                        handleClientStreamClosed(
                            socket = socket,
                            reason = "rfcomm server client disconnected",
                        )
                        return@launch
                    }

                    val frames = incomingFrameExtractor.append(buffer, offset = 0, length = count)
                    for (frame in frames) {
                        val decoded = codec.decode(frame)
                        logFrameMetadata(
                            direction = "received",
                            header = decoded.header,
                            body = decoded.body,
                            frameBytes = frame.size,
                        )
                        internalEvents.emit(GlassesTransportEvent.FrameReceived(decoded.header, decoded.body))
                    }
                }
            } catch (error: IOException) {
                handleClientReadFailure(socket, error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleClientFailure(IOException("rfcomm server frame decode failed", error))
            }
        }
    }

    private suspend fun handleClientReadFailure(
        socket: RfcommClientSocketHandle,
        error: IOException,
    ) {
        val expectedShutdown = expectedShutdownReasonForClient(socket)
        if (expectedShutdown != null) {
            Timber.tag(RFCOMM_SERVER_LOG_TAG).i(
                error,
                "ignoring RFCOMM read failure after shutdown reason=%s",
                expectedShutdown,
            )
            return
        }

        if (error.isSocketClosedAfterRemoteDisconnect()) {
            handleClientDisconnected("rfcomm server client disconnected")
            return
        }

        handleClientFailure(IOException("rfcomm server read failed", error))
    }

    private suspend fun handleClientStreamClosed(
        socket: RfcommClientSocketHandle,
        reason: String,
    ) {
        val expectedShutdown = expectedShutdownReasonForClient(socket)
        if (expectedShutdown != null) {
            Timber.tag(RFCOMM_SERVER_LOG_TAG).i(
                "ignoring RFCOMM client close after shutdown reason=%s",
                expectedShutdown,
            )
            return
        }

        handleClientDisconnected(reason)
    }

    private suspend fun handleAcceptFailure(
        listeningSocket: RfcommServerSocketHandle,
        error: IOException,
    ): Boolean {
        val expectedShutdown = expectedShutdownReasonForAccept(listeningSocket)
        if (expectedShutdown != null) {
            Timber.tag(RFCOMM_SERVER_LOG_TAG).i(
                error,
                "ignoring RFCOMM server accept failure after shutdown reason=%s",
                expectedShutdown,
            )
            return true
        }

        Timber.tag(RFCOMM_SERVER_LOG_TAG).e(error, "RFCOMM server accept loop failed")
        transitionTo(GlassesTransportState.ERROR, reason = "accept loop failed", error = error)
        internalEvents.emit(GlassesTransportEvent.Failure(IOException("rfcomm server accept failed", error)))
        return true
    }

    private suspend fun handleClientDisconnected(reason: String) {
        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("RFCOMM client disconnected reason=%s", reason)
        incomingFrameExtractor.reset()
        closeResourceSilently("RFCOMM client socket after disconnect") { clientSocket?.close() }
        clientSocket = null
        transitionTo(GlassesTransportState.LISTENING, reason = "waiting for next client")
        internalEvents.emit(GlassesTransportEvent.ConnectionClosed(reason))
    }

    private suspend fun handleClientFailure(error: IOException) {
        Timber.tag(RFCOMM_SERVER_LOG_TAG).e(error, "RFCOMM server client connection failed")
        incomingFrameExtractor.reset()
        closeResourceSilently("RFCOMM client socket after failure") { clientSocket?.close() }
        clientSocket = null
        transitionTo(GlassesTransportState.ERROR, reason = "client connection failed", error = error)
        internalEvents.emit(GlassesTransportEvent.Failure(error))
    }

    private suspend fun transitionTo(
        state: GlassesTransportState,
        reason: String,
        error: Throwable? = null,
    ) {
        internalState.value = state
        logStateTransition(state = state, reason = reason, error = error)
        internalEvents.emit(GlassesTransportEvent.StateChanged(state))
    }

    private fun logStateTransition(
        state: GlassesTransportState,
        reason: String,
        error: Throwable? = null,
    ) {
        val message = "state -> ${state.name} reason=$reason"
        when (state) {
            GlassesTransportState.LISTENING,
            GlassesTransportState.CONNECTED,
            -> Timber.tag(RFCOMM_SERVER_LOG_TAG).i(message)

            GlassesTransportState.ERROR -> {
                if (error != null) {
                    Timber.tag(RFCOMM_SERVER_LOG_TAG).e(error, message)
                } else {
                    Timber.tag(RFCOMM_SERVER_LOG_TAG).e(message)
                }
            }

            GlassesTransportState.DISCONNECTED,
            GlassesTransportState.IDLE,
            -> Timber.tag(RFCOMM_SERVER_LOG_TAG).d(message)
        }
    }

    private fun logFrameMetadata(
        direction: String,
        header: LocalFrameHeader<*>,
        body: ByteArray?,
        frameBytes: Int,
    ) {
        Timber.tag(RFCOMM_SERVER_LOG_TAG).v(
            "frame %s type=%s requestId=%s transferId=%s bodyBytes=%d frameBytes=%d",
            direction,
            header.type.name,
            header.requestId ?: "-",
            header.transferId ?: "-",
            body?.size ?: 0,
            frameBytes,
        )
    }

    private fun closeSocketsSilently(reason: String) {
        if (clientSocket == null && serverSocket == null) {
            incomingFrameExtractor.reset()
            return
        }

        Timber.tag(RFCOMM_SERVER_LOG_TAG).d("cleaning up RFCOMM sockets reason=%s", reason)
        closeResourceSilently("RFCOMM client socket") { clientSocket?.close() }
        closeResourceSilently("RFCOMM server socket") { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
        incomingFrameExtractor.reset()
    }

    private inline fun closeResourceSilently(resourceName: String, closeAction: () -> Unit) {
        runCatching(closeAction).onFailure { error ->
            Timber.tag(RFCOMM_SERVER_LOG_TAG).w(error, "failed to close $resourceName")
        }
    }

    private suspend fun expectedShutdownReasonForClient(socket: RfcommClientSocketHandle): String? {
        return closeMutex.withLock {
            when {
                clientSocket !== socket -> shutdownReason ?: "client socket already closed"
                shutdownReason != null -> shutdownReason
                else -> null
            }
        }
    }

    private suspend fun expectedShutdownReasonForAccept(listeningSocket: RfcommServerSocketHandle): String? {
        return closeMutex.withLock {
            when {
                serverSocket !== listeningSocket -> shutdownReason ?: "server socket already closed"
                shutdownReason != null -> shutdownReason
                else -> null
            }
        }
    }

    private data class TransportCleanup(
        val acceptLoopJob: Job?,
        val readLoopJob: Job?,
    )
}

private fun RfcommRemoteDeviceInfo?.maskedAddress(): String = maskBluetoothMacAddress(this?.address)

private fun IOException.isSocketClosedAfterRemoteDisconnect(): Boolean {
    return generateSequence<Throwable>(this) { it.cause }
        .mapNotNull { it.message?.lowercase() }
        .any { message ->
            val readReturnedMinusOne = "read return: -1" in message || "read ret: -1" in message
            readReturnedMinusOne && (
                "bt socket closed" in message ||
                    "socket closed" in message ||
                    "socket might closed" in message
            )
        }
}

private fun maskBluetoothMacAddress(address: String?): String {
    if (address.isNullOrBlank()) {
        return "unknown"
    }

    val octets = address.split(':')
    if (octets.size < 2) {
        return "unknown"
    }

    return "**:**:**:**:${octets.takeLast(2).joinToString(":") { it.uppercase() }}"
}
