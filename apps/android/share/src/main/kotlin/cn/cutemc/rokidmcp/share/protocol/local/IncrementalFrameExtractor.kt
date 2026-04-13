package cn.cutemc.rokidmcp.share.protocol.local

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants

/**
 * Buffers arbitrary byte slices and drains complete local-link frames in arrival order.
 *
 * The extractor only reads the fixed 16-byte prefix so fragmented trailing bytes remain buffered
 * until the full frame has arrived. Full semantic validation still belongs to
 * [DefaultLocalFrameCodec.decode].
 */
class IncrementalFrameExtractor {
    private companion object {
        private const val FIXED_HEADER_LENGTH_BYTES = 16
        private const val DEFAULT_INITIAL_CAPACITY_BYTES = 4 * 1024
        private const val MAGIC_OFFSET = 0
        private const val HEADER_LENGTH_OFFSET = 4
        private const val BODY_LENGTH_OFFSET = 8
        private val MAX_FRAME_LENGTH_BYTES =
            FIXED_HEADER_LENGTH_BYTES.toLong() +
                LocalProtocolConstants.FRAME_HEADER_MAX_BYTES.toLong() +
                LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES
    }

    private var buffer = ByteArray(DEFAULT_INITIAL_CAPACITY_BYTES)
    private var bufferedByteCount = 0

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<ByteArray> {
        require(offset in 0..bytes.size) { "offset out of bounds: $offset" }
        require(length >= 0 && offset + length <= bytes.size) {
            "length out of bounds: offset=$offset length=$length size=${bytes.size}"
        }

        if (length == 0) {
            return emptyList()
        }

        ensureCapacity(bufferedByteCount + length)
        bytes.copyInto(
            destination = buffer,
            destinationOffset = bufferedByteCount,
            startIndex = offset,
            endIndex = offset + length,
        )
        bufferedByteCount += length

        return extractFrames()
    }

    fun reset() {
        bufferedByteCount = 0
    }

    private fun extractFrames(): List<ByteArray> {
        if (bufferedByteCount < FIXED_HEADER_LENGTH_BYTES) {
            return emptyList()
        }

        val frames = mutableListOf<ByteArray>()
        var readOffset = 0
        while (bufferedByteCount - readOffset >= FIXED_HEADER_LENGTH_BYTES) {
            val magic = readInt(readOffset + MAGIC_OFFSET)
            val headerLength = readInt(readOffset + HEADER_LENGTH_OFFSET)
            val bodyLength = readInt(readOffset + BODY_LENGTH_OFFSET)

            validateFixedHeader(magic = magic, headerLength = headerLength, bodyLength = bodyLength)

            val frameLength = FIXED_HEADER_LENGTH_BYTES.toLong() + headerLength.toLong() + bodyLength.toLong()
            if (frameLength > MAX_FRAME_LENGTH_BYTES) {
                throw ProtocolCodecException("Frame exceeds $MAX_FRAME_LENGTH_BYTES bytes")
            }

            if (bufferedByteCount - readOffset < frameLength) {
                break
            }

            val frameEnd = readOffset + frameLength.toInt()
            frames += buffer.copyOfRange(readOffset, frameEnd)
            readOffset = frameEnd
        }

        compactBufferedBytes(readOffset)
        return frames
    }

    private fun validateFixedHeader(magic: Int, headerLength: Int, bodyLength: Int) {
        if (magic != LocalProtocolConstants.FRAME_MAGIC) {
            throw ProtocolCodecException("Unexpected frame magic: 0x${magic.toUInt().toString(16)}")
        }
        if (headerLength !in 1..LocalProtocolConstants.FRAME_HEADER_MAX_BYTES) {
            throw ProtocolCodecException("Invalid header length: $headerLength")
        }
        if (bodyLength < 0 || bodyLength.toLong() > LocalProtocolConstants.MAX_IMAGE_SIZE_BYTES) {
            throw ProtocolCodecException("Invalid body length: $bodyLength")
        }
    }

    private fun compactBufferedBytes(consumedByteCount: Int) {
        if (consumedByteCount == 0) {
            return
        }

        val remainingByteCount = bufferedByteCount - consumedByteCount
        if (remainingByteCount > 0) {
            buffer.copyInto(
                destination = buffer,
                destinationOffset = 0,
                startIndex = consumedByteCount,
                endIndex = bufferedByteCount,
            )
        }
        bufferedByteCount = remainingByteCount
    }

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= buffer.size) {
            return
        }

        var nextCapacity = buffer.size
        while (nextCapacity < requiredCapacity) {
            nextCapacity = (nextCapacity * 2).coerceAtLeast(requiredCapacity)
        }
        buffer = buffer.copyOf(nextCapacity)
    }

    private fun readInt(offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }
}
