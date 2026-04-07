package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutionException
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.CommandFailure
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextResult
import cn.cutemc.rokidmcp.share.protocol.local.DisplayingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.ExecutingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CommandDispatcher(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val frameSender: GlassesFrameSender,
    private val exclusiveGuard: ExclusiveExecutionGuard,
    private val displayTextExecutor: DisplayTextExecutor,
    private val capturePhotoExecutor: CapturePhotoExecutor,
) {
    val supportedActions: List<CommandAction> = listOf(
        CommandAction.DISPLAY_TEXT,
        CommandAction.CAPTURE_PHOTO,
    )

    private var activeCommandJob: Job? = null

    suspend fun handleCommand(header: LocalFrameHeader<*>) {
        require(header.type == LocalMessageType.COMMAND) {
            "command dispatcher only accepts COMMAND frames"
        }

        val requestId = header.requestId ?: return
        when (val payload = header.payload) {
            is DisplayTextCommand -> dispatchDisplayText(requestId, payload)
            is CapturePhotoCommand -> dispatchCapturePhoto(requestId, payload)
            else -> sendCommandError(
                requestId = requestId,
                action = CommandAction.DISPLAY_TEXT,
                code = LocalProtocolErrorCodes.UNSUPPORTED_PROTOCOL,
                message = "unsupported command payload ${payload.javaClass.simpleName}",
                retryable = false,
            )
        }
    }

    suspend fun stop() {
        activeCommandJob?.cancel()
        activeCommandJob = null
        exclusiveGuard.currentRequestId()?.let(exclusiveGuard::release)
    }

    private suspend fun dispatchDisplayText(requestId: String, command: DisplayTextCommand) {
        if (!exclusiveGuard.tryAcquire(requestId)) {
            sendCommandBusy(requestId, command.action)
            return
        }

        activeCommandJob = scope.launch {
            try {
                sendCommandAck(requestId, command.action)
                sendExecutingStatus(requestId, command.action)
                sendDisplayingStatus(requestId)
                val result = displayTextExecutor.execute(command)
                sendDisplayTextResult(requestId, result)
            } catch (error: DisplayTextExecutionException) {
                sendCommandError(
                    requestId = requestId,
                    action = command.action,
                    code = error.code,
                    message = error.message,
                    retryable = false,
                )
            } finally {
                exclusiveGuard.release(requestId)
                activeCommandJob = null
            }
        }
    }

    private suspend fun dispatchCapturePhoto(requestId: String, command: CapturePhotoCommand) {
        if (!exclusiveGuard.tryAcquire(requestId)) {
            sendCommandBusy(requestId, command.action)
            return
        }

        activeCommandJob = scope.launch {
            try {
                capturePhotoExecutor.execute(requestId = requestId, command = command)
            } finally {
                exclusiveGuard.release(requestId)
                activeCommandJob = null
            }
        }
    }

    private suspend fun sendCommandAck(requestId: String, action: CommandAction) {
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ACK,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = CommandAck(
                    action = action,
                    acceptedAt = clock.nowMs(),
                    runtimeState = LocalRuntimeState.BUSY,
                ),
            ),
            null,
        )
    }

    private suspend fun sendExecutingStatus(requestId: String, action: CommandAction) {
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = ExecutingCommandStatus(
                    action = action,
                    statusAt = clock.nowMs(),
                ),
            ),
            null,
        )
    }

    private suspend fun sendDisplayingStatus(requestId: String) {
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = DisplayingCommandStatus(statusAt = clock.nowMs()),
            ),
            null,
        )
    }

    private suspend fun sendDisplayTextResult(requestId: String, result: DisplayTextResult) {
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_RESULT,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = result,
            ),
            null,
        )
    }

    private suspend fun sendCommandBusy(requestId: String, action: CommandAction) {
        sendCommandError(
            requestId = requestId,
            action = action,
            code = LocalProtocolErrorCodes.COMMAND_BUSY,
            message = "another glasses command is already running",
            retryable = true,
        )
    }

    private suspend fun sendCommandError(
        requestId: String,
        action: CommandAction,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ERROR,
                requestId = requestId,
                timestamp = clock.nowMs(),
                payload = CommandError(
                    action = action,
                    failedAt = clock.nowMs(),
                    error = CommandFailure(
                        code = code,
                        message = message,
                        retryable = retryable,
                    ),
                ),
            ),
            null,
        )
    }
}
