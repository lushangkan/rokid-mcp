package cn.cutemc.rokidmcp.glasses.gateway

import android.os.Build
import cn.cutemc.rokidmcp.glasses.BuildConfig
import cn.cutemc.rokidmcp.share.protocol.local.GlassesInfo
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

class GlassesLocalLinkSession(
    private val transport: RfcommServerTransport,
    private val controller: GlassesAppController,
    private val clock: Clock,
    private val sessionScope: CoroutineScope,
    private val commandDispatcher: CommandDispatcher,
) {
    private companion object {
        const val LOG_TAG = "glasses-session"
    }

    private var eventJob: Job? = null
    private val glassesCapabilities = commandDispatcher.supportedActions

    suspend fun start() {
        if (eventJob?.isActive == true) {
            Timber.tag(LOG_TAG).w("ignoring start because glasses local link session is already active")
            return
        }

        Timber.tag(LOG_TAG).i("starting glasses local link session")
        eventJob = sessionScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is GlassesTransportEvent.StateChanged -> {
                        when (event.state) {
                            GlassesTransportState.ERROR -> Timber.tag(LOG_TAG).w("observed transport state=%s", event.state)
                            else -> Timber.tag(LOG_TAG).i("observed transport state=%s", event.state)
                        }
                        controller.applyTransportState(event.state)
                    }
                    is GlassesTransportEvent.FrameReceived -> {
                        try {
                            handleFrame(event)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Timber.tag(LOG_TAG).e(error, "failed to handle incoming ${event.header.type} frame")
                            controller.markFailure(error.message ?: "frame handling failed")
                        }
                    }
                    is GlassesTransportEvent.Failure -> {
                        Timber.tag(LOG_TAG).e(event.cause, "glasses transport failure")
                        controller.markFailure(event.cause.message ?: "transport failure")
                    }
                    is GlassesTransportEvent.ConnectionClosed -> {
                        Timber.tag(LOG_TAG).i("glasses transport disconnected reason=%s", event.reason ?: "unknown")
                        controller.markDisconnected()
                    }
                }
            }
        }
        try {
            transport.start()
        } catch (error: CancellationException) {
            eventJob?.cancelAndJoin()
            eventJob = null
            throw error
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).e(error, "failed to start glasses local link session")
            controller.markFailure(error.message ?: "failed to start glasses link session")
            eventJob?.cancelAndJoin()
            eventJob = null
            throw error
        }
    }

    suspend fun stop(reason: String) {
        Timber.tag(LOG_TAG).i("stopping glasses local link session reason=%s", reason)
        try {
            transport.stop(reason)
        } finally {
            commandDispatcher.stop()
            controller.markDisconnected()
            eventJob?.cancelAndJoin()
            eventJob = null
        }
    }

    private suspend fun handleFrame(event: GlassesTransportEvent.FrameReceived) {
        when (event.header.type) {
            LocalMessageType.HELLO -> handleHello(event.header)
            LocalMessageType.PING -> handlePing(event.header)
            LocalMessageType.COMMAND -> handleCommand(event.header)
            else -> Unit
        }
    }

    private suspend fun handleHello(header: LocalFrameHeader<*>) {
        val hello = header.payload as? HelloPayload ?: run {
            Timber.tag(LOG_TAG).w("ignored HELLO frame with unexpected payload=%s", header.payload::class.simpleName ?: "null")
            return
        }
        Timber.tag(LOG_TAG).i(
            "received HELLO role=%s deviceId=%s appVersion=%s actions=%d",
            hello.role,
            hello.deviceId,
            hello.appVersion,
            hello.supportedActions.size,
        )
        val ack = LocalFrameHeader(
            type = LocalMessageType.HELLO_ACK,
            timestamp = clock.nowMs(),
            payload = HelloAckPayload(
                accepted = true,
                role = LinkRole.GLASSES,
                glassesInfo = GlassesInfo(
                    model = Build.MODEL,
                    appVersion = BuildConfig.VERSION_NAME,
                ),
                capabilities = glassesCapabilities,
                runtimeState = LocalRuntimeState.READY,
            ),
        )
        if (!sendSessionFrame(ack, "hello_ack")) {
            return
        }
        val helloAck = ack.payload
        Timber.tag(LOG_TAG).i(
            "sent HELLO_ACK accepted=%s role=%s capabilities=%d runtimeState=%s",
            helloAck.accepted,
            helloAck.role,
            helloAck.capabilities?.size ?: 0,
            helloAck.runtimeState,
        )
        controller.markHelloAccepted()
    }

    private suspend fun handlePing(header: LocalFrameHeader<*>) {
        val ping = header.payload as? PingPayload ?: run {
            Timber.tag(LOG_TAG).w("ignored PING frame with unexpected payload=%s", header.payload::class.simpleName ?: "null")
            return
        }
        Timber.tag(LOG_TAG).v("received PING seq=%d nonce=%s", ping.seq, ping.nonce)
        val pong = LocalFrameHeader(
            type = LocalMessageType.PONG,
            timestamp = clock.nowMs(),
            payload = PongPayload(seq = ping.seq, nonce = ping.nonce),
        )
        if (!sendSessionFrame(pong, "pong")) {
            return
        }
        Timber.tag(LOG_TAG).v("sent PONG seq=%d nonce=%s", ping.seq, ping.nonce)
    }

    private suspend fun handleCommand(header: LocalFrameHeader<*>) {
        Timber.tag(LOG_TAG).i(
            "handing off COMMAND frame requestId=%s payload=%s",
            header.requestId ?: "none",
            header.payload::class.simpleName ?: "null",
        )
        commandDispatcher.handleCommand(header)
    }

    private suspend fun sendSessionFrame(header: LocalFrameHeader<*>, frameName: String): Boolean {
        return try {
            transport.send(header)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).w(error, "failed to send %s frame", frameName)
            controller.markFailure(error.message ?: "failed to send $frameName frame")
            false
        }
    }
}
