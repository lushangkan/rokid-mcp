package cn.cutemc.rokidmcp.glasses.gateway

import android.util.Log
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextResult
import cn.cutemc.rokidmcp.share.protocol.local.DisplayingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.ExecutingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDispatcherTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatcher emits ack statuses and result for display_text`() = runTest {
        val frames = mutableListOf<LocalFrameHeader<*>>()
        val dispatcher = CommandDispatcher(
            clock = FakeClock(1_717_191_100L),
            scope = backgroundScope,
            frameSender = GlassesFrameSender { header, _ -> frames += header },
            exclusiveGuard = ExclusiveExecutionGuard(),
            displayTextExecutor = DisplayTextExecutor(
                textRenderer = TextRenderer { _, _ -> Unit },
                clock = FakeClock(1_717_191_100L),
            ),
            capturePhotoExecutor = testCapturePhotoExecutor(),
        )

        val logs = captureTimberLogs {
            runBlocking { dispatcher.handleCommand(displayCommand("req_display_1")) }
            runCurrent()
        }

        assertEquals(
            listOf(
                LocalMessageType.COMMAND_ACK,
                LocalMessageType.COMMAND_STATUS,
                LocalMessageType.COMMAND_STATUS,
                LocalMessageType.COMMAND_RESULT,
            ),
            frames.map { it.type },
        )
        assertTrue(frames[1].payload is ExecutingCommandStatus)
        assertTrue(frames[2].payload is DisplayingCommandStatus)
        val result = frames[3].payload as DisplayTextResult
        assertTrue(result.result.displayed)
        assertEquals(3_000L, result.result.durationMs)
        logs.assertLog(Log.INFO, "command-dispatch", "dispatcher entry requestId=req_display_1 payload=DisplayTextCommand")
        logs.assertLog(Log.INFO, "command-dispatch", "selected display_text branch requestId=req_display_1")
        logs.assertLog(Log.INFO, "command-dispatch", "sending command ack requestId=req_display_1")
        logs.assertLog(Log.INFO, "command-dispatch", "sending executing status requestId=req_display_1")
        logs.assertLog(Log.INFO, "command-dispatch", "sending displaying status requestId=req_display_1")
        logs.assertLog(Log.INFO, "command-dispatch", "sending display_text result requestId=req_display_1")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatcher rejects overlapping display_text commands`() = runTest {
        val frames = mutableListOf<LocalFrameHeader<*>>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = CommandDispatcher(
            clock = FakeClock(1_717_191_200L),
            scope = backgroundScope,
            frameSender = GlassesFrameSender { header, _ -> frames += header },
            exclusiveGuard = ExclusiveExecutionGuard(),
            displayTextExecutor = DisplayTextExecutor(
                textRenderer = TextRenderer { _, _ -> gate.await() },
                clock = FakeClock(1_717_191_200L),
            ),
            capturePhotoExecutor = testCapturePhotoExecutor(),
        )

        val logs = captureTimberLogs {
            runBlocking { dispatcher.handleCommand(displayCommand("req_display_1")) }
            runCurrent()
            runBlocking { dispatcher.handleCommand(displayCommand("req_display_2")) }
            runCurrent()
        }

        assertEquals(LocalMessageType.COMMAND_ERROR, frames.last().type)
        val busy = frames.last().payload as CommandError
        assertEquals(LocalProtocolErrorCodes.COMMAND_BUSY, busy.error.code)
        logs.assertLog(Log.WARN, "command-dispatch", "busy rejection requestId=req_display_2 action=DISPLAY_TEXT")
        logs.assertLog(Log.WARN, "command-dispatch", "sending command error requestId=req_display_2 action=DISPLAY_TEXT code=COMMAND_BUSY")
        logs.assertNoSensitiveData()

        gate.complete(Unit)
        runCurrent()

        assertEquals(LocalMessageType.COMMAND_RESULT, frames.last().type)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatcher reports displaying before a blocking renderer completes`() = runTest {
        val frames = mutableListOf<LocalFrameHeader<*>>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = CommandDispatcher(
            clock = FakeClock(1_717_191_300L),
            scope = backgroundScope,
            frameSender = GlassesFrameSender { header, _ -> frames += header },
            exclusiveGuard = ExclusiveExecutionGuard(),
            displayTextExecutor = DisplayTextExecutor(
                textRenderer = TextRenderer { _, _ -> gate.await() },
                clock = FakeClock(1_717_191_300L),
            ),
            capturePhotoExecutor = testCapturePhotoExecutor(),
        )

        dispatcher.handleCommand(displayCommand("req_display_3"))
        runCurrent()

        assertEquals(
            listOf(
                LocalMessageType.COMMAND_ACK,
                LocalMessageType.COMMAND_STATUS,
                LocalMessageType.COMMAND_STATUS,
            ),
            frames.map { it.type },
        )
        assertTrue(frames[2].payload is DisplayingCommandStatus)

        gate.complete(Unit)
        runCurrent()

        assertEquals(LocalMessageType.COMMAND_RESULT, frames.last().type)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatcher falls back to command error when display response send fails`() = runTest {
        val frames = mutableListOf<LocalFrameHeader<*>>()
        var failFirstSend = true
        val dispatcher = CommandDispatcher(
            clock = FakeClock(1_717_191_400L),
            scope = backgroundScope,
            frameSender = GlassesFrameSender { header, _ ->
                if (failFirstSend) {
                    failFirstSend = false
                    throw IllegalStateException("ack send failed")
                }
                frames += header
            },
            exclusiveGuard = ExclusiveExecutionGuard(),
            displayTextExecutor = DisplayTextExecutor(
                textRenderer = TextRenderer { _, _ -> Unit },
                clock = FakeClock(1_717_191_400L),
            ),
            capturePhotoExecutor = testCapturePhotoExecutor(),
        )

        val logs = captureTimberLogs {
            runBlocking { dispatcher.handleCommand(displayCommand("req_display_4")) }
            runCurrent()
        }

        assertEquals(1, frames.size)
        assertEquals(LocalMessageType.COMMAND_ERROR, frames.single().type)
        val error = frames.single().payload as CommandError
        assertEquals(LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED, error.error.code)
        assertEquals("ack send failed", error.error.message)
        logs.assertLog(Log.ERROR, "command-dispatch", "display_text command failed for requestId=req_display_4")
        logs.assertLog(Log.WARN, "command-dispatch", "sending command error requestId=req_display_4 action=DISPLAY_TEXT code=BLUETOOTH_SEND_FAILED")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatcher falls back to command error when capture executor send fails`() = runTest {
        val frames = mutableListOf<LocalFrameHeader<*>>()
        val dispatcher = CommandDispatcher(
            clock = FakeClock(1_717_191_500L),
            scope = backgroundScope,
            frameSender = GlassesFrameSender { header, _ -> frames += header },
            exclusiveGuard = ExclusiveExecutionGuard(),
            displayTextExecutor = DisplayTextExecutor(
                textRenderer = TextRenderer { _, _ -> Unit },
                clock = FakeClock(1_717_191_500L),
            ),
            capturePhotoExecutor = CapturePhotoExecutor(
                cameraAdapter = object : CameraAdapter {
                    override suspend fun capture(quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?) = CameraCapture(
                        bytes = "jpeg-test".encodeToByteArray(),
                        width = 640,
                        height = 480,
                    )
                },
                checksumCalculator = ChecksumCalculator(),
                imageChunkSender = ImageChunkSender(
                    clock = FakeClock(1_717_191_500L),
                    frameSender = GlassesFrameSender { _, _ -> },
                ),
                clock = FakeClock(1_717_191_500L),
                frameSender = GlassesFrameSender { _, _ -> throw IllegalStateException("capture ack send failed") },
            ),
        )

        val logs = captureTimberLogs {
            runBlocking {
                dispatcher.handleCommand(
                    LocalFrameHeader(
                        type = LocalMessageType.COMMAND,
                        requestId = "req_capture_2",
                        timestamp = 1_717_191_501L,
                        payload = cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand(
                            timeoutMs = 30_000L,
                            params = cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommandParams(),
                            transfer = cn.cutemc.rokidmcp.share.protocol.local.CaptureTransfer(
                                transferId = "trf_capture_2",
                                maxBytes = 4_096L,
                            ),
                        ),
                    ),
                )
            }
            runCurrent()
        }

        assertEquals(1, frames.size)
        assertEquals(LocalMessageType.COMMAND_ERROR, frames.single().type)
        val error = frames.single().payload as CommandError
        assertEquals(LocalProtocolErrorCodes.BLUETOOTH_SEND_FAILED, error.error.code)
        assertEquals("capture ack send failed", error.error.message)
        logs.assertLog(Log.INFO, "command-dispatch", "selected capture_photo branch requestId=req_capture_2")
        logs.assertLog(Log.ERROR, "command-dispatch", "capture_photo command failed for requestId=req_capture_2")
        logs.assertLog(Log.WARN, "command-dispatch", "sending command error requestId=req_capture_2 action=CAPTURE_PHOTO code=BLUETOOTH_SEND_FAILED")
        logs.assertNoSensitiveData()
    }

    private fun displayCommand(requestId: String) = LocalFrameHeader(
        type = LocalMessageType.COMMAND,
        requestId = requestId,
        timestamp = 1_717_191_000L,
        payload = DisplayTextCommand(
            timeoutMs = 30_000L,
            params = DisplayTextCommandParams(
                text = "hello glasses",
                durationMs = 3_000L,
            ),
        ),
    )

    private fun testCapturePhotoExecutor() = CapturePhotoExecutor(
        cameraAdapter = object : CameraAdapter {
            override suspend fun capture(quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?) = CameraCapture(
                bytes = "jpeg-test".encodeToByteArray(),
                width = 640,
                height = 480,
            )
        },
        checksumCalculator = ChecksumCalculator(),
        imageChunkSender = ImageChunkSender(
            clock = FakeClock(1_717_191_000L),
            frameSender = GlassesFrameSender { _, _ -> },
        ),
        clock = FakeClock(1_717_191_000L),
        frameSender = GlassesFrameSender { _, _ -> },
    )
}
