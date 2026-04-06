package cn.cutemc.rokidmcp.glasses.gateway

import android.os.Build
import cn.cutemc.rokidmcp.glasses.BuildConfig
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.CommandFailure
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.GlassesInfo
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
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
    private val capturePhotoExecutor: CapturePhotoExecutor? = null,
    private val commandDispatcher: CommandDispatcher? = null,
) {
    private var eventJob: Job? = null
    private var activeCommandJob: Job? = null
    private val glassesCapabilities = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO)

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
            commandDispatcher?.stop()
            activeCommandJob?.cancelAndJoin()
            activeCommandJob = null
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

    private suspend fun handleCommand(header: LocalFrameHeader<*>) {
        commandDispatcher?.let {
            it.handleCommand(header)
            return
        }

        val requestId = header.requestId ?: return
        when (val payload = header.payload) {
            is CapturePhotoCommand -> dispatchCapturePhotoCommand(requestId, payload)
            is DisplayTextCommand -> sendUnsupportedCommandError(
                requestId = requestId,
                action = CommandAction.DISPLAY_TEXT,
                message = "display_text execution is not available in glasses-app yet",
            )
            else -> Unit
        }
    }

    private suspend fun dispatchCapturePhotoCommand(
        requestId: String,
        payload: CapturePhotoCommand,
    ) {
        if (activeCommandJob?.isActive == true) {
            sendBusyCommandError(requestId, CommandAction.CAPTURE_PHOTO)
            return
        }

        val executor = capturePhotoExecutor ?: run {
            sendUnsupportedCommandError(
                requestId = requestId,
                action = CommandAction.CAPTURE_PHOTO,
                message = "capture_photo execution is not configured",
            )
            return
        }

        activeCommandJob = sessionScope.launch {
            try {
                executor.execute(requestId = requestId, command = payload)
            } catch (error: Exception) {
                controller.markFailure(error.message ?: "capture photo execution failed")
            } finally {
                activeCommandJob = null
            }
        }
    }

    private suspend fun sendBusyCommandError(requestId: String, action: CommandAction) {
        transport.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ERROR,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = CommandError(
                    action = action,
                    failedAt = clock.nowMs(),
                    error = CommandFailure(
                        code = LocalProtocolErrorCodes.COMMAND_BUSY,
                        message = "another glasses command is already running",
                        retryable = true,
                    ),
                ),
            ),
        )
    }

    private suspend fun sendUnsupportedCommandError(
        requestId: String,
        action: CommandAction,
        message: String,
    ) {
        transport.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ERROR,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = CommandError(
                    action = action,
                    failedAt = clock.nowMs(),
                    error = CommandFailure(
                        code = LocalProtocolErrorCodes.UNSUPPORTED_PROTOCOL,
                        message = message,
                        retryable = false,
                    ),
                ),
            ),
        )
    }
}
