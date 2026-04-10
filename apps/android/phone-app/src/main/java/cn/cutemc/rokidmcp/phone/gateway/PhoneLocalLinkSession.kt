package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.DecodedFrame
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

data class PhoneHelloConfig(
    val deviceId: String,
    val appVersion: String,
    val supportedActions: List<CommandAction>,
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

    data class FrameReceived(
        val header: LocalFrameHeader<*>,
        val body: ByteArray?,
    ) : PhoneLocalSessionEvent
}

open class PhoneLocalLinkSession(
    private val transport: RfcommClientTransport,
    private val helloConfig: PhoneHelloConfig,
    private val codec: LocalFrameCodec,
    private val clock: Clock,
    private val sessionScope: CoroutineScope,
) {
    private companion object {
        const val LOG_TAG = "local-session"
    }

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

        Timber.tag(LOG_TAG).i("starting phone local link session")

        eventJob = sessionScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is PhoneTransportEvent.StateChanged -> handleStateChanged(event.state)
                    is PhoneTransportEvent.BytesReceived -> handleReceivedBytes(event.bytes)
                    is PhoneTransportEvent.Failure,
                    is PhoneTransportEvent.ConnectionClosed,
                    -> clearSessionState("transport closed or failed")
                }
            }
        }

        transport.start(targetDeviceAddress)
    }

    open suspend fun stop(reason: String) {
        Timber.tag(LOG_TAG).i("stopping phone local link session: %s", reason)
        try {
            transport.stop(reason)
        } finally {
            terminate(reason)
        }
    }

    open suspend fun terminate(reason: String) {
        Timber.tag(LOG_TAG).i("terminating phone local link session: %s", reason)
        clearSessionState("terminate: $reason")
        eventJob?.cancelAndJoin()
        eventJob = null
    }

    open suspend fun sendFrame(header: LocalFrameHeader<*>, body: ByteArray? = null) {
        check(sessionReady) { "local session is not ready" }
        transport.send(codec.encode(header, body))
    }

    private suspend fun handleStateChanged(state: PhoneTransportState) {
        if (state != PhoneTransportState.CONNECTED) {
            return
        }

        sessionReady = false
        waitingForPong = null
        Timber.tag(LOG_TAG).i("transport connected; sending HELLO")
        sendHello()
        scheduleHelloTimeout()
    }

    private suspend fun handleReceivedBytes(bytes: ByteArray) {
        val frame = try {
            codec.decode(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(LOG_TAG).e(error, "failed to decode local frame from glasses")
            emitFailure(
                code = LocalProtocolErrorCodes.BLUETOOTH_PROTOCOL_ERROR,
                message = "failed to decode local frame: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }

        handleFrame(frame)
    }

    private suspend fun handleFrame(frame: DecodedFrame) {
        when (frame.header.type) {
            LocalMessageType.HELLO_ACK -> {
                Timber.tag(LOG_TAG).v("dispatching HELLO_ACK frame to handshake handler")
                handleHelloAck(frame.header.payload as? HelloAckPayload ?: return)
            }
            LocalMessageType.PONG -> {
                Timber.tag(LOG_TAG).v("dispatching PONG frame to keepalive handler")
                handlePong(frame.header.payload as? PongPayload ?: return)
            }
            LocalMessageType.COMMAND_ACK,
            LocalMessageType.COMMAND_STATUS,
            LocalMessageType.COMMAND_RESULT,
            LocalMessageType.COMMAND_ERROR,
            LocalMessageType.CHUNK_START,
            LocalMessageType.CHUNK_DATA,
            LocalMessageType.CHUNK_END,
            -> {
                Timber.tag(LOG_TAG).v("dispatching ${frame.header.type} frame to session events")
                internalEvents.emit(PhoneLocalSessionEvent.FrameReceived(frame.header, frame.body))
            }
            else -> Timber.tag(LOG_TAG).v("ignoring unsupported frame type=${frame.header.type}")
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
        Timber.tag(LOG_TAG).i(
            "sent HELLO with deviceId=%s appVersion=%s actions=%d",
            helloConfig.deviceId,
            helloConfig.appVersion,
            helloConfig.supportedActions.size,
        )
    }

    private fun scheduleHelloTimeout() {
        helloTimeoutJob?.cancel()
        Timber.tag(LOG_TAG).i(
            "armed HELLO_ACK timeout for %d ms",
            LocalProtocolConstants.HELLO_ACK_TIMEOUT_MS,
        )
        helloTimeoutJob = sessionScope.launch {
            delay(LocalProtocolConstants.HELLO_ACK_TIMEOUT_MS)
            if (!sessionReady) {
                Timber.tag(LOG_TAG).w("timed out waiting for HELLO_ACK")
                emitFailure(
                    code = LocalProtocolErrorCodes.BLUETOOTH_HELLO_TIMEOUT,
                    message = "hello ack not received in time",
                )
            }
        }
    }

    private suspend fun handleHelloAck(ack: HelloAckPayload) {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null

        if (!ack.accepted) {
            Timber.tag(LOG_TAG).w(
                "HELLO_ACK rejected code=%s message=%s",
                ack.error?.code ?: LocalProtocolErrorCodes.BLUETOOTH_HELLO_REJECTED,
                ack.error?.message ?: "hello rejected",
            )
            internalEvents.emit(
                PhoneLocalSessionEvent.HelloRejected(
                    code = ack.error?.code ?: LocalProtocolErrorCodes.BLUETOOTH_HELLO_REJECTED,
                    message = ack.error?.message ?: "hello rejected",
                ),
            )
            return
        }

        Timber.tag(LOG_TAG).i("HELLO_ACK accepted for role=%s", ack.role)
        sessionReady = true
        Timber.tag(LOG_TAG).i("local session ready")
        internalEvents.emit(PhoneLocalSessionEvent.SessionReady)
        startKeepaliveLoop()
    }

    private suspend fun handlePong(pong: PongPayload) {
        val pendingPong = waitingForPong
        if (!sessionReady || pendingPong?.seq != pong.seq || pendingPong.nonce != pong.nonce) {
            Timber.tag(LOG_TAG).w(
                "received unmatched PONG seq=%d nonce=%s while waiting=%s",
                pong.seq,
                pong.nonce,
                pendingPong,
            )
            return
        }

        Timber.tag(LOG_TAG).v("received PONG seq=%d", pong.seq)
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
        Timber.tag(LOG_TAG).i("starting keepalive loop")
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
        Timber.tag(LOG_TAG).v("sending PING seq=%d", pendingPong.seq)
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
        Timber.tag(LOG_TAG).v("armed PONG timeout for seq=%d", expectedPong.seq)
        pongTimeoutJob = sessionScope.launch {
            delay(LocalProtocolConstants.PONG_TIMEOUT_MS)
            if (waitingForPong != expectedPong) {
                return@launch
            }

            Timber.tag(LOG_TAG).w("timed out waiting for PONG seq=%d", expectedPong.seq)
            emitFailure(
                code = LocalProtocolErrorCodes.BLUETOOTH_PONG_TIMEOUT,
                message = "pong not received in time",
            )
        }
    }

    private suspend fun emitFailure(code: String, message: String) {
        Timber.tag(LOG_TAG).e("local session failed code=%s message=%s", code, message)
        clearSessionState("failure: $code")
        internalEvents.emit(
            PhoneLocalSessionEvent.SessionFailed(
                code = code,
                message = message,
            ),
        )
    }

    private fun clearSessionState(reason: String) {
        Timber.tag(LOG_TAG).i("clearing session state: %s", reason)
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
