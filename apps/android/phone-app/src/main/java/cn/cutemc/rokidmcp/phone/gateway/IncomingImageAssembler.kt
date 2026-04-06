package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

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
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "image assembly already in progress",
            )
        }
        if (payload.totalSize > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.IMAGE_TOO_LARGE,
                message = "image transfer exceeds local protocol limits",
            )
        }

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
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "expected chunk index ${current.nextIndex} but received ${payload.index}",
            )
        }
        if (payload.offset != current.writtenBytes) {
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
                message = "expected chunk offset ${current.writtenBytes} but received ${payload.offset}",
            )
        }

        current.buffer.write(body)
        current.nextIndex += 1
        current.writtenBytes += body.size.toLong()

        if (current.writtenBytes > current.start.totalSize) {
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.IMAGE_TRANSFER_INCOMPLETE,
                message = "image transfer exceeded declared total size",
            )
        }
    }

    fun finish(requestId: String, transferId: String, payload: ChunkEnd): AssembledImage {
        val current = requireState(requestId, transferId)
        val bytes = current.buffer.toByteArray()
        if (payload.totalChunks != current.nextIndex) {
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.IMAGE_TRANSFER_INCOMPLETE,
                message = "received ${current.nextIndex} chunks but expected ${payload.totalChunks}",
            )
        }
        if (payload.totalSize != current.writtenBytes || current.start.totalSize != current.writtenBytes) {
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.IMAGE_TRANSFER_INCOMPLETE,
                message = "image transfer completed at ${current.writtenBytes} bytes but expected ${payload.totalSize}",
            )
        }

        val computedSha256 = bytes.sha256Hex()
        val expectedHashes = listOfNotNull(current.start.sha256, payload.sha256).distinct()
        if (expectedHashes.any { !it.equals(computedSha256, ignoreCase = true) }) {
            throw ImageAssemblyException(
                code = LocalProtocolErrorCodes.IMAGE_CHECKSUM_MISMATCH,
                message = "assembled image checksum does not match transfer metadata",
            )
        }

        state = null
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
        val current = state ?: throw ImageAssemblyException(
            code = LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID,
            message = "no image assembly is currently active",
        )
        if (current.requestId != requestId || current.transferId != transferId) {
            throw ImageAssemblyException(
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

class ImageAssemblyException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance(LocalProtocolConstants.FILE_CHECKSUM_ALGO)
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
