package cn.cutemc.rokidmcp.glasses.executor

import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.camera.CameraCaptureException
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.gateway.Clock
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSenderException
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoOutcome
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.CapturingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.CommandFailure
import cn.cutemc.rokidmcp.share.protocol.local.ExecutingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import kotlinx.coroutines.CancellationException
import timber.log.Timber

private const val CAPTURE_PHOTO_TAG = "capture-photo"

class CapturePhotoExecutor(
    private val cameraAdapter: CameraAdapter,
    private val checksumCalculator: ChecksumCalculator,
    private val imageChunkSender: ImageChunkSender,
    private val clock: Clock,
    private val frameSender: GlassesFrameSender,
) {
    suspend fun execute(requestId: String, command: CapturePhotoCommand) {
        Timber.tag(CAPTURE_PHOTO_TAG).i(
            "capture_photo execution start requestId=$requestId transferId=${command.transfer.transferId}",
        )
        sendAck(requestId)
        sendExecutingStatus(requestId)
        sendCapturingStatus(requestId)

        Timber.tag(CAPTURE_PHOTO_TAG).i(
            "camera capture start requestId=$requestId quality=${command.params.quality}",
        )
        val capture = try {
            cameraAdapter.capture(command.params.quality)
        } catch (error: CameraCaptureException) {
            Timber.tag(CAPTURE_PHOTO_TAG).e(error, "camera capture failed requestId=$requestId")
            sendFailure(requestId, error.code, error.message, retryable = error.code == LocalProtocolErrorCodes.CAMERA_UNAVAILABLE)
            return
        } catch (error: IllegalArgumentException) {
            Timber.tag(CAPTURE_PHOTO_TAG).e(error, "camera returned invalid jpeg metadata requestId=$requestId")
            sendFailure(
                requestId = requestId,
                code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                message = error.message ?: "camera returned an invalid jpeg payload",
                retryable = false,
            )
            return
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            Timber.tag(CAPTURE_PHOTO_TAG).e(error, "unexpected camera capture failure requestId=$requestId")
            sendFailure(
                requestId = requestId,
                code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                message = error.message ?: "camera capture failed",
                retryable = false,
            )
            return
        }

        Timber.tag(CAPTURE_PHOTO_TAG).i(
            "camera capture success requestId=$requestId transferId=${command.transfer.transferId} bytes=${capture.bytes.size} width=${capture.width} height=${capture.height}",
        )

        val sha256 = checksumCalculator.sha256(capture.bytes)

        try {
            validateTransfer(command, capture)
            imageChunkSender.send(
                requestId = requestId,
                transferId = command.transfer.transferId,
                imageData = capture.bytes,
                width = capture.width,
                height = capture.height,
                sha256 = sha256,
            )
        } catch (error: CapturePhotoExecutionException) {
            Timber.tag(CAPTURE_PHOTO_TAG).w(
                "capture_photo validation failed requestId=$requestId transferId=${command.transfer.transferId} code=${error.code}",
            )
            sendFailure(requestId, error.code, error.message, retryable = error.retryable)
            return
        } catch (error: ImageChunkSenderException) {
            Timber.tag(CAPTURE_PHOTO_TAG).w(
                "capture_photo image transfer failed requestId=$requestId transferId=${command.transfer.transferId} code=${error.code}",
            )
            sendFailure(requestId, error.code, error.message, retryable = error.code == LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED)
            return
        }

        val completedAt = clock.nowMs()
        Timber.tag(CAPTURE_PHOTO_TAG).i(
            "sending capture_photo result requestId=$requestId transferId=${command.transfer.transferId}",
        )
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_RESULT,
                requestId = requestId,
                timestamp = completedAt,
                payload = CapturePhotoResult(
                    action = CommandAction.CAPTURE_PHOTO,
                    completedAt = completedAt,
                    result = CapturePhotoOutcome(
                        mediaType = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
                        size = capture.bytes.size.toLong(),
                        width = capture.width,
                        height = capture.height,
                        sha256 = sha256,
                    ),
                ),
            ),
            null,
        )
        Timber.tag(CAPTURE_PHOTO_TAG).i(
            "capture_photo execution complete requestId=$requestId transferId=${command.transfer.transferId}",
        )
    }

    private suspend fun sendAck(requestId: String) {
        val acceptedAt = clock.nowMs()
        Timber.tag(CAPTURE_PHOTO_TAG).i("sending capture_photo ack requestId=$requestId")
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ACK,
                requestId = requestId,
                timestamp = acceptedAt,
                payload = CommandAck(
                    action = CommandAction.CAPTURE_PHOTO,
                    acceptedAt = acceptedAt,
                    runtimeState = LocalRuntimeState.BUSY,
                ),
            ),
            null,
        )
    }

    private suspend fun sendExecutingStatus(requestId: String) {
        val statusAt = clock.nowMs()
        Timber.tag(CAPTURE_PHOTO_TAG).i("sending capture_photo status=executing requestId=$requestId")
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                requestId = requestId,
                timestamp = statusAt,
                payload = ExecutingCommandStatus(
                    action = CommandAction.CAPTURE_PHOTO,
                    statusAt = statusAt,
                ),
            ),
            null,
        )
    }

    private suspend fun sendCapturingStatus(requestId: String) {
        val statusAt = clock.nowMs()
        Timber.tag(CAPTURE_PHOTO_TAG).i("sending capture_photo status=capturing requestId=$requestId")
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_STATUS,
                requestId = requestId,
                timestamp = statusAt,
                payload = CapturingCommandStatus(statusAt = statusAt),
            ),
            null,
        )
    }

    private suspend fun sendFailure(
        requestId: String,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        val failedAt = clock.nowMs()
        Timber.tag(CAPTURE_PHOTO_TAG).w(
            "sending capture_photo error requestId=$requestId code=$code retryable=$retryable",
        )
        frameSender.send(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND_ERROR,
                requestId = requestId,
                timestamp = failedAt,
                payload = CommandError(
                    action = CommandAction.CAPTURE_PHOTO,
                    failedAt = failedAt,
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

    private fun validateTransfer(command: CapturePhotoCommand, capture: CameraCapture) {
        if (command.transfer.mediaType != LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG) {
            throw CapturePhotoExecutionException(
                code = LocalProtocolErrorCodes.UNSUPPORTED_PROTOCOL,
                message = "capture_photo only supports image/jpeg transfers",
                retryable = false,
            )
        }

        if (capture.bytes.size.toLong() > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
            throw CapturePhotoExecutionException(
                code = LocalProtocolErrorCodes.IMAGE_TOO_LARGE,
                message = "captured image exceeds protocol maximum size",
                retryable = false,
            )
        }

        if (capture.bytes.size.toLong() > command.transfer.maxBytes) {
            throw CapturePhotoExecutionException(
                code = LocalProtocolErrorCodes.IMAGE_TOO_LARGE,
                message = "captured image exceeds transfer maxBytes",
                retryable = false,
            )
        }
    }
}

class CapturePhotoExecutionException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : IllegalStateException(message)
