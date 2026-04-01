package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.LinkRole
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import cn.cutemc.rokidmcp.share.protocol.PongPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                        supportedActions = listOf(LocalAction.DISPLAY_TEXT),
                    ),
                ),
            ),
        )
        runCurrent()

        val helloAck = transport.sentFrames.single().header
        assertEquals(LocalMessageType.HELLO_ACK, helloAck.type)
        assertTrue((helloAck.payload as HelloAckPayload).accepted)
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
        )

        session.start()
        runCurrent()

        session.stop("user requested")
        runCurrent()

        assertEquals(listOf("user requested"), transport.stopReasons)
        assertEquals(GlassesRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
    }
}
