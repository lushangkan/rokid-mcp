package cn.cutemc.rokidmcp.glasses.executor

import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.camera.CameraCaptureException
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.gateway.FakeClock
import cn.cutemc.rokidmcp.glasses.sender.EncodedLocalFrameSender
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.CaptureTransfer
import cn.cutemc.rokidmcp.share.protocol.local.CapturingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.ExecutingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePhotoExecutorTest {
    @Test
    fun `executor acknowledges capture streams chunks and emits terminal result`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val chunkFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val clock = FakeClock(1_717_180_100L)
        val codec = DefaultLocalFrameCodec()
        val imageBytes = "jpeg-stream".encodeToByteArray()
        val executor = CapturePhotoExecutor(
            cameraAdapter = FakeCameraAdapter(
                result = CameraCapture(
                    bytes = imageBytes,
                    width = 640,
                    height = 480,
                ),
            ),
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                codec = codec,
                clock = clock,
                frameSender = EncodedLocalFrameSender { frameBytes ->
                    val decoded = codec.decode(frameBytes)
                    chunkFrames += decoded.header to decoded.body
                },
                chunkSizeBytes = 4,
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        executor.execute(requestId = "req_capture_1", command = captureCommand(maxBytes = 4096))

        assertEquals(
            listOf(
                LocalMessageType.COMMAND_ACK,
                LocalMessageType.COMMAND_STATUS,
                LocalMessageType.COMMAND_STATUS,
                LocalMessageType.COMMAND_RESULT,
            ),
            commandFrames.map { it.first.type },
        )
        assertTrue(commandFrames[0].first.payload is CommandAck)
        assertTrue(commandFrames[1].first.payload is ExecutingCommandStatus)
        assertTrue(commandFrames[2].first.payload is CapturingCommandStatus)

        assertEquals(
            listOf(
                LocalMessageType.CHUNK_START,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_DATA,
                LocalMessageType.CHUNK_END,
            ),
            chunkFrames.map { it.first.type },
        )

        val result = commandFrames.last().first.payload as CapturePhotoResult
        assertEquals(640, result.result.width)
        assertEquals(480, result.result.height)
        assertEquals(imageBytes.size.toLong(), result.result.size)
        assertEquals(ChecksumCalculator().sha256(imageBytes), result.result.sha256)
    }

    @Test
    fun `executor emits command error when camera capture fails`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val chunkFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val clock = FakeClock(1_717_180_200L)
        val codec = DefaultLocalFrameCodec()
        val executor = CapturePhotoExecutor(
            cameraAdapter = FakeCameraAdapter(
                failure = CameraCaptureException(
                    code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                    message = "camera offline",
                ),
            ),
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                codec = codec,
                clock = clock,
                frameSender = EncodedLocalFrameSender { frameBytes ->
                    val decoded = codec.decode(frameBytes)
                    chunkFrames += decoded.header to decoded.body
                },
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        executor.execute(requestId = "req_capture_2", command = captureCommand(maxBytes = 4096))

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.CAMERA_UNAVAILABLE, error.error.code)
        assertTrue(error.error.retryable)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
        assertTrue(chunkFrames.isEmpty())
    }

    @Test
    fun `executor rejects non jpeg transfer metadata before sending chunks`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val chunkFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val clock = FakeClock(1_717_180_300L)
        val codec = DefaultLocalFrameCodec()
        val executor = CapturePhotoExecutor(
            cameraAdapter = FakeCameraAdapter(
                result = CameraCapture(
                    bytes = "jpeg-stream".encodeToByteArray(),
                    width = 640,
                    height = 480,
                ),
            ),
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                codec = codec,
                clock = clock,
                frameSender = EncodedLocalFrameSender { frameBytes ->
                    val decoded = codec.decode(frameBytes)
                    chunkFrames += decoded.header to decoded.body
                },
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        executor.execute(
            requestId = "req_capture_3",
            command = captureCommand(maxBytes = 4096, mediaType = "image/png"),
        )

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.UNSUPPORTED_PROTOCOL, error.error.code)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
        assertTrue(chunkFrames.isEmpty())
    }

    @Test
    fun `executor emits command error when chunk transmission fails`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val clock = FakeClock(1_717_180_400L)
        val executor = CapturePhotoExecutor(
            cameraAdapter = FakeCameraAdapter(
                result = CameraCapture(
                    bytes = "jpeg-stream".encodeToByteArray(),
                    width = 640,
                    height = 480,
                ),
            ),
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                codec = DefaultLocalFrameCodec(),
                clock = clock,
                frameSender = EncodedLocalFrameSender { throw IllegalStateException("link write failed") },
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        executor.execute(requestId = "req_capture_4", command = captureCommand(maxBytes = 4096))

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED, error.error.code)
        assertTrue(error.error.retryable)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
    }

    private fun captureCommand(maxBytes: Long, mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG) = CapturePhotoCommand(
        timeoutMs = 90_000L,
        params = CapturePhotoCommandParams(quality = CapturePhotoQuality.MEDIUM),
        transfer = CaptureTransfer(
            transferId = "trf_capture_1",
            mediaType = mediaType,
            maxBytes = maxBytes,
        ),
    )
}

private class FakeCameraAdapter(
    private val result: CameraCapture? = null,
    private val failure: CameraCaptureException? = null,
) : CameraAdapter {
    override suspend fun capture(quality: CapturePhotoQuality?) = failure?.let { throw it } ?: requireNotNull(result)
}
