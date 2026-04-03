package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.GlassesInfo
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.LinkRole
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import cn.cutemc.rokidmcp.share.protocol.PongPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class GlassesLocalLinkSession(
    private val transport: RfcommServerTransport,
    private val controller: GlassesAppController,
    private val clock: Clock,
    private val sessionScope: CoroutineScope,
) {
    private var eventJob: Job? = null
    private val glassesCapabilities = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO)

    suspend fun start() {
        if (eventJob?.isActive == true) {
            return
        }

        eventJob = sessionScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is GlassesTransportEvent.StateChanged -> controller.applyTransportState(event.state)
                    is GlassesTransportEvent.FrameReceived -> handleFrame(event)
                    is GlassesTransportEvent.Failure -> controller.markFailure(event.cause.message ?: "transport failure")
                    is GlassesTransportEvent.ConnectionClosed -> controller.markDisconnected()
                }
            }
        }
        transport.start()
    }

    suspend fun stop(reason: String) {
        try {
            transport.stop(reason)
        } finally {
            controller.markDisconnected()
            eventJob?.cancelAndJoin()
            eventJob = null
        }
    }

    private suspend fun handleFrame(event: GlassesTransportEvent.FrameReceived) {
        when (event.header.type) {
            LocalMessageType.HELLO -> handleHello(event.header)
            LocalMessageType.PING -> handlePing(event.header)
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
                    model = "Rokid",
                    appVersion = "1.0.0",
                ),
                capabilities = glassesCapabilities,
                runtimeState = LocalRuntimeState.READY,
            ),
        )
        transport.send(ack)
        controller.markHelloAccepted()
    }

    private suspend fun handlePing(header: LocalFrameHeader<*>) {
        val ping = header.payload as? PingPayload ?: return
        transport.send(
            LocalFrameHeader(
                type = LocalMessageType.PONG,
                timestamp = clock.nowMs(),
                payload = PongPayload(seq = ping.seq, nonce = ping.nonce),
            ),
        )
    }
}
