package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.glasses.GlassesApp
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.CaptureTransfer
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesGatewayServiceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `active service composition builds dispatcher-backed execution path`() = runTest {
        val app = GlassesApp()
        val transport = FakeRfcommServerTransport()
        val composition = createActiveGlassesGatewayComposition(
            app = app,
            sessionScope = backgroundScope,
            transport = transport,
            clock = FakeClock(1_717_200_000L),
            cameraAdapter = object : CameraAdapter {
                override suspend fun capture(quality: CapturePhotoQuality?) = CameraCapture(
                    bytes = "jpeg-test".encodeToByteArray(),
                    width = 640,
                    height = 480,
                )
            },
        )

        composition.commandDispatcher.handleCommand(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND,
                requestId = "req_display_1",
                timestamp = 1_717_200_001L,
                payload = DisplayTextCommand(
                    timeoutMs = 30_000L,
                    params = DisplayTextCommandParams(
                        text = "hello glasses",
                        durationMs = 2_000L,
                    ),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(transport.sentFrames.any { it.header.type == LocalMessageType.COMMAND_ACK })
        assertTrue(transport.sentFrames.last().header.type == LocalMessageType.COMMAND_RESULT)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `active service composition wires capture command to chunked result frames`() = runTest {
        val app = GlassesApp()
        val transport = FakeRfcommServerTransport()
        val composition = createActiveGlassesGatewayComposition(
            app = app,
            sessionScope = backgroundScope,
            cameraAdapter = object : CameraAdapter {
                override suspend fun capture(quality: CapturePhotoQuality?) = CameraCapture(
                    bytes = "jpeg-test".encodeToByteArray(),
                    width = 640,
                    height = 480,
                )
            },
            transport = transport,
            clock = FakeClock(1_717_200_100L),
        )

        composition.commandDispatcher.handleCommand(
            LocalFrameHeader(
                type = LocalMessageType.COMMAND,
                requestId = "req_capture_1",
                timestamp = 1_717_200_101L,
                payload = CapturePhotoCommand(
                    timeoutMs = 30_000L,
                    params = CapturePhotoCommandParams(quality = CapturePhotoQuality.MEDIUM),
                    transfer = CaptureTransfer(
                        transferId = "trf_capture_1",
                        maxBytes = 4_096L,
                    ),
                ),
            ),
        )
        runCurrent()

        assertTrue(transport.sentFrames.any { it.header.type == LocalMessageType.CHUNK_START })
        assertTrue(transport.sentFrames.any { it.header.type == LocalMessageType.CHUNK_END })
        assertTrue(transport.sentFrames.last().header.type == LocalMessageType.COMMAND_RESULT)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `starting active composition rolls back controller and session when startup fails`() = runTest {
        val app = GlassesApp()
        val transport = FakeRfcommServerTransport().apply {
            startFailure = IllegalStateException("rfcomm start failed")
        }
        val composition = createActiveGlassesGatewayComposition(
            app = app,
            sessionScope = backgroundScope,
            transport = transport,
            clock = FakeClock(1_717_200_200L),
            cameraAdapter = object : CameraAdapter {
                override suspend fun capture(quality: CapturePhotoQuality?) = CameraCapture(
                    bytes = "jpeg-test".encodeToByteArray(),
                    width = 640,
                    height = 480,
                )
            },
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { startActiveGlassesGatewayComposition(composition) }
        }
        runCurrent()

        assertEquals(listOf("startup-failed"), transport.stopReasons)
        assertEquals(GlassesRuntimeState.DISCONNECTED, app.runtimeStore.snapshot.value.runtimeState)
        assertEquals(null, app.runtimeStore.snapshot.value.lastErrorMessage)
    }
}
