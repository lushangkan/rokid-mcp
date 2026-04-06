package cn.cutemc.rokidmcp.glasses.sender

import cn.cutemc.rokidmcp.glasses.gateway.FakeClock
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageChunkSenderTest {
    @Test
    fun `sender emits chunk frames with crc checksums and terminal metadata`() = runTest {
        val codec = DefaultLocalFrameCodec()
        val decodedFrames = mutableListOf<Pair<cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader<*>, ByteArray?>>()
        val sender = ImageChunkSender(
            codec = codec,
            clock = FakeClock(1_717_180_000L),
            frameSender = EncodedLocalFrameSender { frameBytes ->
                val decoded = codec.decode(frameBytes)
                decodedFrames += decoded.header to decoded.body
            },
            chunkSizeBytes = 4,
        )
        val imageBytes = "abcdefghij".encodeToByteArray()

        sender.send(
            requestId = "req_capture_1",
            transferId = "trf_capture_1",
            imageBytes = imageBytes,
            width = 800,
            height = 600,
            sha256 = "sha256-test",
        )

        assertEquals(
            listOf(
                LocalMessageType.CHUNK_START,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_END,
            ),
            decodedFrames.map { it.first.type },
        )

        val start = decodedFrames[0].first.payload as ChunkStart
        assertEquals(imageBytes.size.toLong(), start.totalSize)
        assertEquals(800, start.width)
        assertEquals(600, start.height)
        assertEquals("sha256-test", start.sha256)

        val firstChunk = decodedFrames[1]
        val firstChunkPayload = firstChunk.first.payload as ChunkData
        assertEquals(0, firstChunkPayload.index)
        assertEquals(0L, firstChunkPayload.offset)
        assertEquals(4, firstChunkPayload.size)
        assertEquals(LocalProtocolChecksums.crc32(firstChunk.second!!), firstChunkPayload.chunkChecksum)
        assertArrayEquals("abcd".encodeToByteArray(), firstChunk.second)

        val lastChunk = decodedFrames[3]
        val lastChunkPayload = lastChunk.first.payload as ChunkData
        assertEquals(2, lastChunkPayload.index)
        assertEquals(8L, lastChunkPayload.offset)
        assertArrayEquals("ij".encodeToByteArray(), lastChunk.second)

        val end = decodedFrames[4].first.payload as ChunkEnd
        assertEquals(3, end.totalChunks)
        assertEquals(imageBytes.size.toLong(), end.totalSize)
        assertEquals("sha256-test", end.sha256)
    }
}
