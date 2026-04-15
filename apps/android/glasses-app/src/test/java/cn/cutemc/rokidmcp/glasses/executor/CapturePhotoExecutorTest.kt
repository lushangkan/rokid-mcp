package cn.cutemc.rokidmcp.glasses.executor

import android.util.Log
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.camera.CameraCaptureException
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.gateway.FakeClock
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
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
import cn.cutemc.rokidmcp.share.protocol.local.ExecutingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
                clock = clock,
                frameSender = GlassesFrameSender { header, body -> chunkFrames += header to body },
                chunkSizeBytes = 4,
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        val logs = captureTimberLogs { runBlocking { executor.execute(requestId = "req_capture_1", command = captureCommand(maxBytes = 4096)) } }

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
        logs.assertLog(Log.INFO, "capture-photo", "capture_photo execution start requestId=req_capture_1 transferId=trf_capture_1")
        logs.assertLog(Log.INFO, "capture-photo", "sending capture_photo ack requestId=req_capture_1")
        logs.assertLog(Log.INFO, "capture-photo", "sending capture_photo status=executing requestId=req_capture_1")
        logs.assertLog(Log.INFO, "capture-photo", "sending capture_photo status=capturing requestId=req_capture_1")
        logs.assertLog(Log.INFO, "capture-photo", "camera capture start requestId=req_capture_1")
        logs.assertLog(Log.INFO, "capture-photo", "camera capture success requestId=req_capture_1 transferId=trf_capture_1")
        logs.assertLog(Log.INFO, "image-chunk", "image transfer start requestId=req_capture_1 transferId=trf_capture_1")
        logs.assertLog(Log.VERBOSE, "image-chunk", "image chunk progress requestId=req_capture_1 transferId=trf_capture_1 index=0")
        logs.assertLog(Log.INFO, "image-chunk", "image transfer complete requestId=req_capture_1 transferId=trf_capture_1 totalChunks=3")
        logs.assertLog(Log.INFO, "capture-photo", "sending capture_photo result requestId=req_capture_1 transferId=trf_capture_1")
        logs.assertLog(Log.INFO, "capture-photo", "capture_photo execution complete requestId=req_capture_1 transferId=trf_capture_1")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `executor emits command error when camera capture fails`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val chunkFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val clock = FakeClock(1_717_180_200L)
        val executor = CapturePhotoExecutor(
            cameraAdapter = FakeCameraAdapter(
                failure = CameraCaptureException(
                    code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                    message = "camera offline",
                ),
            ),
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                clock = clock,
                frameSender = GlassesFrameSender { header, body -> chunkFrames += header to body },
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        val logs = captureTimberLogs { runBlocking { executor.execute(requestId = "req_capture_2", command = captureCommand(maxBytes = 4096)) } }

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.CAMERA_UNAVAILABLE, error.error.code)
        assertTrue(error.error.retryable)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
        assertTrue(chunkFrames.isEmpty())
        logs.assertLog(Log.ERROR, "capture-photo", "camera capture failed requestId=req_capture_2")
        logs.assertLog(Log.WARN, "capture-photo", "sending capture_photo error requestId=req_capture_2 code=CAMERA_UNAVAILABLE retryable=true")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `executor rejects non jpeg transfer metadata before sending chunks`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val chunkFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val clock = FakeClock(1_717_180_300L)
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
                clock = clock,
                frameSender = GlassesFrameSender { header, body -> chunkFrames += header to body },
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        val logs = captureTimberLogs {
            runBlocking {
                executor.execute(
                    requestId = "req_capture_3",
                    command = captureCommand(maxBytes = 4096, mediaType = "image/png"),
                )
            }
        }

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.UNSUPPORTED_PROTOCOL, error.error.code)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
        assertTrue(chunkFrames.isEmpty())
        logs.assertLog(Log.WARN, "capture-photo", "capture_photo validation failed requestId=req_capture_3 transferId=trf_capture_1 code=UNSUPPORTED_PROTOCOL")
        logs.assertLog(Log.WARN, "capture-photo", "sending capture_photo error requestId=req_capture_3 code=UNSUPPORTED_PROTOCOL retryable=false")
        logs.assertNoSensitiveData()
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
                clock = clock,
                frameSender = GlassesFrameSender { _, _ -> throw IllegalStateException("link write failed") },
            ),
            clock = clock,
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        val logs = captureTimberLogs { runBlocking { executor.execute(requestId = "req_capture_4", command = captureCommand(maxBytes = 4096)) } }

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED, error.error.code)
        assertTrue(error.error.retryable)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
        logs.assertLog(Log.ERROR, "image-chunk", "failed to send image transfer frame type=CHUNK_START requestId=req_capture_4 transferId=trf_capture_1")
        logs.assertLog(Log.WARN, "capture-photo", "capture_photo image transfer failed requestId=req_capture_4 transferId=trf_capture_1 code=BLUETOOTH_SEND_FAILED")
        logs.assertLog(Log.WARN, "capture-photo", "sending capture_photo error requestId=req_capture_4 code=BLUETOOTH_SEND_FAILED retryable=true")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `executor emits command error when adapter throws unexpected failure`() = runTest {
        val commandFrames = mutableListOf<Pair<LocalFrameHeader<*>, ByteArray?>>()
        val executor = CapturePhotoExecutor(
            cameraAdapter = object : CameraAdapter {
                override suspend fun capture(quality: CapturePhotoQuality?) = throw IOException("disk read failed")
            },
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                clock = FakeClock(1_717_180_500L),
                frameSender = GlassesFrameSender { _, _ -> },
            ),
            clock = FakeClock(1_717_180_500L),
            frameSender = GlassesFrameSender { header, body -> commandFrames += header to body },
        )

        val logs = captureTimberLogs { runBlocking { executor.execute(requestId = "req_capture_5", command = captureCommand(maxBytes = 4096)) } }

        assertEquals(LocalMessageType.COMMAND_ERROR, commandFrames.last().first.type)
        val error = commandFrames.last().first.payload as CommandError
        assertEquals(LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED, error.error.code)
        assertEquals("disk read failed", error.error.message)
        assertTrue(commandFrames.none { it.first.type == LocalMessageType.COMMAND_RESULT })
        logs.assertLog(Log.ERROR, "capture-photo", "unexpected camera capture failure requestId=req_capture_5")
        logs.assertLog(Log.WARN, "capture-photo", "sending capture_photo error requestId=req_capture_5 code=CAMERA_CAPTURE_FAILED retryable=false")
        logs.assertNoSensitiveData()
    }

    @Test(expected = CancellationException::class)
    fun `executor preserves cancellation from adapter`() = runTest {
        val executor = CapturePhotoExecutor(
            cameraAdapter = object : CameraAdapter {
                override suspend fun capture(quality: CapturePhotoQuality?) = throw CancellationException("capture cancelled")
            },
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                clock = FakeClock(1_717_180_600L),
                frameSender = GlassesFrameSender { _, _ -> },
            ),
            clock = FakeClock(1_717_180_600L),
            frameSender = GlassesFrameSender { _, _ -> },
        )

        executor.execute(requestId = "req_capture_6", command = captureCommand(maxBytes = 4096))
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
