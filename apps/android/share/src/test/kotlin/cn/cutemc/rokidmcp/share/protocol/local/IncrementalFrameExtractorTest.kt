package cn.cutemc.rokidmcp.share.protocol.local

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IncrementalFrameExtractorTest {
    private val codec = DefaultLocalFrameCodec()

    @Test
    fun `buffers fragmented fixed header until full frame arrives`() {
        val extractor = IncrementalFrameExtractor()
        val frame = pingFrame(seq = 1, nonce = "fixed-header")

        assertTrue(extractor.append(frame, length = 10).isEmpty())

        val extracted = extractor.append(frame, offset = 10, length = frame.size - 10)

        assertEquals(1, extracted.size)
        assertArrayEquals(frame, extracted.single())
    }

    @Test
    fun `buffers fragmented json header until full header arrives`() {
        val extractor = IncrementalFrameExtractor()
        val frame = pingFrame(seq = 2, nonce = "json-header")
        val headerLength = readInt(frame, offset = 4)
        val partialHeaderLength = 16 + headerLength - 1

        assertTrue(extractor.append(frame, length = partialHeaderLength).isEmpty())

        val extracted = extractor.append(frame, offset = partialHeaderLength, length = frame.size - partialHeaderLength)

        assertEquals(1, extracted.size)
        assertArrayEquals(frame, extracted.single())
    }

    @Test
    fun `buffers fragmented body until full body arrives`() {
        val extractor = IncrementalFrameExtractor()
        val frame = chunkDataFrame(body = byteArrayOf(1, 2, 3, 4, 5))
        val headerLength = readInt(frame, offset = 4)
        val bodyLength = readInt(frame, offset = 8)
        val partialFrameLength = 16 + headerLength + bodyLength - 1

        assertTrue(extractor.append(frame, length = partialFrameLength).isEmpty())

        val extracted = extractor.append(frame, offset = partialFrameLength, length = frame.size - partialFrameLength)

        assertEquals(1, extracted.size)
        assertArrayEquals(frame, extracted.single())
    }

    @Test
    fun `extracts two coalesced frames from one buffer`() {
        val extractor = IncrementalFrameExtractor()
        val firstFrame = pingFrame(seq = 3, nonce = "first")
        val secondFrame = pingFrame(seq = 4, nonce = "second")

        val extracted = extractor.append(firstFrame + secondFrame)

        assertEquals(2, extracted.size)
        assertArrayEquals(firstFrame, extracted[0])
        assertArrayEquals(secondFrame, extracted[1])
    }

    @Test
    fun `keeps trailing partial bytes buffered after extracting a full frame`() {
        val extractor = IncrementalFrameExtractor()
        val firstFrame = pingFrame(seq = 5, nonce = "whole-frame")
        val secondFrame = pingFrame(seq = 6, nonce = "partial-tail")
        val trailingLength = 8

        val firstExtracted = extractor.append(firstFrame + secondFrame.copyOfRange(0, trailingLength))

        assertEquals(1, firstExtracted.size)
        assertArrayEquals(firstFrame, firstExtracted.single())

        val secondExtracted = extractor.append(
            secondFrame,
            offset = trailingLength,
            length = secondFrame.size - trailingLength,
        )

        assertEquals(1, secondExtracted.size)
        assertArrayEquals(secondFrame, secondExtracted.single())
    }

    @Test
    fun `reset clears buffered partial bytes`() {
        val extractor = IncrementalFrameExtractor()
        val frame = pingFrame(seq = 7, nonce = "after-reset")

        assertTrue(extractor.append(frame, length = 12).isEmpty())

        extractor.reset()

        val extracted = extractor.append(frame)

        assertEquals(1, extracted.size)
        assertArrayEquals(frame, extracted.single())
    }

    @Test
    fun `rejects malformed complete frame instead of buffering forever`() {
        val extractor = IncrementalFrameExtractor()
        val malformedFrame = ByteBuffer.allocate(16)
            .putInt(LocalProtocolConstants.FRAME_MAGIC)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()

        try {
            extractor.append(malformedFrame)
            fail("Expected malformed frame to throw ProtocolCodecException")
        } catch (exception: ProtocolCodecException) {
            assertTrue(exception.message.orEmpty().contains("Invalid header length"))
        }
    }

    private fun pingFrame(seq: Long, nonce: String): ByteArray = codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.PING,
            timestamp = 1_717_171_800L + seq,
            payload = PingPayload(seq = seq, nonce = nonce),
        ),
    )

    private fun chunkDataFrame(body: ByteArray): ByteArray = codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.CHUNK_DATA,
            requestId = "req-${body.size}",
            transferId = "tx-${body.size}",
            timestamp = 1_717_171_900L,
            payload = ChunkData(
                index = 0,
                offset = 0,
                size = body.size,
                chunkChecksum = LocalProtocolChecksums.crc32(body),
            ),
        ),
        body = body,
    )

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).int
}
