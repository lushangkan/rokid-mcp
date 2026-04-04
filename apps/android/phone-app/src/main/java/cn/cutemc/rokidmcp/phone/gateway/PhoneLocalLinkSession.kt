package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.DecodedFrame
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.LinkRole
import cn.cutemc.rokidmcp.share.protocol.LocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import cn.cutemc.rokidmcp.share.protocol.PongPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class PhoneHelloConfig(
    val deviceId: String,
    val appVersion: String,
    val supportedActions: List<cn.cutemc.rokidmcp.share.protocol.LocalAction>,
)

sealed interface PhoneLocalSessionEvent {
    data object SessionReady : PhoneLocalSessionEvent

    data class HelloRejected(
        val code: String,
        val message: String,
    ) : PhoneLocalSessionEvent

    data class PongReceived(
        val seq: Long,
        val receivedAt: Long,
    ) : PhoneLocalSessionEvent

    data class SessionFailed(
        val code: String,
        val message: String,
    ) : PhoneLocalSessionEvent
}

open class PhoneLocalLinkSession(
    private val transport: RfcommClientTransport,
    private val helloConfig: PhoneHelloConfig,
    private val codec: LocalFrameCodec,
    private val clock: Clock,
    private val sessionScope: CoroutineScope,
) {
    private data class PendingPong(
        val seq: Long,
        val nonce: String,
    )

    private val internalEvents = MutableSharedFlow<PhoneLocalSessionEvent>(extraBufferCapacity = 32)

    val events: Flow<PhoneLocalSessionEvent> = internalEvents

    private var eventJob: Job? = null
    private var helloTimeoutJob: Job? = null
    private var keepaliveJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var nextPingSeq: Long = 1L
    private var waitingForPong: PendingPong? = null
    private var sessionReady = false

    open suspend fun start(targetDeviceAddress: String) {
        if (eventJob?.isActive == true) {
            return
        }

        eventJob = sessionScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is PhoneTransportEvent.StateChanged -> handleStateChanged(event.state)
                    is PhoneTransportEvent.BytesReceived -> handleReceivedBytes(event.bytes)
                    is PhoneTransportEvent.Failure,
                    is PhoneTransportEvent.ConnectionClosed,
                    -> clearSessionState()
                }
            }
        }

        transport.start(targetDeviceAddress)
    }

    open suspend fun stop(reason: String) {
        try {
            transport.stop(reason)
        } finally {
            terminate(reason)
        }
    }

    open suspend fun terminate(reason: String) {
        clearSessionState()
        eventJob?.cancelAndJoin()
        eventJob = null
    }

    private suspend fun handleStateChanged(state: PhoneTransportState) {
        if (state != PhoneTransportState.CONNECTED) {
            return
        }

        sessionReady = false
        waitingForPong = null
        sendHello()
        scheduleHelloTimeout()
    }

    private suspend fun handleReceivedBytes(bytes: ByteArray) {
        val frame = try {
            codec.decode(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emitFailure(
                code = "BLUETOOTH_PROTOCOL_ERROR",
                message = "failed to decode local frame: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }

        handleFrame(frame)
    }

    private suspend fun handleFrame(frame: DecodedFrame) {
        when (frame.header.type) {
            LocalMessageType.HELLO_ACK -> handleHelloAck(frame.header.payload as? HelloAckPayload ?: return)
            LocalMessageType.PONG -> handlePong(frame.header.payload as? PongPayload ?: return)
            else -> Unit
        }
    }

    private suspend fun sendHello() {
        transport.send(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO,
                    timestamp = clock.nowMs(),
                    payload = HelloPayload(
                        role = LinkRole.PHONE,
                        deviceId = helloConfig.deviceId,
                        appVersion = helloConfig.appVersion,
                        supportedActions = helloConfig.supportedActions,
                    ),
                ),
            ),
        )
    }

    private fun scheduleHelloTimeout() {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = sessionScope.launch {
            delay(LocalProtocolConstants.HELLO_ACK_TIMEOUT_MS)
            if (!sessionReady) {
                emitFailure(
                    code = "BLUETOOTH_HELLO_TIMEOUT",
                    message = "hello ack not received in time",
                )
            }
        }
    }

    private suspend fun handleHelloAck(ack: HelloAckPayload) {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null

        if (!ack.accepted) {
            internalEvents.emit(
                PhoneLocalSessionEvent.HelloRejected(
                    code = ack.error?.code ?: "BLUETOOTH_HELLO_REJECTED",
                    message = ack.error?.message ?: "hello rejected",
                ),
            )
            return
        }

        sessionReady = true
        internalEvents.emit(PhoneLocalSessionEvent.SessionReady)
        startKeepaliveLoop()
    }

    private suspend fun handlePong(pong: PongPayload) {
        val pendingPong = waitingForPong
        if (!sessionReady || pendingPong?.seq != pong.seq || pendingPong.nonce != pong.nonce) {
            return
        }

        waitingForPong = null
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null
        internalEvents.emit(
            PhoneLocalSessionEvent.PongReceived(
            seq = pong.seq,
                receivedAt = clock.nowMs(),
            ),
        )
    }

    private fun startKeepaliveLoop() {
        keepaliveJob?.cancel()
        keepaliveJob = sessionScope.launch {
            while (true) {
                delay(LocalProtocolConstants.PING_INTERVAL_MS)
                if (!sessionReady || waitingForPong != null) {
                    continue
                }

                sendPing()
            }
        }
    }

    private suspend fun sendPing() {
        val seq = nextPingSeq++
        val pendingPong = PendingPong(
            seq = seq,
            nonce = "ping-$seq",
        )
        waitingForPong = pendingPong
        transport.send(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.PING,
                    timestamp = clock.nowMs(),
                    payload = PingPayload(
                        seq = pendingPong.seq,
                        nonce = pendingPong.nonce,
                    ),
                ),
            ),
        )
        armPongTimeout(pendingPong)
    }

    private fun armPongTimeout(expectedPong: PendingPong) {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = sessionScope.launch {
            delay(LocalProtocolConstants.PONG_TIMEOUT_MS)
            if (waitingForPong != expectedPong) {
                return@launch
            }

            emitFailure(
                code = "BLUETOOTH_PONG_TIMEOUT",
                message = "pong not received in time",
            )
        }
    }

    private suspend fun emitFailure(code: String, message: String) {
        clearSessionState()
        internalEvents.emit(
            PhoneLocalSessionEvent.SessionFailed(
                code = code,
                message = message,
            ),
        )
    }

    private fun clearSessionState() {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null
        sessionReady = false
        waitingForPong = null
    }
}
