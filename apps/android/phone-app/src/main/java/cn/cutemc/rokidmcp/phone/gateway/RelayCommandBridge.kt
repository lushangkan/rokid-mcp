package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.TerminalErrorCode
import cn.cutemc.rokidmcp.share.protocol.local.CapturingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.CommandFailure
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextResult
import cn.cutemc.rokidmcp.share.protocol.local.DisplayingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.ExecutingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalCommandResult
import cn.cutemc.rokidmcp.share.protocol.local.LocalCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandResult
import cn.cutemc.rokidmcp.share.protocol.relay.CommandAckPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandErrorPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandResultPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandStatusImageProgress
import cn.cutemc.rokidmcp.share.protocol.relay.CommandStatusPayload
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandResult
import cn.cutemc.rokidmcp.share.protocol.relay.ExecutingStatus
import cn.cutemc.rokidmcp.share.protocol.relay.ForwardingToGlassesStatus
import cn.cutemc.rokidmcp.share.protocol.relay.ImageCapturedStatus
import cn.cutemc.rokidmcp.share.protocol.relay.ImageUploadedStatus
import cn.cutemc.rokidmcp.share.protocol.relay.TerminalError
import cn.cutemc.rokidmcp.share.protocol.relay.UploadingImageStatus
import cn.cutemc.rokidmcp.share.protocol.relay.WaitingGlassesAckStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

typealias RelayCommandRuntimeUpdater = suspend (activeCommandRequestId: String?, errorCode: String?, errorMessage: String?) -> Unit

class RelayCommandBridge(
    private val relayBaseUrl: String,
    private val deviceId: String,
    private val clock: Clock,
    private val relaySessionClient: RelaySessionClient,
    private val activeCommandRegistry: ActiveCommandRegistry,
    private val localCommandForwarder: LocalCommandForwarder,
    private val incomingImageAssembler: IncomingImageAssembler,
    private val relayImageUploader: RelayImageUploader,
    private val runtimeUpdater: RelayCommandRuntimeUpdater,
) {
    private val mutex = Mutex()

    private var pendingCaptureResult: cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult? = null
    private var assembledImage: AssembledImage? = null

    suspend fun handleRelaySessionEvent(event: RelaySessionEvent) {
        mutex.withLock {
            when (event) {
                is RelaySessionEvent.CommandDispatched -> handleCommandDispatched(event.message)
                is RelaySessionEvent.CommandCancelled -> handleCommandCancelled(event.message.requestId)
                else -> Unit
            }
        }
    }

    suspend fun handleLocalSessionEvent(event: PhoneLocalSessionEvent) {
        if (event !is PhoneLocalSessionEvent.FrameReceived) {
            return
        }

        mutex.withLock {
            handleLocalFrame(event.header, event.body)
        }
    }

    suspend fun failActiveCommand(code: String, message: String) {
        mutex.withLock {
            val active = activeCommandRegistry.activeOrNull() ?: return
            sendTerminalError(active, code, message, retryable = true)
            clearActiveCommand(code = code, message = message)
        }
    }

    private suspend fun handleCommandDispatched(message: CommandDispatchMessage) {
        val active = try {
            activeCommandRegistry.begin(message)
        } catch (error: ActiveCommandRegistryException) {
            relaySessionClient.sendCommandError(
                requestId = message.requestId,
                payload = CommandErrorPayload(
                    action = message.payload.action,
                    failedAt = clock.nowMs(),
                    error = error.toTerminalError(retryable = true),
                ),
            )
            return
        }

        pendingCaptureResult = null
        assembledImage = null
        runtimeUpdater(active.requestId, null, null)

        try {
            relaySessionClient.sendCommandAck(
                requestId = active.requestId,
                payload = CommandAckPayload(
                    action = active.action,
                    acknowledgedAt = clock.nowMs(),
                    runtimeState = RuntimeState.BUSY,
                ),
            )
            relaySessionClient.sendCommandStatus(
                requestId = active.requestId,
                payload = ForwardingToGlassesStatus(
                    action = active.action,
                    statusAt = clock.nowMs(),
                ),
            )
            localCommandForwarder.forward(active)
            relaySessionClient.sendCommandStatus(
                requestId = active.requestId,
                payload = WaitingGlassesAckStatus(
                    action = active.action,
                    statusAt = clock.nowMs(),
                ),
            )
        } catch (error: Exception) {
            sendTerminalError(
                active = active,
                code = LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
                message = error.message ?: "failed to forward the relay command to glasses",
                retryable = true,
            )
            clearActiveCommand(
                code = LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
                message = error.message ?: "failed to forward the relay command to glasses",
            )
        }
    }

    private suspend fun handleCommandCancelled(requestId: String) {
        if (activeCommandRegistry.activeOrNull()?.requestId != requestId) {
            return
        }

        clearActiveCommand()
    }

    private suspend fun handleLocalFrame(header: LocalFrameHeader<*>, body: ByteArray?) {
        val requestId = header.requestId ?: return

        try {
            when (header.type) {
                LocalMessageType.COMMAND_ACK -> handleLocalCommandAck(requestId, header.payload as? CommandAck ?: return)
                LocalMessageType.COMMAND_STATUS -> handleLocalCommandStatus(requestId, header.payload as? LocalCommandStatus ?: return)
                LocalMessageType.COMMAND_RESULT -> handleLocalCommandResult(requestId, header.payload as? LocalCommandResult ?: return)
                LocalMessageType.COMMAND_ERROR -> handleLocalCommandError(requestId, header.payload as? CommandError ?: return)
                LocalMessageType.CHUNK_START -> handleChunkStart(requestId, header.transferId, header.payload as? ChunkStart ?: return)
                LocalMessageType.CHUNK_DATA -> handleChunkData(requestId, header.transferId, header.payload as? ChunkData ?: return, body ?: ByteArray(0))
                LocalMessageType.CHUNK_END -> handleChunkEnd(requestId, header.transferId, header.payload as? ChunkEnd ?: return)
                else -> Unit
            }
        } catch (error: ActiveCommandRegistryException) {
            val active = activeCommandRegistry.activeOrNull() ?: return
            sendTerminalError(active, error.code, error.message, retryable = true)
            clearActiveCommand(code = error.code, message = error.message)
        } catch (error: ImageAssemblyException) {
            val active = activeCommandRegistry.activeOrNull() ?: return
            sendTerminalError(active, error.code, error.message, retryable = false)
            clearActiveCommand(code = error.code, message = error.message)
        } catch (error: RelayImageUploadException) {
            val active = activeCommandRegistry.activeOrNull() ?: return
            sendTerminalError(active, error.code, error.message, retryable = error.retryable)
            clearActiveCommand(code = error.code, message = error.message)
        }
    }

    private suspend fun handleLocalCommandAck(requestId: String, payload: CommandAck) {
        val active = activeCommandRegistry.require(requestId, payload.action)
        relaySessionClient.sendCommandStatus(
            requestId = requestId,
            payload = ExecutingStatus(
                action = active.action,
                statusAt = payload.acceptedAt,
            ),
        )
    }

    private suspend fun handleLocalCommandStatus(requestId: String, payload: LocalCommandStatus) {
        activeCommandRegistry.require(requestId, payload.action)
        relaySessionClient.sendCommandStatus(
            requestId = requestId,
            payload = payload.toRelayStatus(),
        )
    }

    private suspend fun handleLocalCommandResult(requestId: String, payload: LocalCommandResult) {
        when (payload) {
            is DisplayTextResult -> {
                activeCommandRegistry.require(requestId, payload.action)
                relaySessionClient.sendCommandResult(
                    requestId = requestId,
                    payload = CommandResultPayload(
                        completedAt = payload.completedAt,
                        result = DisplayTextCommandResult(
                            durationMs = payload.result.durationMs,
                            displayed = payload.result.displayed,
                        ),
                    ),
                )
                clearActiveCommand()
            }

            is cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult -> {
                activeCommandRegistry.require(requestId, payload.action)
                pendingCaptureResult = payload
                attemptCaptureUploadCompletion(requestId)
            }
        }
    }

    private suspend fun handleLocalCommandError(requestId: String, payload: CommandError) {
        val active = activeCommandRegistry.require(requestId, payload.action)
        relaySessionClient.sendCommandError(
            requestId = requestId,
            payload = CommandErrorPayload(
                action = payload.action,
                failedAt = payload.failedAt,
                error = payload.error.toTerminalError(),
            ),
        )
        clearActiveCommand(code = payload.error.code, message = payload.error.message)
    }

    private fun handleChunkStart(requestId: String, transferId: String?, payload: ChunkStart) {
        val active = activeCommandRegistry.require(requestId, CommandAction.CAPTURE_PHOTO) as ActiveCapturePhotoRelayCommand
        incomingImageAssembler.start(
            requestId = active.requestId,
            transferId = requireTransferId(transferId),
            payload = payload,
        )
    }

    private fun handleChunkData(requestId: String, transferId: String?, payload: ChunkData, body: ByteArray) {
        activeCommandRegistry.require(requestId, CommandAction.CAPTURE_PHOTO)
        incomingImageAssembler.append(
            requestId = requestId,
            transferId = requireTransferId(transferId),
            payload = payload,
            body = body,
        )
    }

    private suspend fun handleChunkEnd(requestId: String, transferId: String?, payload: ChunkEnd) {
        activeCommandRegistry.require(requestId, CommandAction.CAPTURE_PHOTO)
        assembledImage = incomingImageAssembler.finish(
            requestId = requestId,
            transferId = requireTransferId(transferId),
            payload = payload,
        )
        attemptCaptureUploadCompletion(requestId)
    }

    private suspend fun attemptCaptureUploadCompletion(requestId: String) {
        val captureResult = pendingCaptureResult ?: return
        val image = assembledImage ?: return
        val active = activeCommandRegistry.require(requestId, CommandAction.CAPTURE_PHOTO) as ActiveCapturePhotoRelayCommand

        relaySessionClient.sendCommandStatus(
            requestId = requestId,
            payload = ImageCapturedStatus(
                statusAt = captureResult.completedAt,
                image = image.toProgress(active),
            ),
        )

        val uploadStartedAt = clock.nowMs()
        relaySessionClient.sendCommandStatus(
            requestId = requestId,
            payload = UploadingImageStatus(
                statusAt = uploadStartedAt,
                image = image.toProgress(active, uploadStartedAt = uploadStartedAt),
            ),
        )

        val uploadResponse = relayImageUploader.upload(
            RelayImageUploadInput(
                relayBaseUrl = relayBaseUrl,
                deviceId = deviceId,
                requestId = requestId,
                imageId = active.image.imageId,
                transferId = active.image.transferId,
                uploadToken = active.image.uploadToken,
                contentType = image.mediaType,
                bytes = image.bytes,
                sha256 = image.sha256,
            ),
        )

        relaySessionClient.sendCommandStatus(
            requestId = requestId,
            payload = ImageUploadedStatus(
                statusAt = uploadResponse.image.uploadedAt,
                image = image.toProgress(
                    active = active,
                    uploadStartedAt = uploadStartedAt,
                    uploadedAt = uploadResponse.image.uploadedAt,
                    sha256 = uploadResponse.image.sha256 ?: image.sha256,
                ),
            ),
        )
        relaySessionClient.sendCommandResult(
            requestId = requestId,
            payload = CommandResultPayload(
                completedAt = captureResult.completedAt,
                result = CapturePhotoCommandResult(
                    imageId = uploadResponse.image.imageId,
                    transferId = uploadResponse.image.transferId,
                    mimeType = uploadResponse.image.mimeType,
                    size = uploadResponse.image.size,
                    width = captureResult.result.width,
                    height = captureResult.result.height,
                    sha256 = uploadResponse.image.sha256 ?: image.sha256,
                ),
            ),
        )
        clearActiveCommand()
    }

    private suspend fun sendTerminalError(
        active: ActiveRelayCommand,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        relaySessionClient.sendCommandError(
            requestId = active.requestId,
            payload = CommandErrorPayload(
                action = active.action,
                failedAt = clock.nowMs(),
                error = TerminalError(
                    code = code.toTerminalErrorCode(),
                    message = message,
                    retryable = retryable,
                    details = buildJsonObject {
                        put("localCode", JsonPrimitive(code))
                    },
                ),
            ),
        )
    }

    private suspend fun clearActiveCommand(code: String? = null, message: String? = null) {
        activeCommandRegistry.clear()
        pendingCaptureResult = null
        assembledImage = null
        incomingImageAssembler.reset()
        runtimeUpdater(null, code, message)
    }

    private fun requireTransferId(transferId: String?): String {
        return transferId ?: throw ActiveCommandRegistryException(
            code = LocalProtocolErrorCodes.PROTOCOL_TRANSFER_ID_REQUIRED,
            message = "capture photo image transfer is missing transferId",
        )
    }
}

private fun LocalCommandStatus.toRelayStatus(): CommandStatusPayload = when (this) {
    is ExecutingCommandStatus -> ExecutingStatus(
        action = action,
        statusAt = statusAt,
        detailCode = detailCode,
        detailMessage = detailMessage,
    )

    is DisplayingCommandStatus -> cn.cutemc.rokidmcp.share.protocol.relay.DisplayingStatus(
        statusAt = statusAt,
        detailCode = detailCode,
        detailMessage = detailMessage,
    )

    is CapturingCommandStatus -> cn.cutemc.rokidmcp.share.protocol.relay.CapturingStatus(
        statusAt = statusAt,
        detailCode = detailCode,
        detailMessage = detailMessage,
    )
}

private fun CommandFailure.toTerminalError(): TerminalError = TerminalError(
    code = code.toTerminalErrorCode(),
    message = message,
    retryable = retryable,
    details = buildJsonObject {
        put("localCode", JsonPrimitive(code))
    },
)

private fun ActiveCommandRegistryException.toTerminalError(retryable: Boolean): TerminalError = TerminalError(
    code = code.toTerminalErrorCode(),
    message = message,
    retryable = retryable,
    details = buildJsonObject {
        put("localCode", JsonPrimitive(code))
    },
)

private fun String.toTerminalErrorCode(): TerminalErrorCode = when (this) {
    LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH -> TerminalErrorCode.CHECKSUM_MISMATCH
    LocalProtocolErrorCodes.UPLOAD_FAILED -> TerminalErrorCode.UPLOAD_FAILED
    LocalProtocolErrorCodes.BLUETOOTH_CONNECT_FAILED,
    LocalProtocolErrorCodes.BLUETOOTH_DISCONNECTED,
    LocalProtocolErrorCodes.BLUETOOTH_READ_FAILED,
    LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
    LocalProtocolErrorCodes.BLUETOOTH_HELLO_TIMEOUT,
    LocalProtocolErrorCodes.BLUETOOTH_PONG_TIMEOUT,
    LocalProtocolErrorCodes.BLUETOOTH_PROTOCOL_ERROR,
    LocalProtocolErrorCodes.BLUETOOTH_HELLO_REJECTED,
    -> TerminalErrorCode.BLUETOOTH_UNAVAILABLE

    else -> TerminalErrorCode.UNSUPPORTED_OPERATION
}

private fun AssembledImage.toProgress(
    active: ActiveCapturePhotoRelayCommand,
    uploadStartedAt: Long? = null,
    uploadedAt: Long? = null,
    sha256: String? = this.sha256,
): CommandStatusImageProgress = CommandStatusImageProgress(
    imageId = active.image.imageId,
    transferId = active.image.transferId,
    uploadStartedAt = uploadStartedAt,
    uploadedAt = uploadedAt,
    sha256 = sha256,
)
