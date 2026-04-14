package cn.cutemc.rokidmcp.glasses.gateway

import android.util.Log
import cn.cutemc.rokidmcp.glasses.GlassesApp
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.logging.CapturingTimberTree
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

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

    @Test
    fun `starting active composition emits service and controller traces`() {
        val app = GlassesApp()
        val transport = FakeRfcommServerTransport()
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val composition = createActiveGlassesGatewayComposition(
                app = app,
                sessionScope = sessionScope,
                transport = transport,
                clock = FakeClock(1_717_200_300L),
                cameraAdapter = object : CameraAdapter {
                    override suspend fun capture(quality: CapturePhotoQuality?) = CameraCapture(
                        bytes = "jpeg-test".encodeToByteArray(),
                        width = 640,
                        height = 480,
                    )
                },
            )

            val logs = captureTimberLogs {
                runBlocking {
                    startActiveGlassesGatewayComposition(composition)
                }
            }

            logs.assertLog(Log.DEBUG, "gateway-service", "starting active glasses gateway composition")
            logs.assertLog(Log.INFO, "glasses-controller", "controller start")
            logs.assertLog(Log.DEBUG, "glasses-controller", "transport state DISCONNECTED -> CONNECTING")
            logs.assertLog(Log.INFO, "gateway-service", "active glasses gateway composition started")
            logs.assertNoSensitiveData()
        } finally {
            sessionScope.cancel()
        }
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

        val logs = captureLogs {
            try {
                startActiveGlassesGatewayComposition(composition)
                throw AssertionError("Expected startup to fail when transport.start throws")
            } catch (error: IllegalStateException) {
                assertEquals("rfcomm start failed", error.message)
            }
        }

        assertEquals(listOf("startup-failed"), transport.stopReasons)
        assertEquals(GlassesRuntimeState.DISCONNECTED, app.runtimeStore.snapshot.value.runtimeState)
        assertEquals(null, app.runtimeStore.snapshot.value.lastErrorMessage)
        logs.assertLog(Log.ERROR, "gateway-service", "failed to start glasses gateway composition")
        logs.assertLog(Log.INFO, "glasses-controller", "controller start")
        logs.assertLog(Log.WARN, "glasses-controller", "runtime disconnected")
        logs.assertNoSensitiveData()
    }

    private suspend fun captureLogs(block: suspend () -> Unit): List<CapturingTimberTree.LogEntry> {
        val tree = CapturingTimberTree()
        Timber.plant(tree)

        return try {
            block()
            tree.logs
        } finally {
            Timber.uproot(tree)
        }
    }
}
