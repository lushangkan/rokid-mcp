package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.TerminalErrorCode
import cn.cutemc.rokidmcp.share.protocol.relay.CommandAckPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandErrorPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandResultPayload
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandResult
import cn.cutemc.rokidmcp.share.protocol.relay.ExecutingStatus
import cn.cutemc.rokidmcp.share.protocol.relay.TerminalError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySessionClientTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sends heartbeat after hello ack using runtime snapshot`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        val config = PhoneGatewayConfig(
            deviceId = "abc12345",
            authToken = "token",
            relayBaseUrl = "http://10.0.2.2:3000",
            appVersion = "1.0",
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = config,
            controllerScope = backgroundScope,
        )

        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                uplinkState = PhoneUplinkState.ONLINE,
                runtimeState = PhoneRuntimeState.READY,
            ),
        )

        client.onConnected()
        client.onTextMessage(
            """
            {
              "version":"1.0",
              "type":"hello_ack",
              "deviceId":"abc12345",
              "timestamp":1717172001,
              "payload":{
                "sessionId":"ses_abcdef",
                "serverTime":1717172001,
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

        client.sendHeartbeat(runtimeStore.snapshot.value)
        client.onClosed(1000, "test complete")

        assertTrue(webSocket.sentTexts.any { it.contains("\"type\":\"heartbeat\"") })
    }

    @Test
    fun `on connected sends hello using phone gateway config fields`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                uplinkState = PhoneUplinkState.CONNECTING,
            ),
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "device-from-config",
                authToken = "auth-from-config",
                relayBaseUrl = "https://relay-user:relay-pass@relay.example.com/base?trace=hidden",
                appVersion = "9.9.9",
            ),
            supportedActions = listOf(CommandAction.DISPLAY_TEXT),
        )

        val logs = captureTimberLogsSuspend {
            client.onConnected()
        }

        val hello = webSocket.sentTexts.single()
        assertTrue(hello.contains("\"type\":\"hello\""))
        assertTrue(hello.contains("\"deviceId\":\"device-from-config\""))
        assertTrue(hello.contains("\"authToken\":\"auth-from-config\""))
        assertTrue(hello.contains("\"appVersion\":\"9.9.9\""))
        assertFalse(hello.contains("\"uplinkState\""))
        assertTrue(hello.contains("\"capabilities\":[\"display_text\"]"))
        assertFalse(hello.contains("capture_photo"))
        logs.assertLog(Log.INFO, "relay-session", "sending relay HELLO")
        logs.assertLog(Log.INFO, "relay-session", "wss://relay.example.com/base/ws/device")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("relay-user") || it.message.contains("trace=hidden") || it.message.contains("auth-from-config") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect logs safe websocket lifecycle traces`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        var callbacks: RelayWebSocketCallbacks? = null
        val client = RelaySessionClient(
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "https://relay-user:relay-pass@relay.example.com/base?trace=hidden",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
            webSocketFactory = RelayWebSocketFactory { _, nextCallbacks ->
                callbacks = nextCallbacks
                FakeRelayWebSocket()
            },
        )

        val logs = captureTimberLogsSuspend {
            client.connect()
            callbacks!!.onOpen()
            runCurrent()
            callbacks!!.onClosed(1000, "normal closure")
            runCurrent()
        }

        logs.assertLog(Log.INFO, "relay-session", "connecting relay websocket to wss://relay.example.com/base/ws/device")
        logs.assertLog(Log.INFO, "relay-session", "relay websocket opened")
        logs.assertLog(Log.WARN, "relay-session", "relay websocket closed code=1000 reason=normal closure")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("relay-user") || it.message.contains("trace=hidden") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect logs relay websocket failures while connection is active`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        var callbacks: RelayWebSocketCallbacks? = null
        val client = RelaySessionClient(
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "https://relay-user:relay-pass@relay.example.com/base?trace=hidden",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
            webSocketFactory = RelayWebSocketFactory { _, nextCallbacks ->
                callbacks = nextCallbacks
                FakeRelayWebSocket()
            },
        )

        val logs = captureTimberLogsSuspend {
            client.connect()
            callbacks!!.onFailure(IllegalStateException("boom"))
            runCurrent()
        }

        logs.assertLog(Log.INFO, "relay-session", "connecting relay websocket to wss://relay.example.com/base/ws/device")
        logs.assertLog(Log.ERROR, "relay-session", "relay websocket failure url=wss://relay.example.com/base/ws/device")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("relay-user") || it.message.contains("trace=hidden") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `duplicate websocket terminal callbacks are ignored after the first close`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        var callbacks: RelayWebSocketCallbacks? = null
        val client = RelaySessionClient(
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
            webSocketFactory = RelayWebSocketFactory { _, nextCallbacks ->
                callbacks = nextCallbacks
                FakeRelayWebSocket()
            },
        )

        val logs = captureTimberLogsSuspend {
            client.connect()
            callbacks!!.onClosed(1003, "")
            callbacks!!.onClosed(1003, "")
            callbacks!!.onFailure(IllegalStateException("boom"))
            runCurrent()
        }

        assertEquals(
            1,
            logs.count { entry ->
                entry.tag == "relay-session" &&
                    entry.message == "relay websocket closed code=1003 reason=(empty)"
            },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `manual disconnect ignores late websocket close callbacks`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        var callbacks: RelayWebSocketCallbacks? = null
        val client = RelaySessionClient(
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
            webSocketFactory = RelayWebSocketFactory { _, nextCallbacks ->
                callbacks = nextCallbacks
                FakeRelayWebSocket()
            },
        )

        val logs = captureTimberLogsSuspend {
            client.connect()
            client.disconnect("manual")
            callbacks!!.onClosed(1003, "")
            callbacks!!.onFailure(IllegalStateException("boom"))
            runCurrent()
        }

        assertEquals(
            0,
            logs.count { entry ->
                entry.tag == "relay-session" &&
                    entry.message == "relay websocket closed code=1003 reason=(empty)"
            },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `incoming relay command logs avoid raw websocket payloads and command text`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val client = RelaySessionClient(
            webSocket = FakeRelayWebSocket(),
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_050L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "authToken secret",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )

        val logs = captureTimberLogsSuspend {
            client.onTextMessage(
                """
                {
                  "version":"1.0",
                  "type":"command",
                  "deviceId":"abc12345",
                  "sessionId":"ses_sensitive",
                  "requestId":"req_display_sensitive",
                  "timestamp":1717172051,
                  "payload":{
                    "action":"display_text",
                    "params":{
                      "text":"Bearer Authorization authToken displayed text=do-not-log",
                      "durationMs":3000
                    }
                  }
                }
                """.trimIndent(),
            )
            runCurrent()
        }

        logs.assertLog(Log.INFO, "relay-session", "received relay command requestId=req_display_sensitive action=DISPLAY_TEXT sessionId=ses_sensitive")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("displayed text=do-not-log") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `heartbeat includes nullable active command field when absent`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                uplinkState = PhoneUplinkState.ONLINE,
                runtimeState = PhoneRuntimeState.READY,
                activeCommandRequestId = null,
            ),
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "http://10.0.2.2:3000",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )

        client.onConnected()
        client.onTextMessage(
            """
            {
              "version":"1.0",
              "type":"hello_ack",
              "deviceId":"abc12345",
              "timestamp":1717172001,
              "payload":{
                "sessionId":"ses_abcdef",
                "serverTime":1717172001,
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

        client.sendHeartbeat(runtimeStore.snapshot.value)
        client.onClosed(1000, "test complete")

        val heartbeat = webSocket.sentTexts.last { it.contains("\"type\":\"heartbeat\"") }
        assertTrue(heartbeat.contains("\"activeCommandRequestId\":null"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `phone state update includes null active command id when absent`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                uplinkState = PhoneUplinkState.ONLINE,
                runtimeState = PhoneRuntimeState.CONNECTING,
                activeCommandRequestId = null,
            ),
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "http://10.0.2.2:3000",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )

        client.onConnected()
        client.onTextMessage(
            """
            {
              "version":"1.0",
              "type":"hello_ack",
              "deviceId":"abc12345",
              "timestamp":1717172001,
              "payload":{
                "sessionId":"ses_abcdef",
                "serverTime":1717172001,
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

        client.sendPhoneStateUpdate(runtimeStore.snapshot.value)
        client.onClosed(1000, "test complete")

        val stateUpdate = webSocket.sentTexts.last { it.contains("\"type\":\"phone_state_update\"") }
        assertTrue(stateUpdate.contains("\"activeCommandRequestId\":null"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `phone state update includes active command id when present`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                uplinkState = PhoneUplinkState.ONLINE,
                runtimeState = PhoneRuntimeState.BUSY,
                activeCommandRequestId = "cmd_123",
            ),
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "http://10.0.2.2:3000",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )

        client.onConnected()
        client.onTextMessage(
            """
            {
              "version":"1.0",
              "type":"hello_ack",
              "deviceId":"abc12345",
              "timestamp":1717172001,
              "payload":{
                "sessionId":"ses_abcdef",
                "serverTime":1717172001,
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

        client.sendPhoneStateUpdate(runtimeStore.snapshot.value)
        client.onClosed(1000, "test complete")

        val stateUpdate = webSocket.sentTexts.last { it.contains("\"type\":\"phone_state_update\"") }
        assertTrue(stateUpdate.contains("\"activeCommandRequestId\":\"cmd_123\""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack without session id emits failure and stays offline`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "http://10.0.2.2:3000",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )
        val events = mutableListOf<RelaySessionEvent>()
        backgroundScope.launch {
            client.events.collect { events += it }
        }
        runCurrent()

        val logs = captureTimberLogsSuspend {
            client.onConnected()
            client.onTextMessage(
                """
                {
                  "version":"1.0",
                  "type":"hello_ack",
                  "deviceId":"abc12345",
                  "timestamp":1717172001,
                  "payload":{
                    "serverTime":1717172001,
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
        }

        assertFalse(client.canSendStateUpdate())
        assertTrue(events.any { it is RelaySessionEvent.Failed && it.message.contains("missing sessionId") })
        assertTrue(events.any { it is RelaySessionEvent.UplinkStateChanged && it.state == PhoneUplinkState.OFFLINE })
        logs.assertLog(Log.ERROR, "relay-session", "relay HELLO_ACK failed: missing sessionId")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hello ack starts heartbeat loop and logs heartbeat trace`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                runtimeState = PhoneRuntimeState.READY,
            ),
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com/base",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )

        val logs = captureTimberLogsSuspend {
            client.onConnected()
            client.onTextMessage(validHelloAckJson())
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            client.onClosed(1000, "test complete")
        }

        logs.assertLog(Log.INFO, "relay-session", "relay HELLO_ACK accepted sessionId=ses_abcdef heartbeatIntervalMs=5000")
        logs.assertLog(Log.INFO, "relay-session", "starting relay heartbeat loop sessionId=ses_abcdef intervalMs=5000")
        logs.assertLog(Log.VERBOSE, "relay-session", "sent relay heartbeat sessionId=ses_abcdef seq=0")
        logs.assertNoSensitiveData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `command handling and outbound session messages are traced`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = PhoneGatewayConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com/base",
                appVersion = "1.0",
            ),
            controllerScope = backgroundScope,
        )

        runtimeStore.replace(
            runtimeStore.snapshot.value.copy(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.BUSY,
                activeCommandRequestId = "req_cmd_1",
            ),
        )

        val logs = captureTimberLogsSuspend {
            client.onConnected()
            client.onTextMessage(validHelloAckJson())
            client.onTextMessage(validCommandJson())
            client.onTextMessage(validCommandCancelJson())
            client.onTextMessage("""{"version":"1.0","type":"mystery","deviceId":"abc12345","timestamp":1717172001,"payload":{}}""")
            client.sendPhoneStateUpdate(runtimeStore.snapshot.value)
            client.sendCommandAck(
                requestId = "req_cmd_1",
                payload = CommandAckPayload(
                    action = CommandAction.DISPLAY_TEXT,
                    acknowledgedAt = 1_717_172_100L,
                    runtimeState = PhoneRuntimeState.BUSY,
                ),
            )
            client.sendCommandStatus(
                requestId = "req_cmd_1",
                payload = ExecutingStatus(
                    action = CommandAction.DISPLAY_TEXT,
                    statusAt = 1_717_172_101L,
                ),
            )
            client.sendCommandResult(
                requestId = "req_cmd_1",
                payload = CommandResultPayload(
                    completedAt = 1_717_172_102L,
                    result = DisplayTextCommandResult(durationMs = 3_000L),
                ),
            )
            client.sendCommandError(
                requestId = "req_cmd_1",
                payload = CommandErrorPayload(
                    action = CommandAction.DISPLAY_TEXT,
                    failedAt = 1_717_172_103L,
                    error = TerminalError(
                        code = TerminalErrorCode.TIMEOUT,
                        message = "timed out",
                        retryable = true,
                    ),
                ),
            )
            client.onClosed(1000, "test complete")
        }

        logs.assertLog(Log.INFO, "relay-session", "received relay command requestId=req_cmd_1 action=DISPLAY_TEXT sessionId=ses_abcdef")
        logs.assertLog(Log.INFO, "relay-session", "received relay command_cancel requestId=req_cmd_1 action=DISPLAY_TEXT sessionId=ses_abcdef")
        logs.assertLog(Log.WARN, "relay-session", "received relay message with unknown type=mystery")
        logs.assertLog(Log.INFO, "relay-session", "sending relay phone_state_update sessionId=ses_abcdef setupState=INITIALIZED runtimeState=BUSY activeCommandRequestId=req_cmd_1")
        logs.assertLog(Log.INFO, "relay-session", "sending relay command_ack requestId=req_cmd_1 action=DISPLAY_TEXT sessionId=ses_abcdef")
        logs.assertLog(Log.INFO, "relay-session", "sending relay command_status requestId=req_cmd_1 action=DISPLAY_TEXT status=EXECUTING sessionId=ses_abcdef")
        logs.assertLog(Log.INFO, "relay-session", "sending relay command_result requestId=req_cmd_1 action=DISPLAY_TEXT sessionId=ses_abcdef")
        logs.assertLog(Log.INFO, "relay-session", "sending relay command_error requestId=req_cmd_1 action=DISPLAY_TEXT errorCode=TIMEOUT sessionId=ses_abcdef")
        logs.assertNoSensitiveData()
    }

    @Test
    fun `report if needed ignores lastUpdatedAt only changes`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = "token",
                    relayBaseUrl = "http://10.0.2.2:3000",
                    appVersion = "1.0",
                )
            },
        )

        val first = runtimeStore.snapshot.value.copy(lastUpdatedAt = 1)
        val second = runtimeStore.snapshot.value.copy(lastUpdatedAt = 2)

        assertFalse(controller.shouldReportSnapshotForTest(first, second))
    }

    @Test
    fun `report if needed ignores pong freshness-only changes`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = "token",
                    relayBaseUrl = "http://10.0.2.2:3000",
                    appVersion = "1.0",
                )
            },
        )

        val first = runtimeStore.snapshot.value.copy(lastSeenAt = 10L)
        val second = runtimeStore.snapshot.value.copy(lastSeenAt = 20L)

        assertFalse(controller.shouldReportSnapshotForTest(first, second))
    }

    @Test
    fun `report if needed ignores uplink-only changes`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = "token",
                    relayBaseUrl = "http://10.0.2.2:3000",
                    appVersion = "1.0",
                )
            },
        )

        val first = runtimeStore.snapshot.value.copy(uplinkState = PhoneUplinkState.CONNECTING)
        val second = runtimeStore.snapshot.value.copy(uplinkState = PhoneUplinkState.ONLINE)

        assertFalse(controller.shouldReportSnapshotForTest(first, second))
    }

    private fun validHelloAckJson(): String =
        """
        {
          "version":"1.0",
          "type":"hello_ack",
          "deviceId":"abc12345",
          "timestamp":1717172001,
          "payload":{
            "sessionId":"ses_abcdef",
            "serverTime":1717172001,
            "heartbeatIntervalMs":5000,
            "heartbeatTimeoutMs":15000,
            "limits":{
              "maxPendingCommands":1,
              "maxImageUploadSizeBytes":10485760,
              "acceptedImageContentTypes":["image/jpeg"]
            }
          }
        }
        """.trimIndent()

    private fun validCommandJson(): String =
        """
        {
          "version":"1.0",
          "type":"command",
          "deviceId":"abc12345",
          "requestId":"req_cmd_1",
          "sessionId":"ses_abcdef",
          "timestamp":1717172100,
          "payload":{
            "action":"display_text",
            "timeoutMs":30000,
            "params":{
              "text":"hello world",
              "durationMs":3000
            }
          }
        }
        """.trimIndent()

    private fun validCommandCancelJson(): String =
        """
        {
          "version":"1.0",
          "type":"command_cancel",
          "deviceId":"abc12345",
          "requestId":"req_cmd_1",
          "sessionId":"ses_abcdef",
          "timestamp":1717172200,
          "payload":{
            "action":"display_text",
            "cancelledAt":1717172200,
            "reasonCode":"TIMEOUT",
            "reasonMessage":"stop"
          }
        }
        """.trimIndent()

    private suspend fun TestScope.captureTimberLogsSuspend(
        block: suspend TestScope.() -> Unit,
    ) = captureTimberLogs {
        kotlinx.coroutines.runBlocking {
            this@captureTimberLogsSuspend.block()
        }
    }
}
