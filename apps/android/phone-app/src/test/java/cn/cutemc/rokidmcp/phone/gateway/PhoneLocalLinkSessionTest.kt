package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.HelloError
import cn.cutemc.rokidmcp.share.protocol.local.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
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
        supportedActions = listOf(CommandAction.DISPLAY_TEXT),
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
        assertEquals(listOf(CommandAction.DISPLAY_TEXT), (hello.payload as HelloPayload).supportedActions)

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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
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
                            capabilities = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
                            runtimeState = LocalRuntimeState.READY,
                        ),
                    ),
                ),
            )
            runCurrent()
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        assertTrue(events.contains(PhoneLocalSessionEvent.SessionReady))
        logs.assertLog(Log.INFO, "local-session", "starting phone local link session")
        logs.assertLog(Log.INFO, "local-session", "transport connected; sending HELLO")
        logs.assertLog(Log.INFO, "local-session", "sent HELLO")
        logs.assertLog(Log.INFO, "local-session", "armed HELLO_ACK timeout")
        logs.assertLog(Log.VERBOSE, "local-session", "dispatching HELLO_ACK frame")
        logs.assertLog(Log.INFO, "local-session", "HELLO_ACK accepted")
        logs.assertLog(Log.INFO, "local-session", "local session ready")
        logs.assertLog(Log.INFO, "local-session", "starting keepalive loop")
        logs.assertLog(Log.INFO, "local-session", "stopping phone local link session")
        logs.assertLog(Log.INFO, "local-session", "terminating phone local link session")
        logs.assertLog(Log.INFO, "local-session", "clearing session state")
        logs.assertNoSensitiveData()
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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
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
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.HelloRejected(
                    code = "REJECTED",
                    message = "not available",
                ),
            ),
        )
        logs.assertLog(Log.VERBOSE, "local-session", "dispatching HELLO_ACK frame")
        logs.assertLog(Log.WARN, "local-session", "HELLO_ACK rejected code=REJECTED")
        logs.assertNoSensitiveData()
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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
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
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        val ping = codec.decode(transport.sentBytes.last { codec.decode(it).header.type == LocalMessageType.PING }).header.payload as PingPayload

        assertTrue(events.contains(PhoneLocalSessionEvent.PongReceived(seq = ping.seq, receivedAt = 1_717_177_101L)))
        logs.assertLog(Log.VERBOSE, "local-session", "sending PING seq=1")
        logs.assertLog(Log.VERBOSE, "local-session", "armed PONG timeout for seq=1")
        logs.assertLog(Log.VERBOSE, "local-session", "dispatching PONG frame")
        logs.assertLog(Log.VERBOSE, "local-session", "received PONG seq=1")
        logs.assertNoSensitiveData()
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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
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
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        assertTrue(events.none { it == PhoneLocalSessionEvent.PongReceived(seq = 999L, receivedAt = 1_717_177_151L) })
        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.SessionFailed(
                    code = "BLUETOOTH_PONG_TIMEOUT",
                    message = "pong not received in time",
                ),
            ),
        )
        logs.assertLog(Log.VERBOSE, "local-session", "sending PING seq=1")
        logs.assertLog(Log.WARN, "local-session", "received unmatched PONG seq=999 nonce=wrong")
        logs.assertLog(Log.WARN, "local-session", "timed out waiting for PONG seq=1")
        logs.assertLog(Log.ERROR, "local-session", "local session failed code=BLUETOOTH_PONG_TIMEOUT")
        logs.assertLog(Log.INFO, "local-session", "clearing session state: failure: BLUETOOTH_PONG_TIMEOUT")
        logs.assertNoSensitiveData()
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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
            runCurrent()
            transport.updateState(PhoneTransportState.CONNECTED)
            runCurrent()
            clock.advanceBy(5_001L)
            advanceTimeBy(5_001L)
            runCurrent()
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        assertTrue(
            events.contains(
                PhoneLocalSessionEvent.SessionFailed(
                    code = "BLUETOOTH_HELLO_TIMEOUT",
                    message = "hello ack not received in time",
                ),
            ),
        )
        logs.assertLog(Log.INFO, "local-session", "armed HELLO_ACK timeout")
        logs.assertLog(Log.WARN, "local-session", "timed out waiting for HELLO_ACK")
        logs.assertLog(Log.ERROR, "local-session", "local session failed code=BLUETOOTH_HELLO_TIMEOUT")
        logs.assertLog(Log.INFO, "local-session", "clearing session state: failure: BLUETOOTH_HELLO_TIMEOUT")
        logs.assertNoSensitiveData()
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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
            runCurrent()
            transport.updateState(PhoneTransportState.CONNECTED)
            runCurrent()
            transport.emitBytes(byteArrayOf(0x01, 0x02, 0x03))
            runCurrent()
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        val failure = events.filterIsInstance<PhoneLocalSessionEvent.SessionFailed>().single()

        assertEquals("BLUETOOTH_PROTOCOL_ERROR", failure.code)
        assertTrue(failure.message.contains("failed to decode local frame"))
        assertTrue(failure.message.contains("Frame is shorter than fixed header"))
        logs.assertLog(Log.ERROR, "local-session", "failed to decode local frame from glasses")
        logs.assertLog(Log.ERROR, "local-session", "local session failed code=BLUETOOTH_PROTOCOL_ERROR")
        logs.assertNoSensitiveData()
        assertTrue(logs.any { it.throwable?.message?.contains("Frame is shorter than fixed header") == true })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatches command frames to session events and traces decision`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            helloConfig = helloConfig,
            codec = codec,
            clock = FakeClock(1_717_172_390L),
            sessionScope = backgroundScope,
        )
        val events = mutableListOf<PhoneLocalSessionEvent>()

        backgroundScope.launch {
            session.events.collect { events += it }
        }

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
            runCurrent()
            transport.updateState(PhoneTransportState.CONNECTED)
            runCurrent()
            transport.emitBytes(
                codec.encode(
                    LocalFrameHeader(
                        type = LocalMessageType.HELLO_ACK,
                        timestamp = 1_717_172_391L,
                        payload = HelloAckPayload(
                            accepted = true,
                            role = LinkRole.GLASSES,
                        ),
                    ),
                ),
            )
            runCurrent()
            transport.emitBytes(
                codec.encode(
                    LocalFrameHeader(
                        type = LocalMessageType.COMMAND_ACK,
                        requestId = "req-1",
                        timestamp = 1_717_172_392L,
                        payload = CommandAck(
                            action = CommandAction.DISPLAY_TEXT,
                            acceptedAt = 1_717_172_392L,
                            runtimeState = LocalRuntimeState.READY,
                        ),
                    ),
                ),
            )
            runCurrent()
            backgroundScope.launch { session.stop("test complete") }
            runCurrent()
        }

        assertTrue(events.any { it is PhoneLocalSessionEvent.FrameReceived && it.header.type == LocalMessageType.COMMAND_ACK })
        logs.assertLog(Log.VERBOSE, "local-session", "dispatching COMMAND_ACK frame to session events")
        logs.assertNoSensitiveData()
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

        val logs = captureTimberLogs {
            backgroundScope.launch { session.start(targetDeviceAddress = "00:11:22:33:44:55") }
            runCurrent()
            backgroundScope.launch { session.stop("user requested") }
            runCurrent()
        }

        assertEquals(listOf("user requested"), transport.stopReasons)
        logs.assertLog(Log.INFO, "local-session", "starting phone local link session")
        logs.assertLog(Log.INFO, "local-session", "stopping phone local link session: user requested")
        logs.assertLog(Log.INFO, "local-session", "terminating phone local link session: user requested")
        logs.assertLog(Log.INFO, "local-session", "clearing session state: terminate: user requested")
        logs.assertNoSensitiveData()
    }
}
