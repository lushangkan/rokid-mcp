package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.HelloError
import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.LinkRole
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import cn.cutemc.rokidmcp.share.protocol.PongPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalLinkSessionTest {
    private val codec = DefaultLocalFrameCodec()
    private val helloConfig = PhoneHelloConfig(
        deviceId = "phone-device",
        appVersion = "1.2.3",
        supportedActions = listOf(LocalAction.DISPLAY_TEXT),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sends hello bytes when transport becomes connected`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = FakeClock(1_717_171_800L),
            sessionScope = backgroundScope,
        )

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()

        val hello = codec.decode(transport.sentBytes.single()).header
        assertEquals(LocalMessageType.HELLO, hello.type)
        assertEquals(LinkRole.PHONE, (hello.payload as HelloPayload).role)
        assertEquals("phone-device", (hello.payload as HelloPayload).deviceId)
        assertEquals("1.2.3", (hello.payload as HelloPayload).appVersion)
        assertEquals(listOf(LocalAction.DISPLAY_TEXT), (hello.payload as HelloPayload).supportedActions)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `enters ready after accepted hello ack bytes`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = FakeClock(1_717_171_900L),
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_171_901L,
                    payload = HelloAckPayload(
                        accepted = true,
                        role = LinkRole.GLASSES,
                        capabilities = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
                        runtimeState = LocalRuntimeState.READY,
                    ),
                ),
            ),
        )
        runCurrent()

        assertTrue(events.contains(PhoneLocalSessionEvent.SessionReady))

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `emits hello rejected event when accepted is false`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = FakeClock(1_717_172_000L),
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_172_001L,
                    payload = HelloAckPayload(
                        accepted = false,
                        role = LinkRole.GLASSES,
                        error = HelloError(
                            code = "REJECTED",
                            message = "not available",
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.HelloRejected(
                    code = "REJECTED",
                    message = "not available",
                ),
            ),
        )

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `emits pong received from raw bytes when it matches pending ping`() = runTest {
        val clock = FakeClock(1_717_172_100L)
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = clock,
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_172_101L,
                    payload = HelloAckPayload(
                        accepted = true,
                        role = LinkRole.GLASSES,
                    ),
                ),
            ),
        )
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        val ping = codec.decode(transport.sentBytes.last()).header.payload as PingPayload
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.PONG,
                    timestamp = 1_717_172_102L,
                    payload = PongPayload(seq = ping.seq, nonce = ping.nonce),
                ),
            ),
        )
        runCurrent()

        assertTrue(events.contains(PhoneLocalSessionEvent.PongReceived(seq = ping.seq, receivedAt = 1_717_177_101L)))

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ignores unmatched pong and keeps timeout armed`() = runTest {
        val clock = FakeClock(1_717_172_150L)
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = clock,
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_172_151L,
                    payload = HelloAckPayload(
                        accepted = true,
                        role = LinkRole.GLASSES,
                    ),
                ),
            ),
        )
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.PONG,
                    timestamp = 1_717_172_152L,
                    payload = PongPayload(seq = 999L, nonce = "wrong"),
                ),
            ),
        )
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        assertTrue(events.none { it == PhoneLocalSessionEvent.PongReceived(seq = 999L, receivedAt = 1_717_177_151L) })
        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.SessionFailed(
                    code = "BLUETOOTH_PONG_TIMEOUT",
                    message = "pong not received in time",
                ),
            ),
        )

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ignores pong when seq matches but nonce does not`() = runTest {
        val clock = FakeClock(1_717_172_175L)
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = clock,
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_172_176L,
                    payload = HelloAckPayload(
                        accepted = true,
                        role = LinkRole.GLASSES,
                    ),
                ),
            ),
        )
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        val ping = codec.decode(transport.sentBytes.last()).header.payload as PingPayload
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.PONG,
                    timestamp = 1_717_172_177L,
                    payload = PongPayload(seq = ping.seq, nonce = "wrong-${ping.nonce}"),
                ),
            ),
        )
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        assertTrue(events.none { it == PhoneLocalSessionEvent.PongReceived(seq = ping.seq, receivedAt = 1_717_177_176L) })
        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.SessionFailed(
                    code = "BLUETOOTH_PONG_TIMEOUT",
                    message = "pong not received in time",
                ),
            ),
        )

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack timeout emits session failed`() = runTest {
        val clock = FakeClock(1_717_172_200L)
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = clock,
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.SessionFailed(
                    code = "BLUETOOTH_HELLO_TIMEOUT",
                    message = "hello ack not received in time",
                ),
            ),
        )

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `pong timeout emits session failed after keepalive starts`() = runTest {
        val clock = FakeClock(1_717_172_300L)
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = clock,
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_172_301L,
                    payload = HelloAckPayload(
                        accepted = true,
                        role = LinkRole.GLASSES,
                    ),
                ),
            ),
        )
        runCurrent()
        clock.advanceBy(5_001L)
        advanceTimeBy(10_001L)
        runCurrent()

        assertTrue(transport.sentBytes.any { codec.decode(it).header.type == LocalMessageType.PING })
        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.SessionFailed(
                    code = "BLUETOOTH_PONG_TIMEOUT",
                    message = "pong not received in time",
                ),
            ),
        )

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sends next ping on the next interval after receiving pong`() = runTest {
        val clock = FakeClock(1_717_172_350L)
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = clock,
            sessionScope = backgroundScope,
        )

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_172_351L,
                    payload = HelloAckPayload(
                        accepted = true,
                        role = LinkRole.GLASSES,
                    ),
                ),
            ),
        )
        runCurrent()

        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        val firstPing = codec.decode(transport.sentBytes.last()).header.payload as PingPayload
        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.PONG,
                    timestamp = 1_717_172_352L,
                    payload = PongPayload(seq = firstPing.seq, nonce = firstPing.nonce),
                ),
            ),
        )
        runCurrent()

        clock.advanceBy(5_001L)
        advanceTimeBy(5_001L)
        runCurrent()

        val pingFrames = transport.sentBytes.map { codec.decode(it).header }.filter { it.type == LocalMessageType.PING }
        assertEquals(2, pingFrames.size)
        assertEquals(2L, (pingFrames.last().payload as PingPayload).seq)

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `decode failure emits session failed instead of cancelling the session loop`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = FakeClock(1_717_172_375L),
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.updateState(PhoneTransportState.CONNECTED)
        runCurrent()
        transport.emitBytes(byteArrayOf(0x01, 0x02, 0x03))
        runCurrent()

        assertTrue(
            events.any {
                it is PhoneLocalSessionEvent.SessionFailed &&
                    it.code == "BLUETOOTH_PROTOCOL_ERROR" &&
                    it.message.contains("failed to decode local frame")
            },
        )

        session.stop("test complete")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session stop ends transport and closes events`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = FakeClock(1_717_172_400L),
            sessionScope = backgroundScope,
        )

        session.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()

        session.stop("user requested")
        runCurrent()

        assertEquals(listOf("user requested"), transport.stopReasons)
    }
}
