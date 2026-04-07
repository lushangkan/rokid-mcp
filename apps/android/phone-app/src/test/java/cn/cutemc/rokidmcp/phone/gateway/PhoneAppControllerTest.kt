package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.phone.logging.PhoneLogLevel
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogStore
import cn.cutemc.rokidmcp.phone.logging.PhoneUiLogTree
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import timber.log.Timber

class PhoneAppControllerTest {
    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `runtime store starts disconnected and offline`() = runTest {
        val store = PhoneRuntimeStore()
        val snapshot = store.snapshot.value

        assertEquals(PhoneSetupState.UNINITIALIZED, snapshot.setupState)
        assertEquals(PhoneRuntimeState.DISCONNECTED, snapshot.runtimeState)
        assertEquals(PhoneUplinkState.OFFLINE, snapshot.uplinkState)
    }

    @Test
    fun `start without required config records startup error and does not run`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val controllerLogStore = PhoneLogStore(logStore)
        Timber.plant(PhoneUiLogTree(logStore))
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = controllerLogStore,
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = null,
                    relayBaseUrl = null,
                    appVersion = "1.0",
                )
            },
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")

        assertEquals(GatewayRunState.ERROR, controller.runState.value)
        assertEquals("PHONE_CONFIG_INCOMPLETE", runtimeStore.snapshot.value.lastErrorCode)
        assertTrue(logStore.entries.value.any { it.message.contains("missing relay config") })
        assertEquals(1_717_171_800L, logStore.entries.value.single().timestampMs)
        assertEquals(PhoneLogLevel.ERROR, logStore.entries.value.single().level)
        assertEquals("controller", logStore.entries.value.single().tag)
    }

    @Test
    fun `start uses preloaded config when provided`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                fail("loadConfig should not be called when preloaded config is provided")
                PhoneGatewayConfig(
                    deviceId = "phone-ab12cd34",
                    authToken = null,
                    relayBaseUrl = null,
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
        )

        controller.start(
            targetDeviceAddress = "00:11:22:33:44:55",
            preloadedConfig = PhoneGatewayConfig(
                deviceId = "phone-ab12cd34",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
        )

        assertEquals(GatewayRunState.STARTING, controller.runState.value)
    }

    @Test
    fun `start fails gracefully when default transport placeholder is unavailable`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = {
                throw UnsupportedOperationException("RFCOMM transport not implemented yet")
            },
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")

        assertEquals(GatewayRunState.ERROR, controller.runState.value)
        assertEquals(PhoneRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("BLUETOOTH_TRANSPORT_UNAVAILABLE", runtimeStore.snapshot.value.lastErrorCode)
        assertEquals("RFCOMM transport not implemented yet", runtimeStore.snapshot.value.lastErrorMessage)
    }

    @Test
    fun `start fails gracefully when placeholder start throws not implemented error`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = {
                object : RfcommClientTransport {
                    override val state = kotlinx.coroutines.flow.MutableStateFlow(PhoneTransportState.IDLE)
                    override val events = kotlinx.coroutines.flow.MutableSharedFlow<PhoneTransportEvent>()

                    override suspend fun start(targetDeviceAddress: String) {
                        throw NotImplementedError("RFCOMM transport not implemented yet")
                    }

                    override suspend fun send(bytes: ByteArray) {
                        error("unused")
                    }

                    override suspend fun stop(reason: String) {
                        error("unused")
                    }
                }
            },
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")

        assertEquals(GatewayRunState.ERROR, controller.runState.value)
        assertEquals(PhoneRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("BLUETOOTH_TRANSPORT_UNAVAILABLE", runtimeStore.snapshot.value.lastErrorCode)
        assertEquals("RFCOMM transport not implemented yet", runtimeStore.snapshot.value.lastErrorMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `repeated start stops previous local session before replacing it`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transports = mutableListOf<FakeRfcommClientTransport>()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = {
                FakeRfcommClientTransport().also(transports::add)
            },
            clock = FakeClock(1_717_171_900L),
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        controller.start(targetDeviceAddress = "AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertEquals(2, transports.size)
        assertEquals(listOf("00:11:22:33:44:55"), transports.first().startAddresses)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), transports.last().startAddresses)
        assertTrue(transports.first().stopReasons.contains("restarting controller session"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transport failure moves run state to error`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.emit(PhoneTransportEvent.Failure(IllegalStateException("rfcomm broken")))
        runCurrent()

        assertEquals(GatewayRunState.ERROR, controller.runState.value)
        assertEquals(PhoneRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("BLUETOOTH_TRANSPORT_ERROR", runtimeStore.snapshot.value.lastErrorCode)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connection closed moves run state to stopped so ui can retry`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.emit(PhoneTransportEvent.ConnectionClosed("closed"))
        runCurrent()

        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
        assertEquals(PhoneRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
        assertNull(runtimeStore.snapshot.value.lastErrorCode)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connection closed tears down session so timeout does not regress state later`() = runTest {
        val codec = DefaultLocalFrameCodec()
        val clock = FakeClock(1_717_171_900L)
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            createLocalSession = { createdTransport, helloConfig, createdClock, scope ->
                PhoneLocalLinkSession(
                    transport = createdTransport,
                    helloConfig = helloConfig,
                    codec = codec,
                    clock = createdClock,
                    sessionScope = scope,
                )
            },
            clock = clock,
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
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
                    ),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()
        transport.emit(PhoneTransportEvent.ConnectionClosed("closed"))
        runCurrent()

        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
        assertEquals(PhoneRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)

        clock.advanceBy(10_001L)
        advanceTimeBy(10_001L)
        runCurrent()

        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
        assertEquals(PhoneRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
        assertNull(runtimeStore.snapshot.value.lastErrorCode)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `terminal failure stops local session exactly once`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val recordingSession = RecordingSession(transport)
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            createLocalSession = { _, _, _, _ -> recordingSession },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.emit(PhoneTransportEvent.Failure(IllegalStateException("rfcomm broken")))
        runCurrent()

        assertEquals(listOf("transport ended"), recordingSession.terminateReasons)
        assertEquals(emptyList<String>(), recordingSession.stopReasons)
        assertEquals(GatewayRunState.ERROR, controller.runState.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `terminal close stops local session exactly once`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val recordingSession = RecordingSession(transport)
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            createLocalSession = { _, _, _, _ -> recordingSession },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transport.emit(PhoneTransportEvent.ConnectionClosed("closed"))
        runCurrent()

        assertEquals(listOf("transport ended"), recordingSession.terminateReasons)
        assertEquals(emptyList<String>(), recordingSession.stopReasons)
        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restart from error cleans up old session before creating new transport`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transports = mutableListOf<FakeRfcommClientTransport>()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = {
                FakeRfcommClientTransport().also(transports::add)
            },
            clock = FakeClock(1_717_171_950L),
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transports.first().emit(PhoneTransportEvent.Failure(IllegalStateException("rfcomm broken")))
        runCurrent()

        assertEquals(GatewayRunState.ERROR, controller.runState.value)

        controller.start(targetDeviceAddress = "AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertEquals(2, transports.size)
        assertEquals(emptyList<String>(), transports.first().stopReasons)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), transports.last().startAddresses)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restart after connection closed creates new transport without extra stop on old transport`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transports = mutableListOf<FakeRfcommClientTransport>()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = {
                FakeRfcommClientTransport().also(transports::add)
            },
            clock = FakeClock(1_717_171_975L),
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        transports.first().emit(PhoneTransportEvent.ConnectionClosed("closed"))
        runCurrent()

        assertEquals(GatewayRunState.STOPPED, controller.runState.value)

        controller.start(targetDeviceAddress = "AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertEquals(2, transports.size)
        assertEquals(emptyList<String>(), transports.first().stopReasons)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), transports.last().startAddresses)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello rejected stops active transport exactly once`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val recordingSession = RecordingSession(transport)
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            createLocalSession = { _, _, _, _ -> recordingSession },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        controller.handleLocalSessionEvent(
            PhoneLocalSessionEvent.HelloRejected(
                code = "REJECTED",
                message = "not available",
            ),
        )
        runCurrent()

        assertEquals(emptyList<String>(), recordingSession.terminateReasons)
        assertEquals(listOf("session failed"), recordingSession.stopReasons)
        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
        assertEquals(PhoneRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session failed stops active transport exactly once`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val recordingSession = RecordingSession(transport)
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { transport },
            createLocalSession = { _, _, _, _ -> recordingSession },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        controller.handleLocalSessionEvent(
            PhoneLocalSessionEvent.SessionFailed(
                code = "BLUETOOTH_PONG_TIMEOUT",
                message = "pong not received in time",
            ),
        )
        runCurrent()

        assertEquals(emptyList<String>(), recordingSession.terminateReasons)
        assertEquals(listOf("session failed"), recordingSession.stopReasons)
        assertEquals(GatewayRunState.STOPPED, controller.runState.value)
        assertEquals(PhoneRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `controller wires transport and session events into runtime store`() = runTest {
        val codec = DefaultLocalFrameCodec()
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneUiLogStore(nowMs = { 1_717_171_800L })
        val transport = FakeRfcommClientTransport()
        val relaySessionClient = RelaySessionClient(
            webSocket = FakeRelayWebSocket(),
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_171_900L),
            config = PhoneGatewayConfig(
                deviceId = "phone-device",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.2.3",
            ),
            controllerScope = backgroundScope,
        )
        var capturedHelloConfig: PhoneHelloConfig? = null
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(logStore),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.2.3",
                )
            },
            createTransport = { transport },
            createLocalSession = { createdTransport, helloConfig, clock, scope ->
                capturedHelloConfig = helloConfig
                PhoneLocalLinkSession(
                    transport = createdTransport,
                    helloConfig = helloConfig,
                    codec = codec,
                    clock = clock,
                    sessionScope = scope,
                )
            },
            clock = FakeClock(1_717_171_900L),
            controllerScope = backgroundScope,
            supportedActions = listOf(CommandAction.DISPLAY_TEXT),
            createRelaySessionClient = { relaySessionClient },
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        controller.handleRelaySessionEvent(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.CONNECTING))
        runCurrent()

        assertEquals(listOf("00:11:22:33:44:55"), transport.startAddresses)
        assertEquals(
            PhoneHelloConfig(
                deviceId = "phone-device",
                appVersion = "1.2.3",
                supportedActions = listOf(CommandAction.DISPLAY_TEXT),
            ),
            capturedHelloConfig,
        )
        assertEquals(PhoneRuntimeState.CONNECTING, runtimeStore.snapshot.value.runtimeState)

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
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(GatewayRunState.RUNNING, controller.runState.value)
        assertEquals(PhoneRuntimeState.CONNECTING, runtimeStore.snapshot.value.runtimeState)
        assertEquals(PhoneUplinkState.CONNECTING, runtimeStore.snapshot.value.uplinkState)

        advanceTimeBy(5_001L)
        runCurrent()
        val ping = codec.decode(transport.sentBytes.last()).header.payload as cn.cutemc.rokidmcp.share.protocol.local.PingPayload

        transport.emitBytes(
            codec.encode(
                LocalFrameHeader(
                    type = LocalMessageType.PONG,
                    timestamp = 1_717_171_902L,
                    payload = cn.cutemc.rokidmcp.share.protocol.local.PongPayload(seq = ping.seq, nonce = ping.nonce),
                ),
            ),
        )
        runCurrent()

        assertEquals(PhoneRuntimeState.CONNECTING, runtimeStore.snapshot.value.runtimeState)
        assertEquals(1_717_171_900L, runtimeStore.snapshot.value.lastSeenAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `controller sends phone state update only for externally visible snapshot changes`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val webSocket = FakeRelayWebSocket()
        val relaySessionClient = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_171_900L),
            config = PhoneGatewayConfig(
                deviceId = "phone-device",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(PhoneUiLogStore(nowMs = { 1_717_171_800L })),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { FakeRfcommClientTransport() },
            createRelaySessionClient = { relaySessionClient },
            controllerScope = backgroundScope,
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")
        runCurrent()
        relaySessionClient.onTextMessage(
            """
            {
              "version":"1.0",
              "type":"hello_ack",
              "deviceId":"phone-device",
              "timestamp":1717171901,
              "payload":{
                "sessionId":"ses_reporting",
                "serverTime":1717171901,
                "heartbeatIntervalMs":5000,
                "heartbeatTimeoutMs":15000,
                "limits":{
                  "maxPendingCommands":1,
                  "maxImageUploadSizeBytes":10485760,
                  "acceptedImageContentTypes":["image/jpeg"]
                }
              }
            }
            """.trimIndent(),
        )
        runCurrent()

        controller.applyTransportState(PhoneTransportState.CONNECTED)
        runCurrent()

        val stateUpdatesAfterConnected = webSocket.sentTexts.filter { it.contains("\"type\":\"phone_state_update\"") }
        assertEquals(1, stateUpdatesAfterConnected.size)
        assertTrue(stateUpdatesAfterConnected.last().contains("\"runtimeState\":\"${PhoneRuntimeState.CONNECTING.name}\""))
        val baselineCount = stateUpdatesAfterConnected.size

        controller.handleLocalSessionEvent(PhoneLocalSessionEvent.PongReceived(seq = 1, receivedAt = 1_717_171_905L))
        runCurrent()
        assertEquals(baselineCount, webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") })

        runtimeStore.replace(runtimeStore.snapshot.value.copy(lastUpdatedAt = 123L))
        controller.reportSnapshotForTest(runtimeStore.snapshot.value)
        runCurrent()
        assertEquals(baselineCount, webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") })

        runtimeStore.replace(runtimeStore.snapshot.value.copy(uplinkState = PhoneUplinkState.ONLINE))
        controller.reportSnapshotForTest(runtimeStore.snapshot.value)
        runCurrent()
        assertEquals(baselineCount, webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") })

        runtimeStore.replace(runtimeStore.snapshot.value.copy(lastErrorCode = "ERR_SAMPLE"))
        controller.reportSnapshotForTest(runtimeStore.snapshot.value)
        runCurrent()
        assertEquals(baselineCount + 1, webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `local ready does not become externally ready until relay uplink is online`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(PhoneUiLogStore(nowMs = { 1_717_171_800L })),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { FakeRfcommClientTransport() },
            controllerScope = backgroundScope,
        )

        controller.handleRelaySessionEvent(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.CONNECTING))
        controller.applyTransportState(PhoneTransportState.CONNECTED)
        controller.handleLocalSessionEvent(PhoneLocalSessionEvent.SessionReady)
        runCurrent()

        assertEquals(PhoneRuntimeState.CONNECTING, runtimeStore.snapshot.value.runtimeState)

        controller.handleRelaySessionEvent(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.ONLINE))
        runCurrent()

        assertEquals(PhoneRuntimeState.READY, runtimeStore.snapshot.value.runtimeState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `relay uplink offline drops externally ready runtime to disconnected`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(PhoneUiLogStore(nowMs = { 1_717_171_800L })),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { FakeRfcommClientTransport() },
            controllerScope = backgroundScope,
        )

        controller.applyTransportState(PhoneTransportState.CONNECTED)
        controller.handleRelaySessionEvent(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.ONLINE))
        controller.handleLocalSessionEvent(PhoneLocalSessionEvent.SessionReady)
        runCurrent()
        assertEquals(PhoneRuntimeState.READY, runtimeStore.snapshot.value.runtimeState)

        controller.handleRelaySessionEvent(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.OFFLINE))
        runCurrent()

        assertEquals(PhoneRuntimeState.DISCONNECTED, runtimeStore.snapshot.value.runtimeState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `relay failure projects runtime to error`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(PhoneUiLogStore(nowMs = { 1_717_171_800L })),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { FakeRfcommClientTransport() },
            controllerScope = backgroundScope,
        )

        controller.applyTransportState(PhoneTransportState.CONNECTED)
        controller.handleRelaySessionEvent(RelaySessionEvent.UplinkStateChanged(PhoneUplinkState.ONLINE))
        controller.handleLocalSessionEvent(PhoneLocalSessionEvent.SessionReady)
        runCurrent()
        assertEquals(PhoneRuntimeState.READY, runtimeStore.snapshot.value.runtimeState)

        controller.handleRelaySessionEvent(RelaySessionEvent.Failed("boom"))
        runCurrent()

        assertEquals(PhoneUplinkState.ERROR, runtimeStore.snapshot.value.uplinkState)
        assertEquals(PhoneRuntimeState.ERROR, runtimeStore.snapshot.value.runtimeState)
        assertEquals("RELAY_SESSION_ERROR", runtimeStore.snapshot.value.lastErrorCode)
    }

    @Test
    fun `concurrent report requests only send one phone state update`() = runBlocking {
        val runtimeStore = PhoneRuntimeStore()
        val webSocket = BlockingRelayWebSocket()
        val controllerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + SupervisorJob())
        val relaySessionClient = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_171_900L),
            config = PhoneGatewayConfig(
                deviceId = "phone-device",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
            controllerScope = controllerScope,
        )
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(PhoneUiLogStore(nowMs = { 1_717_171_800L })),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "phone-device",
                    authToken = "token",
                    relayBaseUrl = "https://relay.example.com",
                    appVersion = "1.0",
                )
            },
            createTransport = { FakeRfcommClientTransport() },
            createRelaySessionClient = { relaySessionClient },
            controllerScope = controllerScope,
        )

        try {
            controller.start(targetDeviceAddress = "00:11:22:33:44:55")
            relaySessionClient.onTextMessage(
                """
                {
                  "version":"1.0",
                  "type":"hello_ack",
                  "deviceId":"phone-device",
                  "timestamp":1717171901,
                  "payload":{
                    "sessionId":"ses_reporting",
                    "serverTime":1717171901,
                    "heartbeatIntervalMs":5000,
                    "heartbeatTimeoutMs":15000,
                    "limits":{
                      "maxPendingCommands":1,
                      "maxImageUploadSizeBytes":10485760,
                      "acceptedImageContentTypes":["image/jpeg"]
                    }
                  }
                }
                """.trimIndent(),
            )

            controller.applyTransportState(PhoneTransportState.CONNECTED)
            withTimeout(2_000) {
                while (webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") } < 1) {
                    delay(10)
                }
            }
            val baselineCount = webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") }
            val next = runtimeStore.snapshot.value.copy(lastErrorCode = "ERR_CONCURRENT")
            runtimeStore.replace(next)
            webSocket.blockStateUpdates = true

            val first = controllerScope.launch { controller.reportSnapshotForTest(next) }
            assertTrue(webSocket.firstSendStarted.await(2, TimeUnit.SECONDS))

            val second = controllerScope.launch { controller.reportSnapshotForTest(next) }
            assertFalse(webSocket.secondSendStarted.await(200, TimeUnit.MILLISECONDS))

            webSocket.releaseFirstSend.countDown()
            first.join()
            second.join()

            assertEquals(
                baselineCount + 1,
                webSocket.sentTexts.count { it.contains("\"type\":\"phone_state_update\"") },
            )
        } finally {
            controllerScope.coroutineContext[Job]?.cancel()
        }
    }
}

private class RecordingSession(
    private val fakeTransport: FakeRfcommClientTransport,
) : PhoneLocalLinkSession(
    transport = fakeTransport,
    helloConfig = PhoneHelloConfig(
        deviceId = "phone-device",
        appVersion = "1.0",
        supportedActions = listOf(CommandAction.DISPLAY_TEXT),
    ),
    codec = DefaultLocalFrameCodec(),
    clock = FakeClock(1L),
    sessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
) {
    val stopReasons: MutableList<String> = mutableListOf()
    val terminateReasons: MutableList<String> = mutableListOf()

    override suspend fun stop(reason: String) {
        stopReasons += reason
    }

    override suspend fun terminate(reason: String) {
        terminateReasons += reason
    }
}

private class BlockingRelayWebSocket : RelayWebSocket {
    val sentTexts: MutableList<String> = mutableListOf()
    val closeCalls: MutableList<Pair<Int, String>> = mutableListOf()
    val firstSendStarted = CountDownLatch(1)
    val secondSendStarted = CountDownLatch(1)
    val releaseFirstSend = CountDownLatch(1)
    @Volatile var blockStateUpdates: Boolean = false

    @Synchronized
    override fun sendText(text: String) {
        val isStateUpdate = text.contains("\"type\":\"phone_state_update\"")
        if (isStateUpdate && blockStateUpdates && firstSendStarted.count > 0L) {
            firstSendStarted.countDown()
            releaseFirstSend.await(2, TimeUnit.SECONDS)
        } else if (isStateUpdate && blockStateUpdates) {
            secondSendStarted.countDown()
        }
        sentTexts += text
    }

    @Synchronized
    override fun close(code: Int, reason: String) {
        closeCalls += code to reason
    }
}
