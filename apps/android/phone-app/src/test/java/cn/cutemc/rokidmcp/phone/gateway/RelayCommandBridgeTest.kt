package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoOutcome
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchImage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandPayload
import java.security.MessageDigest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCommandBridgeTest {
    @Test
    fun `dispatch forwards local command and reports relay ack statuses`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val client = relayClient(webSocket)
        val forwardedHeaders = mutableListOf<LocalFrameHeader<*>>()
        val runtimeUpdates = mutableListOf<Triple<String?, String?, String?>>()
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = FakeClock(1_717_172_500L),
            relaySessionClient = client,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = FakeClock(1_717_172_500L),
                sender = LocalFrameSender { header, _ -> forwardedHeaders += header },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(httpExecutor = RelayHttpExecutor { error("unused") }),
            runtimeUpdater = { requestId, code, message -> runtimeUpdates += Triple(requestId, code, message) },
        )

        bridge.handleRelaySessionEvent(RelaySessionEvent.CommandDispatched(displayDispatch()))
        runCurrent()

        assertEquals("req_display_1", runtimeUpdates.first().first)
        assertEquals(LocalMessageType.COMMAND, forwardedHeaders.single().type)
        assertTrue(webSocket.sentTexts.any { it.contains("\"type\":\"command_ack\"") })
        assertTrue(webSocket.sentTexts.any { it.contains("\"status\":\"forwarding_to_glasses\"") })
        assertTrue(webSocket.sentTexts.any { it.contains("\"status\":\"waiting_glasses_ack\"") })
    }

    @Test
    fun `capture photo result waits for upload success before terminal result`() = runTest {
        val imageBytes = "jpeg-bytes".encodeToByteArray()
        val webSocket = FakeRelayWebSocket()
        val client = relayClient(webSocket)
        val runtimeUpdates = mutableListOf<Triple<String?, String?, String?>>()
        var uploadCalls = 0
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = FakeClock(1_717_172_600L),
            relaySessionClient = client,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = FakeClock(1_717_172_600L),
                sender = LocalFrameSender { _, _ -> Unit },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(
                httpExecutor = RelayHttpExecutor {
                    uploadCalls += 1
                    RelayHttpResponse(
                        code = 200,
                        body = """
                            {
                              "ok":true,
                              "image":{
                                "imageId":"img_test_1",
                                "transferId":"trf_test_1",
                                "status":"UPLOADED",
                                "mimeType":"image/jpeg",
                                "size":${imageBytes.size},
                                "sha256":"${imageBytes.sha256Hex()}",
                                "uploadedAt":1717172600
                              },
                              "timestamp":1717172601
                            }
                        """.trimIndent(),
                    )
                },
            ),
            runtimeUpdater = { requestId, code, message -> runtimeUpdates += Triple(requestId, code, message) },
        )

        bridge.handleRelaySessionEvent(RelaySessionEvent.CommandDispatched(captureDispatch()))
        bridge.handleLocalSessionEvent(
            PhoneLocalSessionEvent.FrameReceived(
                header = LocalFrameHeader(
                    type = LocalMessageType.COMMAND_ACK,
                    requestId = "req_capture_1",
                    timestamp = 1_717_172_601L,
                    payload = CommandAck(
                        action = cn.cutemc.rokidmcp.share.protocol.constants.CommandAction.CAPTURE_PHOTO,
                        acceptedAt = 1_717_172_601L,
                        runtimeState = cn.cutemc.rokidmcp.share.protocol.local.LocalRuntimeState.BUSY,
                    ),
                ),
                body = null,
            ),
        )
        bridge.handleLocalSessionEvent(
            PhoneLocalSessionEvent.FrameReceived(
                header = LocalFrameHeader(
                    type = LocalMessageType.COMMAND_RESULT,
                    requestId = "req_capture_1",
                    timestamp = 1_717_172_602L,
                    payload = CapturePhotoResult(
                        completedAt = 1_717_172_602L,
                        result = CapturePhotoOutcome(
                            size = imageBytes.size.toLong(),
                            width = 640,
                            height = 480,
                            sha256 = imageBytes.sha256Hex(),
                        ),
                    ),
                ),
                body = null,
            ),
        )

        assertEquals(0, uploadCalls)
        assertFalse(webSocket.sentTexts.any { it.contains("\"type\":\"command_result\"") })

        bridge.handleLocalSessionEvent(
            PhoneLocalSessionEvent.FrameReceived(
                header = LocalFrameHeader(
                    type = LocalMessageType.CHUNK_START,
                    requestId = "req_capture_1",
                    transferId = "trf_test_1",
                    timestamp = 1_717_172_603L,
                    payload = ChunkStart(
                        totalSize = imageBytes.size.toLong(),
                        width = 640,
                        height = 480,
                        sha256 = imageBytes.sha256Hex(),
                    ),
                ),
                body = null,
            ),
        )
        bridge.handleLocalSessionEvent(
            PhoneLocalSessionEvent.FrameReceived(
                header = LocalFrameHeader(
                    type = LocalMessageType.CHUNK_DATA,
                    requestId = "req_capture_1",
                    transferId = "trf_test_1",
                    timestamp = 1_717_172_604L,
                    payload = ChunkData(index = 0, offset = 0L, size = imageBytes.size, chunkChecksum = "ignored"),
                ),
                body = imageBytes,
            ),
        )
        bridge.handleLocalSessionEvent(
            PhoneLocalSessionEvent.FrameReceived(
                header = LocalFrameHeader(
                    type = LocalMessageType.CHUNK_END,
                    requestId = "req_capture_1",
                    transferId = "trf_test_1",
                    timestamp = 1_717_172_605L,
                    payload = ChunkEnd(totalChunks = 1, totalSize = imageBytes.size.toLong(), sha256 = imageBytes.sha256Hex()),
                ),
                body = null,
            ),
        )
        runCurrent()

        val resultIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"type\":\"command_result\"") }
        val uploadedIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"status\":\"image_uploaded\"") }
        assertEquals(1, uploadCalls)
        assertTrue(uploadedIndex >= 0)
        assertTrue(resultIndex > uploadedIndex)
        assertEquals(null, runtimeUpdates.last().first)
    }

    private suspend fun relayClient(webSocket: FakeRelayWebSocket): RelaySessionClient {
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = PhoneRuntimeStore(),
            clock = FakeClock(1_717_172_400L),
            config = PhoneGatewayConfig(
                deviceId = "phone-device",
                authToken = "token",
                relayBaseUrl = "https://relay.example.com",
                appVersion = "1.0",
            ),
        )
        client.onConnected()
        client.onTextMessage(
            """
                {
                  "version":"1.0",
                  "type":"hello_ack",
                  "deviceId":"phone-device",
                  "timestamp":1717172400,
                  "payload":{
                    "sessionId":"ses_bridge",
                    "serverTime":1717172400,
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
        return client
    }

    private fun displayDispatch() = CommandDispatchMessage(
        deviceId = "phone-device",
        requestId = "req_display_1",
        sessionId = "ses_bridge",
        timestamp = 1_717_172_500L,
        payload = DisplayTextCommandDispatchPayload(
            timeoutMs = 30_000L,
            params = DisplayTextCommandPayload(
                text = "hello glasses",
                durationMs = 2_500L,
            ),
        ),
    )

    private fun captureDispatch() = CommandDispatchMessage(
        deviceId = "phone-device",
        requestId = "req_capture_1",
        sessionId = "ses_bridge",
        timestamp = 1_717_172_600L,
        payload = CapturePhotoCommandDispatchPayload(
            timeoutMs = 90_000L,
            params = CapturePhotoCommandPayload(),
            image = CommandDispatchImage(
                imageId = "img_test_1",
                transferId = "trf_test_1",
                uploadToken = "upl_test_1",
                expiresAt = 1_717_272_600L,
                maxSizeBytes = 4_096L,
            ),
        ),
    )
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
