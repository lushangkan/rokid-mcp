package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySessionClientTest {
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
                relayBaseUrl = "https://relay.example.com/base",
                appVersion = "9.9.9",
            ),
            supportedActions = listOf(CommandAction.DISPLAY_TEXT),
        )

        client.onConnected()

        val hello = webSocket.sentTexts.single()
        assertTrue(hello.contains("\"type\":\"hello\""))
        assertTrue(hello.contains("\"deviceId\":\"device-from-config\""))
        assertTrue(hello.contains("\"authToken\":\"auth-from-config\""))
        assertTrue(hello.contains("\"appVersion\":\"9.9.9\""))
        assertTrue(hello.contains("\"uplinkState\":\"CONNECTING\""))
        assertTrue(hello.contains("\"capabilities\":[\"display_text\"]"))
        assertFalse(hello.contains("capture_photo"))
    }

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

        val heartbeat = webSocket.sentTexts.last { it.contains("\"type\":\"heartbeat\"") }
        assertTrue(heartbeat.contains("\"activeCommandRequestId\":null"))
    }

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

        val stateUpdate = webSocket.sentTexts.last { it.contains("\"type\":\"phone_state_update\"") }
        assertTrue(stateUpdate.contains("\"activeCommandRequestId\":null"))
    }

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

        assertFalse(client.canSendStateUpdate())
        assertTrue(events.any { it is RelaySessionEvent.Failed && it.message.contains("missing sessionId") })
        assertTrue(events.any { it is RelaySessionEvent.UplinkStateChanged && it.state == PhoneUplinkState.OFFLINE })
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
}
