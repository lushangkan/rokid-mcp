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
    private var eventJob: Job? = null
    private val glassesCapabilities = commandDispatcher.supportedActions

    suspend fun start() {
        if (eventJob?.isActive == true) {
            return
        }

        eventJob = sessionScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is GlassesTransportEvent.StateChanged -> controller.applyTransportState(event.state)
                    is GlassesTransportEvent.FrameReceived -> {
                        try {
                            handleFrame(event)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Timber.tag("glasses-session").e(error, "failed to handle incoming ${event.header.type} frame")
                            controller.markFailure(error.message ?: "frame handling failed")
                        }
                    }
                    is GlassesTransportEvent.Failure -> {
                        Timber.tag("glasses-session").e(event.cause, "glasses transport failure")
                        controller.markFailure(event.cause.message ?: "transport failure")
                    }
                    is GlassesTransportEvent.ConnectionClosed -> controller.markDisconnected()
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
            Timber.tag("glasses-session").e(error, "failed to start glasses local link session")
            controller.markFailure(error.message ?: "failed to start glasses link session")
            eventJob?.cancelAndJoin()
            eventJob = null
            throw error
        }
    }

    suspend fun stop(reason: String) {
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
        val hello = header.payload as? HelloPayload ?: return
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
        controller.markHelloAccepted()
    }

    private suspend fun handlePing(header: LocalFrameHeader<*>) {
        val ping = header.payload as? PingPayload ?: return
        sendSessionFrame(
            LocalFrameHeader(
                type = LocalMessageType.PONG,
                timestamp = clock.nowMs(),
                payload = PongPayload(seq = ping.seq, nonce = ping.nonce),
            ),
            "pong",
        )
    }

    private suspend fun handleCommand(header: LocalFrameHeader<*>) {
        commandDispatcher.handleCommand(header)
    }

    private suspend fun sendSessionFrame(header: LocalFrameHeader<*>, frameName: String): Boolean {
        return try {
            transport.send(header)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Timber.tag("glasses-session").e(error, "failed to send $frameName frame")
            controller.markFailure(error.message ?: "failed to send $frameName frame")
            false
        }
    }
}
