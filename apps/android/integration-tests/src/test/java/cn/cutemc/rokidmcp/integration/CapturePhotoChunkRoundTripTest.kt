package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.phone.gateway.IncomingImageAssembler
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [32])
class CapturePhotoChunkRoundTripTest {
    @Test
    fun `glasses chunk sender round trips through phone assembler`() = runTest {
        val assembler = IncomingImageAssembler()
        val imageBytes = "jpeg-loopback-payload".encodeToByteArray()
        var assembledCount = 0
        val chunkIndexes = mutableListOf<Int>()
        val sender = ImageChunkSender(
            clock = IntegrationClock(1_717_181_000L),
            frameSender = GlassesFrameSender { header, body ->
                when (header.type) {
                    LocalMessageType.CHUNK_START -> assembler.start(
                        requestId = header.requestId!!,
                        transferId = header.transferId!!,
                        payload = header.payload as ChunkStart,
                    )

                    LocalMessageType.CHUNK_DATA -> assembler.append(
                        requestId = header.requestId!!,
                        transferId = header.transferId!!,
                        payload = header.payload as ChunkData,
                        body = body!!,
                    ).also {
                        chunkIndexes += (header.payload as ChunkData).index
                    }

                    LocalMessageType.CHUNK_END -> {
                        val image = assembler.finish(
                            requestId = header.requestId!!,
                            transferId = header.transferId!!,
                            payload = header.payload as ChunkEnd,
                        )
                        assertEquals("req_capture_round_trip", image.requestId)
                        assertEquals("trf_capture_round_trip", image.transferId)
                        assertEquals(1024, image.width)
                        assertEquals(768, image.height)
                        assertEquals(imageBytes.size.toLong(), image.size)
                        assertArrayEquals(imageBytes, image.bytes)
                        assembledCount += 1
                    }

                    else -> error("Unexpected frame type ${header.type}")
                }
            },
            chunkSizeBytes = 5,
        )

        sender.send(
            requestId = "req_capture_round_trip",
            transferId = "trf_capture_round_trip",
            imageData = imageBytes,
            width = 1024,
            height = 768,
            sha256 = ChecksumCalculator().sha256(imageBytes),
        )

        assertEquals(listOf(0, 1, 2, 3, 4), chunkIndexes)
        assertEquals(1, assembledCount)
    }
}

private class IntegrationClock(private val nowMs: Long) : cn.cutemc.rokidmcp.glasses.gateway.Clock {
    override fun nowMs(): Long = nowMs
}
