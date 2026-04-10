package cn.cutemc.rokidmcp.glasses.sender

import cn.cutemc.rokidmcp.glasses.gateway.Clock
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import cn.cutemc.rokidmcp.share.protocol.local.ProtocolCodecException
import kotlinx.coroutines.CancellationException
import kotlin.math.min
import timber.log.Timber

fun interface GlassesFrameSender {
    suspend fun send(header: LocalFrameHeader<*>, body: ByteArray?)
}

class ImageChunkSenderException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ImageChunkSender(
    private val clock: Clock,
    private val frameSender: GlassesFrameSender,
    private val chunkSizeBytes: Int = LocalProtocolConstants.CHUNK_SIZE_BYTES,
) {
    init {
        require(chunkSizeBytes > 0) { "chunk size must be positive" }
    }

    suspend fun send(
        requestId: String,
        transferId: String,
        imageBytes: ByteArray,
        width: Int,
        height: Int,
        sha256: String,
    ) {
        requireRequestMetadata(requestId, transferId)
        requireImageMetadata(imageBytes, width, height)

        sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_START,
                requestId = requestId,
                transferId = transferId,
                timestamp = clock.nowMs(),
                payload = ChunkStart(
                    action = CommandAction.CAPTURE_PHOTO,
                    mediaType = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
                    totalSize = imageBytes.size.toLong(),
                    width = width,
                    height = height,
                    sha256 = sha256,
                ),
            ),
        )

        var index = 0
        var offset = 0
        while (offset < imageBytes.size) {
            val nextOffset = min(offset + chunkSizeBytes, imageBytes.size)
            val chunkBytes = imageBytes.copyOfRange(offset, nextOffset)
            sendFrame(
                LocalFrameHeader(
                    type = LocalMessageType.CHUNK_DATA,
                    requestId = requestId,
                    transferId = transferId,
                    timestamp = clock.nowMs(),
                    payload = ChunkData(
                        action = CommandAction.CAPTURE_PHOTO,
                        index = index,
                        offset = offset.toLong(),
                        size = chunkBytes.size,
                        chunkChecksum = LocalProtocolChecksums.crc32(chunkBytes),
                    ),
                ),
                chunkBytes,
            )
            offset = nextOffset
            index += 1
        }

        sendFrame(
            LocalFrameHeader(
                type = LocalMessageType.CHUNK_END,
                requestId = requestId,
                transferId = transferId,
                timestamp = clock.nowMs(),
                payload = ChunkEnd(
                    action = CommandAction.CAPTURE_PHOTO,
                    totalChunks = index,
                    totalSize = imageBytes.size.toLong(),
                    sha256 = sha256,
                ),
            ),
        )
    }

    private fun requireRequestMetadata(requestId: String, transferId: String) {
        if (requestId.isBlank()) {
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.PROTOCOL_REQUEST_ID_REQUIRED,
                message = "capture_photo image transfer requires requestId",
            )
        }
        if (transferId.isBlank()) {
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.PROTOCOL_TRANSFER_ID_REQUIRED,
                message = "capture_photo image transfer requires transferId",
            )
        }
    }

    private fun requireImageMetadata(imageBytes: ByteArray, width: Int, height: Int) {
        if (imageBytes.isEmpty()) {
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.PROTOCOL_INVALID_PAYLOAD,
                message = "captured image bytes must not be empty",
            )
        }
        if (width <= 0 || height <= 0) {
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.PROTOCOL_INVALID_PAYLOAD,
                message = "captured image dimensions must be positive",
            )
        }
        if (imageBytes.size.toLong() > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.IMAGE_TOO_LARGE,
                message = "captured image exceeds protocol maximum size",
            )
        }
    }

    private suspend fun sendFrame(header: LocalFrameHeader<*>, body: ByteArray? = null) {
        try {
            frameSender.send(header, body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ImageChunkSenderException) {
            throw error
        } catch (error: ProtocolCodecException) {
            Timber.tag("image-chunk").e(
                error,
                "failed to encode image transfer frame type=${header.type} requestId=${header.requestId} transferId=${header.transferId}",
            )
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.PROTOCOL_INVALID_PAYLOAD,
                message = error.message ?: "failed to encode image transfer frame",
                cause = error,
            )
        } catch (error: Exception) {
            Timber.tag("image-chunk").e(
                error,
                "failed to send image transfer frame type=${header.type} requestId=${header.requestId} transferId=${header.transferId}",
            )
            throw ImageChunkSenderException(
                code = LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED,
                message = error.message ?: "failed to send image transfer frame",
                cause = error,
            )
        }
    }
}
