package cn.cutemc.rokidmcp.glasses.gateway

import android.os.Build
import android.util.Log
import cn.cutemc.rokidmcp.glasses.BuildConfig
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.logging.CapturingTimberTree
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.CaptureTransfer
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber

class GlassesLocalLinkSessionTest {
    private companion object {
        const val LOG_TAG = "glasses-session"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello frame event sends accepted hello ack and marks runtime ready`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_171_800L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_171_800L)),
        )

        session.start()
        runCurrent()
        transport.emit(
            GlassesTransportEvent.FrameReceived(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO,
                    timestamp = 1_717_171_801L,
                    payload = HelloPayload(
                        role = LinkRole.PHONE,
                        deviceId = "phone-device",
                        appVersion = "1.0.0",
                        supportedActions = listOf(CommandAction.DISPLAY_TEXT),
                    ),
                ),
            ),
        )
        runCurrent()

        val helloAck = transport.sentFrames.single().header
        assertEquals(LocalMessageType.HELLO_ACK, helloAck.type)
        assertTrue((helloAck.payload as HelloAckPayload).accepted)
        assertEquals(
            listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
            (helloAck.payload as HelloAckPayload).capabilities,
        )
        assertEquals(Build.MODEL, (helloAck.payload as HelloAckPayload).glassesInfo?.model)
        assertEquals(BuildConfig.VERSION_NAME, (helloAck.payload as HelloAckPayload).glassesInfo?.appVersion)
        assertEquals(
            LocalRuntimeState.READY,
            (helloAck.payload as HelloAckPayload).runtimeState,
        )
        assertEquals(GlassesRuntimeState.READY, runtimeStore.snapshot.value.runtimeState)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello handshake logs session milestones`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_171_850L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_171_850L)),
        )

        val logs = captureLogs {
            session.start()
            runCurrent()
            transport.emit(
                GlassesTransportEvent.FrameReceived(
                    LocalFrameHeader(
                        type = LocalMessageType.HELLO,
                        timestamp = 1_717_171_851L,
                        payload = HelloPayload(
                            role = LinkRole.PHONE,
                            deviceId = "phone-device",
                            appVersion = "1.0.0",
                            supportedActions = listOf(CommandAction.DISPLAY_TEXT),
                        ),
                    ),
                ),
            )
            runCurrent()
            session.stop("test complete")
            runCurrent()
        }

        logs.assertLog(Log.INFO, LOG_TAG, "starting glasses local link session")
        logs.assertLog(Log.INFO, LOG_TAG, "observed transport state=LISTENING")
        logs.assertLog(Log.INFO, LOG_TAG, "received HELLO role=PHONE deviceId=phone-device appVersion=1.0.0 actions=1")
        logs.assertLog(Log.INFO, LOG_TAG, "sent HELLO_ACK accepted=true role=GLASSES capabilities=2 runtimeState=READY")
        logs.assertLog(Log.INFO, LOG_TAG, "stopping glasses local link session reason=test complete")
        logs.assertLog(Log.INFO, LOG_TAG, "glasses transport disconnected reason=test complete")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ping frame event sends pong`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_171_900L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_171_900L)),
        )

        session.start()
        runCurrent()
        transport.emit(
            GlassesTransportEvent.FrameReceived(
                LocalFrameHeader(
                    type = LocalMessageType.PING,
                    timestamp = 1_717_171_901L,
                    payload = PingPayload(seq = 7, nonce = "nonce-7"),
                ),
            ),
        )
        runCurrent()

        val pong = transport.sentFrames.single().header
        assertEquals(LocalMessageType.PONG, pong.type)
        val payload = pong.payload as PongPayload
        assertEquals(7L, payload.seq)
        assertEquals("nonce-7", payload.nonce)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ping frame logs ping and pong traces`() = runTest {
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = FakeClock(1_717_171_950L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_171_950L)),
        )

        val logs = captureLogs {
            session.start()
            runCurrent()
            transport.emit(
                GlassesTransportEvent.FrameReceived(
                    LocalFrameHeader(
                        type = LocalMessageType.PING,
                        timestamp = 1_717_171_951L,
                        payload = PingPayload(seq = 11, nonce = "nonce-11"),
                    ),
                ),
            )
            runCurrent()
            session.stop("test complete")
            runCurrent()
        }

        logs.assertLog(Log.VERBOSE, LOG_TAG, "received PING seq=11 nonce=nonce-11")
        logs.assertLog(Log.VERBOSE, LOG_TAG, "sent PONG seq=11 nonce=nonce-11")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transport failure event moves runtime state to error`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_000L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_000L)),
        )

        session.start()
        runCurrent()
        transport.emit(GlassesTransportEvent.Failure(IllegalStateException("rfcomm broken")))
        runCurrent()

        assertEquals(GlassesRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transport failure event logs error outcome`() = runTest {
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = FakeClock(1_717_172_050L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_050L)),
        )

        val logs = captureLogs {
            session.start()
            runCurrent()
            transport.emit(GlassesTransportEvent.Failure(IllegalStateException("rfcomm broken")))
            runCurrent()
            session.stop("test complete")
            runCurrent()
        }

        logs.assertLog(Log.ERROR, LOG_TAG, "glasses transport failure")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transport failure event requests local link recovery`() = runTest {
        val transport = FakeRfcommServerTransport()
        val recoveryRequests = mutableListOf<Pair<String, String>>()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = FakeClock(1_717_172_060L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_060L)),
            onLocalLinkFailure = { reason, cause ->
                recoveryRequests += reason to (cause.message ?: "unknown")
            },
        )

        session.start()
        runCurrent()
        transport.emit(GlassesTransportEvent.Failure(IllegalStateException("rfcomm broken")))
        runCurrent()

        assertEquals(listOf("transport-failure" to "rfcomm broken"), recoveryRequests)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transport connection closed event moves runtime state to disconnected`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_100L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_100L)),
        )

        session.start()
        runCurrent()
        transport.emit(GlassesTransportEvent.Failure(IllegalStateException("temporary")))
        runCurrent()
        assertEquals(GlassesRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)

        transport.emit(GlassesTransportEvent.ConnectionClosed("link closed"))
        runCurrent()

        assertEquals(GlassesRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session stop ends with disconnected runtime state`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_200L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_200L)),
        )

        session.start()
        runCurrent()

        session.stop("user requested")
        runCurrent()

        assertEquals(listOf("user requested"), transport.stopReasons)
        assertEquals(GlassesRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start is idempotent and does not re-start transport`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_300L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_300L)),
        )

        session.start()
        runCurrent()
        session.start()
        runCurrent()

        assertEquals(1, transport.startCount)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transport start failure marks runtime error and allows retry`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport().apply {
            startFailure = IllegalStateException("rfcomm start failed")
        }
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_320L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_320L)),
        )

        try {
            session.start()
            fail("Expected start to fail when transport.start throws")
        } catch (error: IllegalStateException) {
            assertEquals("rfcomm start failed", error.message)
        }
        runCurrent()

        assertEquals(GlassesRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("rfcomm start failed", runtimeStore.snapshot.value.lastErrorMessage)

        transport.startFailure = null
        session.start()
        runCurrent()

        assertEquals(2, transport.startCount)
        assertEquals(GlassesRuntimeState.CONNECTING, runtimeStore.snapshot.value.runtimeState)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack send failure marks runtime error without reporting ready`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport().apply {
            sendFailure = IllegalStateException("ack send failed")
        }
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_330L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_330L)),
        )

        session.start()
        runCurrent()
        transport.emit(
            GlassesTransportEvent.FrameReceived(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO,
                    timestamp = 1_717_172_331L,
                    payload = HelloPayload(
                        role = LinkRole.PHONE,
                        deviceId = "phone-device",
                        appVersion = "1.0.0",
                        supportedActions = listOf(CommandAction.DISPLAY_TEXT),
                    ),
                ),
            ),
        )
        runCurrent()

        assertTrue(transport.sentFrames.isEmpty())
        assertEquals(GlassesRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("ack send failed", runtimeStore.snapshot.value.lastErrorMessage)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack send failure logs warning`() = runTest {
        val transport = FakeRfcommServerTransport().apply {
            sendFailure = IllegalStateException("ack send failed")
        }
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = FakeClock(1_717_172_340L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_340L)),
        )

        val logs = captureLogs {
            session.start()
            runCurrent()
            transport.emit(
                GlassesTransportEvent.FrameReceived(
                    LocalFrameHeader(
                        type = LocalMessageType.HELLO,
                        timestamp = 1_717_172_341L,
                        payload = HelloPayload(
                            role = LinkRole.PHONE,
                            deviceId = "phone-device",
                            appVersion = "1.0.0",
                            supportedActions = listOf(CommandAction.DISPLAY_TEXT),
                        ),
                    ),
                ),
            )
            runCurrent()
            session.stop("test complete")
            runCurrent()
        }

        logs.assertLog(Log.WARN, LOG_TAG, "failed to send hello_ack frame")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack send failure requests local link recovery`() = runTest {
        val transport = FakeRfcommServerTransport().apply {
            sendFailure = IllegalStateException("ack send failed")
        }
        val recoveryRequests = mutableListOf<Pair<String, String>>()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = FakeClock(1_717_172_345L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_345L)),
            onLocalLinkFailure = { reason, cause ->
                recoveryRequests += reason to (cause.message ?: "unknown")
            },
        )

        session.start()
        runCurrent()
        transport.emit(
            GlassesTransportEvent.FrameReceived(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO,
                    timestamp = 1_717_172_346L,
                    payload = HelloPayload(
                        role = LinkRole.PHONE,
                        deviceId = "phone-device",
                        appVersion = "1.0.0",
                        supportedActions = listOf(CommandAction.DISPLAY_TEXT),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(listOf("frame-send-failed:hello_ack" to "ack send failed"), recoveryRequests)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start failure cleans up collection job and allows retry`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport().apply {
            startFailure = IllegalStateException("bluetooth connect permission is not granted")
        }
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_320L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_320L)),
        )

        try {
            session.start()
            fail("Expected start to fail when transport.start throws")
        } catch (error: IllegalStateException) {
            assertEquals("bluetooth connect permission is not granted", error.message)
        }
        runCurrent()

        transport.startFailure = null
        session.start()
        runCurrent()

        assertEquals(2, transport.startCount)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connection closed clears stale error message`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = FakeClock(1_717_172_400L),
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, FakeClock(1_717_172_400L)),
        )

        session.start()
        runCurrent()
        transport.emit(GlassesTransportEvent.Failure(IllegalStateException("temporary")))
        runCurrent()
        assertEquals(GlassesRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("temporary", runtimeStore.snapshot.value.lastErrorMessage)

        transport.emit(GlassesTransportEvent.ConnectionClosed("closed"))
        runCurrent()

        assertEquals(GlassesRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
        assertEquals(null, runtimeStore.snapshot.value.lastErrorMessage)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `capture photo command runs executor and returns result after chunk transfer`() = runTest {
        val runtimeStore = GlassesRuntimeStore()
        val controller = GlassesAppController(runtimeStore = runtimeStore)
        val transport = FakeRfcommServerTransport()
        val clock = FakeClock(1_717_172_450L)
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = controller,
            clock = clock,
            sessionScope = backgroundScope,
            commandDispatcher = captureAwareCommandDispatcher(backgroundScope, transport, clock),
        )

        session.start()
        runCurrent()
        transport.emit(
            GlassesTransportEvent.FrameReceived(
                LocalFrameHeader(
                    type = LocalMessageType.COMMAND,
                    requestId = "req_capture_1",
                    timestamp = 1_717_172_451L,
                    payload = CapturePhotoCommand(
                        timeoutMs = 90_000L,
                        params = CapturePhotoCommandParams(),
                        transfer = CaptureTransfer(
                            transferId = "trf_capture_1",
                            maxBytes = 4_096L,
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        runCurrent()

        assertEquals(LocalMessageType.COMMAND_ACK, transport.sentFrames[0].header.type)
        assertEquals(LocalMessageType.CHUNK_START, transport.sentFrames[3].header.type)
        assertEquals(LocalMessageType.COMMAND_RESULT, transport.sentFrames.last().header.type)
        val result = transport.sentFrames.last().header.payload as CapturePhotoResult
        assertEquals(800, result.result.width)
        assertEquals(600, result.result.height)
        assertEquals(null, runtimeStore.snapshot.value.lastErrorMessage)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `command frame logs handoff before dispatch`() = runTest {
        val transport = FakeRfcommServerTransport()
        val clock = FakeClock(1_717_172_460L)
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = clock,
            sessionScope = backgroundScope,
            commandDispatcher = testCommandDispatcher(backgroundScope, transport, clock),
        )

        val logs = captureLogs {
            session.start()
            runCurrent()
            transport.emit(
                GlassesTransportEvent.FrameReceived(
                    LocalFrameHeader(
                        type = LocalMessageType.COMMAND,
                        requestId = "req_display_1",
                        timestamp = 1_717_172_461L,
                        payload = DisplayTextCommand(
                            timeoutMs = 5_000L,
                            params = DisplayTextCommandParams(
                                text = "hello glasses",
                                durationMs = 1_000L,
                            ),
                        ),
                    ),
                ),
            )
            runCurrent()
            session.stop("test complete")
            runCurrent()
        }

        logs.assertLog(Log.INFO, LOG_TAG, "handing off COMMAND frame requestId=req_display_1 payload=DisplayTextCommand")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack capabilities reflect configured dispatcher actions`() = runTest {
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            controller = GlassesAppController(GlassesRuntimeStore()),
            clock = FakeClock(1_717_172_550L),
            sessionScope = backgroundScope,
            commandDispatcher = CommandDispatcher(
                clock = FakeClock(1_717_172_550L),
                scope = backgroundScope,
                frameSender = GlassesFrameSender(transport::send),
                exclusiveGuard = ExclusiveExecutionGuard(),
                displayTextExecutor = cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor(
                    textRenderer = cn.cutemc.rokidmcp.glasses.renderer.TextRenderer { _, _ -> Unit },
                    clock = FakeClock(1_717_172_550L),
                ),
                capturePhotoExecutor = testCapturePhotoExecutor(transport, FakeClock(1_717_172_550L)),
            ),
        )

        session.start()
        runCurrent()
        transport.emit(
            GlassesTransportEvent.FrameReceived(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO,
                    timestamp = 1_717_172_551L,
                    payload = HelloPayload(
                        role = LinkRole.PHONE,
                        deviceId = "phone-device",
                        appVersion = "1.0.0",
                        supportedActions = listOf(CommandAction.DISPLAY_TEXT),
                    ),
                ),
            ),
        )
        runCurrent()

        val helloAck = transport.sentFrames.single().header.payload as HelloAckPayload
        assertEquals(listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO), helloAck.capabilities)

        session.stop("test complete")
    }

    private fun testCommandDispatcher(
        scope: kotlinx.coroutines.CoroutineScope,
        transport: FakeRfcommServerTransport,
        clock: FakeClock,
    ) = CommandDispatcher(
        clock = clock,
        scope = scope,
        frameSender = GlassesFrameSender(transport::send),
        exclusiveGuard = ExclusiveExecutionGuard(),
        displayTextExecutor = DisplayTextExecutor(
            textRenderer = TextRenderer { _, _ -> Unit },
            clock = clock,
        ),
        capturePhotoExecutor = testCapturePhotoExecutor(transport, clock),
    )

    private fun captureAwareCommandDispatcher(
        scope: kotlinx.coroutines.CoroutineScope,
        transport: FakeRfcommServerTransport,
        clock: FakeClock,
    ) = CommandDispatcher(
        clock = clock,
        scope = scope,
        frameSender = GlassesFrameSender(transport::send),
        exclusiveGuard = ExclusiveExecutionGuard(),
        displayTextExecutor = DisplayTextExecutor(
            textRenderer = TextRenderer { _, _ -> Unit },
            clock = clock,
        ),
        capturePhotoExecutor = CapturePhotoExecutor(
            cameraAdapter = object : CameraAdapter {
                override suspend fun capture(quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?) =
                    CameraCapture(
                        bytes = "jpeg-session".encodeToByteArray(),
                        width = 800,
                        height = 600,
                    )
            },
            checksumCalculator = ChecksumCalculator(),
            imageChunkSender = ImageChunkSender(
                clock = clock,
                frameSender = GlassesFrameSender(transport::send),
                chunkSizeBytes = 4,
            ),
            clock = clock,
            frameSender = GlassesFrameSender(transport::send),
        ),
    )

    private fun testCapturePhotoExecutor(
        transport: FakeRfcommServerTransport,
        clock: FakeClock,
    ) = CapturePhotoExecutor(
        cameraAdapter = object : CameraAdapter {
            override suspend fun capture(quality: cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality?) =
                CameraCapture(
                    bytes = "jpeg-capabilities".encodeToByteArray(),
                    width = 640,
                    height = 480,
                )
        },
        checksumCalculator = ChecksumCalculator(),
        imageChunkSender = ImageChunkSender(
            clock = clock,
            frameSender = GlassesFrameSender(transport::send),
        ),
        clock = clock,
        frameSender = GlassesFrameSender(transport::send),
    )

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
