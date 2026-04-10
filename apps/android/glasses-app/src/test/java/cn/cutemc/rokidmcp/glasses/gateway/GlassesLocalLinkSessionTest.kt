package cn.cutemc.rokidmcp.glasses.gateway

import android.os.Build
import cn.cutemc.rokidmcp.glasses.BuildConfig
import cn.cutemc.rokidmcp.glasses.camera.CameraAdapter
import cn.cutemc.rokidmcp.glasses.camera.CameraCapture
import cn.cutemc.rokidmcp.glasses.checksum.ChecksumCalculator
import cn.cutemc.rokidmcp.glasses.executor.CapturePhotoExecutor
import cn.cutemc.rokidmcp.glasses.executor.DisplayTextExecutor
import cn.cutemc.rokidmcp.glasses.renderer.TextRenderer
import cn.cutemc.rokidmcp.glasses.sender.GlassesFrameSender
import cn.cutemc.rokidmcp.glasses.sender.ImageChunkSender
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.CaptureTransfer
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
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

class GlassesLocalLinkSessionTest {
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
}
