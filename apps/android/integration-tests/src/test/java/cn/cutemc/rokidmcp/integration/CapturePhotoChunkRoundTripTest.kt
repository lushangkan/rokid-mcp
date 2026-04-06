package cn.cutemc.rokidmcp.integration

import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.sender.EncodedLocalFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.phone.gateway.IncomingImageAssembler
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
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
        val codec = DefaultLocalFrameCodec()
        val assembler = IncomingImageAssembler()
        val imageBytes = "jpeg-loopback-payload".encodeToByteArray()
        var assembled = false
        val sender = ImageChunkSender(
            codec = codec,
            clock = IntegrationClock(1_717_181_000L),
            frameSender = EncodedLocalFrameSender { frameBytes ->
                val decoded = codec.decode(frameBytes)
                when (decoded.header.type) {
                    LocalMessageType.CHUNK_START -> assembler.start(
                        requestId = decoded.header.requestId!!,
                        transferId = decoded.header.transferId!!,
                        payload = decoded.header.payload as ChunkStart,
                    )

                    LocalMessageType.CHUNK_DATA -> assembler.append(
                        requestId = decoded.header.requestId!!,
                        transferId = decoded.header.transferId!!,
                        payload = decoded.header.payload as ChunkData,
                        body = decoded.body!!,
                    )

                    LocalMessageType.CHUNK_END -> {
                        val image = assembler.finish(
                            requestId = decoded.header.requestId!!,
                            transferId = decoded.header.transferId!!,
                            payload = decoded.header.payload as ChunkEnd,
                        )
                        assertEquals("req_capture_round_trip", image.requestId)
                        assertEquals("trf_capture_round_trip", image.transferId)
                        assertEquals(1024, image.width)
                        assertEquals(768, image.height)
                        assertEquals(imageBytes.size.toLong(), image.size)
                        assertArrayEquals(imageBytes, image.bytes)
                        assembled = true
                    }

                    else -> error("Unexpected frame type ${decoded.header.type}")
                }
            },
            chunkSizeBytes = 5,
        )

        sender.send(
            requestId = "req_capture_round_trip",
            transferId = "trf_capture_round_trip",
            imageBytes = imageBytes,
            width = 1024,
            height = 768,
            sha256 = ChecksumCalculator().sha256(imageBytes),
        )

        assertEquals(true, assembled)
    }
}

private class IntegrationClock(private val nowMs: Long) : cn.cutemc.rokidmcp.glasses.gateway.Clock {
    override fun nowMs(): Long = nowMs
}
