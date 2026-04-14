package cn.cutemc.rokidmcp.glasses.sender

import android.util.Log
import cn.cutemc.rokidmcp.glasses.gateway.FakeClock
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageChunkSenderTest {
    @Test
    fun `sender emits chunk frames with crc checksums and terminal metadata`() = runTest {
        val frames = mutableListOf<Pair<cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader<*>, ByteArray?>>()
        val sender = ImageChunkSender(
            clock = FakeClock(1_717_180_000L),
            frameSender = GlassesFrameSender { header, body -> frames += header to body },
            chunkSizeBytes = 4,
        )
        val imageBytes = "abcdefghij".encodeToByteArray()

        val logs = captureTimberLogs {
            runBlocking {
                sender.send(
                    requestId = "req_capture_1",
                    transferId = "trf_capture_1",
                    imageData = imageBytes,
                    width = 800,
                    height = 600,
                    sha256 = "sha256-test",
                )
            }
        }

        assertEquals(
            listOf(
                LocalMessageType.CHUNK_START,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_END,
            ),
            frames.map { it.first.type },
        )

        val start = frames[0].first.payload as ChunkStart
        assertEquals(imageBytes.size.toLong(), start.totalSize)
        assertEquals(800, start.width)
        assertEquals(600, start.height)
        assertEquals("sha256-test", start.sha256)

        val firstChunk = frames[1]
        val firstChunkPayload = firstChunk.first.payload as ChunkData
        assertEquals(0, firstChunkPayload.index)
        assertEquals(0L, firstChunkPayload.offset)
        assertEquals(4, firstChunkPayload.size)
        assertEquals(LocalProtocolChecksums.crc32(firstChunk.second!!), firstChunkPayload.chunkChecksum)
        assertArrayEquals("abcd".encodeToByteArray(), firstChunk.second)

        val lastChunk = frames[3]
        val lastChunkPayload = lastChunk.first.payload as ChunkData
        assertEquals(2, lastChunkPayload.index)
        assertEquals(8L, lastChunkPayload.offset)
        assertArrayEquals("ij".encodeToByteArray(), lastChunk.second)

        val end = frames[4].first.payload as ChunkEnd
        assertEquals(3, end.totalChunks)
        assertEquals(imageBytes.size.toLong(), end.totalSize)
        assertEquals("sha256-test", end.sha256)
        logs.assertLog(Log.INFO, "image-chunk", "image transfer start requestId=req_capture_1 transferId=trf_capture_1 totalBytes=10")
        logs.assertLog(Log.VERBOSE, "image-chunk", "image chunk progress requestId=req_capture_1 transferId=trf_capture_1 index=0 offset=0 size=4 sentBytes=4 totalBytes=10")
        logs.assertLog(Log.VERBOSE, "image-chunk", "image chunk progress requestId=req_capture_1 transferId=trf_capture_1 index=2 offset=8 size=2 sentBytes=10 totalBytes=10")
        logs.assertLog(Log.INFO, "image-chunk", "image transfer complete requestId=req_capture_1 transferId=trf_capture_1 totalChunks=3 totalBytes=10")
        logs.assertNoSensitiveData()
        assertTrue(logs.none { entry -> entry.message.contains("abcdefghij") })
    }

    @Test
    fun `sender logs transfer failure with image chunk ownership`() = runTest {
        val sender = ImageChunkSender(
            clock = FakeClock(1_717_180_050L),
            frameSender = GlassesFrameSender { _, _ -> throw IllegalStateException("link write failed") },
            chunkSizeBytes = 4,
        )

        val logs = captureTimberLogs {
            runBlocking {
                try {
                    sender.send(
                        requestId = "req_capture_2",
                        transferId = "trf_capture_2",
                        imageData = "abcdefghij".encodeToByteArray(),
                        width = 800,
                        height = 600,
                        sha256 = "sha256-test",
                    )
                } catch (_: ImageChunkSenderException) {
                    Unit
                }
            }
        }

        logs.assertLog(Log.INFO, "image-chunk", "image transfer start requestId=req_capture_2 transferId=trf_capture_2 totalBytes=10")
        logs.assertLog(Log.ERROR, "image-chunk", "failed to send image transfer frame type=CHUNK_START requestId=req_capture_2 transferId=trf_capture_2")
        logs.assertNoSensitiveData()
        assertTrue(logs.none { entry -> entry.message.contains("abcdefghij") })
    }

    @Test
    fun `sender logs avoid raw image byte markers`() = runTest {
        val sender = ImageChunkSender(
            clock = FakeClock(1_717_180_075L),
            frameSender = GlassesFrameSender { _, _ -> Unit },
            chunkSizeBytes = 8,
        )

        val logs = captureTimberLogs {
            runBlocking {
                sender.send(
                    requestId = "req_capture_redaction",
                    transferId = "trf_capture_redaction",
                    imageData = "imageBytes chunkBytes authToken".encodeToByteArray(),
                    width = 320,
                    height = 240,
                    sha256 = "sha256-redaction",
                )
            }
        }

        logs.assertLog(Log.INFO, "image-chunk", "image transfer start requestId=req_capture_redaction transferId=trf_capture_redaction totalBytes=31")
        logs.assertLog(Log.INFO, "image-chunk", "image transfer complete requestId=req_capture_redaction transferId=trf_capture_redaction")
        logs.assertNoSensitiveData()
        assertTrue(logs.none { entry -> entry.message.contains("imageBytes") || entry.message.contains("chunkBytes") })
    }
}
