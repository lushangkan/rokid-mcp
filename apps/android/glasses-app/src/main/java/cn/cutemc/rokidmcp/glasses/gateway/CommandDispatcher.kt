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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COMMAND_DISPATCH_TAG = "command-dispatch"

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
        Timber.tag(COMMAND_DISPATCH_TAG).i(
            "dispatcher entry requestId=$requestId payload=${header.payload.javaClass.simpleName}",
        )

        when (val payload = header.payload) {
            is DisplayTextCommand -> {
                Timber.tag(COMMAND_DISPATCH_TAG).i("selected display_text branch requestId=$requestId")
                dispatchDisplayText(requestId, payload)
            }

            is CapturePhotoCommand -> {
                Timber.tag(COMMAND_DISPATCH_TAG).i("selected capture_photo branch requestId=$requestId")
                dispatchCapturePhoto(requestId, payload)
            }

            else -> {
                Timber.tag(COMMAND_DISPATCH_TAG).w(
                    "rejecting unsupported command payload requestId=$requestId payload=${payload.javaClass.simpleName}",
                )
                try {
                    sendCommandError(
                        requestId = requestId,
                        action = CommandAction.DISPLAY_TEXT,
                        code = LocalProtocolErrorCodes.UNSUPPORTED_PROTOCOL,
                        message = "unsupported command payload ${payload.javaClass.simpleName}",
                        retryable = false,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Timber.tag(COMMAND_DISPATCH_TAG).e(
                        error,
                        "failed to reject unsupported command payload for requestId=$requestId",
                    )
                }
            }
        }
    }

    suspend fun stop() {
        val activeJob = activeCommandJob
        activeCommandJob = null
        activeJob?.cancelAndJoin()
        exclusiveGuard.currentRequestId()?.let(exclusiveGuard::release)
    }

    private suspend fun dispatchDisplayText(requestId: String, command: DisplayTextCommand) {
        if (!exclusiveGuard.tryAcquire(requestId)) {
            Timber.tag(COMMAND_DISPATCH_TAG).w(
                "busy rejection requestId=$requestId action=${command.action}",
            )
            sendCommandBusy(requestId, command.action)
            return
        }

        activeCommandJob = scope.launch {
            try {
                sendCommandAck(requestId, command.action)
                sendExecutingStatus(requestId, command.action)
                sendDisplayingStatus(requestId)
                val result = displayTextExecutor.execute(requestId = requestId, command = command)
                sendDisplayTextResult(requestId, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: DisplayTextExecutionException) {
                Timber.tag(COMMAND_DISPATCH_TAG).w(
                    "display_text command failed requestId=$requestId code=${error.code}",
                )
                sendCommandError(
                    requestId = requestId,
                    action = command.action,
                    code = error.code,
                    message = error.message,
                    retryable = false,
                )
            } catch (error: Throwable) {
                reportUnexpectedDispatchFailure(
                    requestId = requestId,
                    action = command.action,
                    code = LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
                    message = error.message ?: "display_text command dispatch failed",
                    failureContext = "display_text command failed for requestId=$requestId",
                    sendErrorContext = "failed to emit display_text command error for requestId=$requestId",
                    error = error,
                )
            } finally {
                exclusiveGuard.release(requestId)
                activeCommandJob = null
            }
        }
    }

    private suspend fun dispatchCapturePhoto(requestId: String, command: CapturePhotoCommand) {
        if (!exclusiveGuard.tryAcquire(requestId)) {
            Timber.tag(COMMAND_DISPATCH_TAG).w(
                "busy rejection requestId=$requestId action=${command.action}",
            )
            sendCommandBusy(requestId, command.action)
            return
        }

        activeCommandJob = scope.launch {
            try {
                capturePhotoExecutor.execute(requestId = requestId, command = command)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportUnexpectedDispatchFailure(
                    requestId = requestId,
                    action = command.action,
                    code = LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
                    message = error.message ?: "capture_photo command dispatch failed",
                    failureContext = "capture_photo command failed for requestId=$requestId",
                    sendErrorContext = "failed to emit capture_photo command error for requestId=$requestId",
                    error = error,
                )
            } finally {
                exclusiveGuard.release(requestId)
                activeCommandJob = null
            }
        }
    }

    private suspend fun sendCommandAck(requestId: String, action: CommandAction) {
        Timber.tag(COMMAND_DISPATCH_TAG).i("sending command ack requestId=$requestId action=$action")
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
        Timber.tag(COMMAND_DISPATCH_TAG).i("sending executing status requestId=$requestId action=$action")
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
        Timber.tag(COMMAND_DISPATCH_TAG).i("sending displaying status requestId=$requestId")
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
        Timber.tag(COMMAND_DISPATCH_TAG).i(
            "sending display_text result requestId=$requestId displayed=${result.result.displayed}",
        )
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
        Timber.tag(COMMAND_DISPATCH_TAG).w(
            "sending command error requestId=$requestId action=$action code=$code retryable=$retryable",
        )
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

    private suspend fun reportUnexpectedDispatchFailure(
        requestId: String,
        action: CommandAction,
        code: String,
        message: String,
        failureContext: String,
        sendErrorContext: String,
        error: Throwable,
    ) {
        Timber.tag(COMMAND_DISPATCH_TAG).e(error, failureContext)
        runCatching {
            sendCommandError(
                requestId = requestId,
                action = action,
                code = code,
                message = message,
                retryable = true,
            )
        }.onFailure { sendError ->
            Timber.tag(COMMAND_DISPATCH_TAG).e(sendError, sendErrorContext)
        }
    }
}
