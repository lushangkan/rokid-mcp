package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import java.io.ByteArrayOutputStream
import timber.log.Timber

private const val IMAGE_ASSEMBLER_TAG = "image-assembler"

data class AssembledImage(
    val requestId: String,
    val transferId: String,
    val mediaType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val sha256: String,
    val bytes: ByteArray,
)

class IncomingImageAssembler {
    private var state: AssemblyState? = null

    fun start(requestId: String, transferId: String, payload: ChunkStart) {
        if (state != null) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "image assembly already in progress",
            )
        }
        if (payload.totalSize > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.IMAGE_TOO_LARGE,
                message = "image transfer exceeds local protocol limits",
            )
        }

        Timber.tag(IMAGE_ASSEMBLER_TAG).i(
            "starting image assembly requestId=$requestId transferId=$transferId totalSize=${payload.totalSize} width=${payload.width ?: 0} height=${payload.height ?: 0}",
        )
        state = AssemblyState(
            requestId = requestId,
            transferId = transferId,
            start = payload,
            buffer = ByteArrayOutputStream(payload.totalSize.toInt()),
        )
    }

    fun append(requestId: String, transferId: String, payload: ChunkData, body: ByteArray) {
        val current = requireState(requestId, transferId)
        if (payload.index != current.nextIndex) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "expected chunk index ${current.nextIndex} but received ${payload.index}",
            )
        }
        if (payload.offset != current.writtenBytes) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "expected chunk offset ${current.writtenBytes} but received ${payload.offset}",
            )
        }
        if (payload.size != body.size) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.PROTOCOL_INVALID_PAYLOAD,
                message = "chunk body size ${body.size} does not match declared size ${payload.size}",
            )
        }
        if (!payload.chunkChecksum.equals(LocalProtocolChecksums.crc32(body), ignoreCase = true)) {
            throw checksumMismatch(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH,
                message = "chunk body checksum does not match transfer metadata",
                detail = " chunkIndex=${payload.index}",
            )
        }

        current.buffer.write(body)
        current.nextIndex += 1
        current.writtenBytes += body.size.toLong()

        Timber.tag(IMAGE_ASSEMBLER_TAG).v(
            "received image chunk requestId=$requestId transferId=$transferId chunkIndex=${payload.index} writtenBytes=${current.writtenBytes} totalSize=${current.start.totalSize}",
        )

        if (current.writtenBytes > current.start.totalSize) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.IMAGE_TRANSFER_INCOMPLETE,
                message = "image transfer exceeded declared total size",
            )
        }
    }

    fun finish(requestId: String, transferId: String, payload: ChunkEnd): AssembledImage {
        val current = requireState(requestId, transferId)
        val bytes = current.buffer.toByteArray()
        if (payload.totalChunks != current.nextIndex) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.IMAGE_TRANSFER_INCOMPLETE,
                message = "received ${current.nextIndex} chunks but expected ${payload.totalChunks}",
            )
        }
        if (payload.totalSize != current.writtenBytes || current.start.totalSize != current.writtenBytes) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.IMAGE_TRANSFER_INCOMPLETE,
                message = "image transfer completed at ${current.writtenBytes} bytes but expected ${payload.totalSize}",
            )
        }

        val computedSha256 = LocalProtocolChecksums.sha256(bytes)
        val expectedHashes = listOfNotNull(current.start.sha256, payload.sha256).distinct()
        if (expectedHashes.any { !it.equals(computedSha256, ignoreCase = true) }) {
            throw checksumMismatch(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH,
                message = "assembled image checksum does not match transfer metadata",
            )
        }

        state = null
        Timber.tag(IMAGE_ASSEMBLER_TAG).i(
            "assembled image successfully requestId=$requestId transferId=$transferId totalChunks=${payload.totalChunks} totalSize=${current.writtenBytes}",
        )
        return AssembledImage(
            requestId = requestId,
            transferId = transferId,
            mediaType = current.start.mediaType,
            width = current.start.width ?: 0,
            height = current.start.height ?: 0,
            size = current.writtenBytes,
            sha256 = computedSha256,
            bytes = bytes,
        )
    }

    fun reset() {
        state = null
    }

    private fun requireState(requestId: String, transferId: String): AssemblyState {
        val current = state ?: throw fail(
            requestId = requestId,
            transferId = transferId,
            code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
            message = "no image assembly is currently active",
        )
        if (current.requestId != requestId || current.transferId != transferId) {
            throw fail(
                requestId = requestId,
                transferId = transferId,
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "image transfer $transferId does not match the active assembly",
            )
        }
        return current
    }

    private data class AssemblyState(
        val requestId: String,
        val transferId: String,
        val start: ChunkStart,
        val buffer: ByteArrayOutputStream,
        var nextIndex: Int = 0,
        var writtenBytes: Long = 0L,
    )
}

private fun fail(requestId: String, transferId: String, code: String, message: String): ImageAssemblyException {
    Timber.tag(IMAGE_ASSEMBLER_TAG).e(
        "image assembly failed requestId=$requestId transferId=$transferId code=$code",
    )
    return ImageAssemblyException(code = code, message = message)
}

private fun checksumMismatch(
    requestId: String,
    transferId: String,
    code: String,
    message: String,
    detail: String = "",
): ImageAssemblyException {
    Timber.tag(IMAGE_ASSEMBLER_TAG).w(
        "image checksum mismatch requestId=$requestId transferId=$transferId code=$code$detail",
    )
    return ImageAssemblyException(code = code, message = message)
}

class ImageAssemblyException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)
