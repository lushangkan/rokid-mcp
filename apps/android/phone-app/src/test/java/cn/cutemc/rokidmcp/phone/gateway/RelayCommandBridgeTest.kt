package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoOutcome
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoResult
import cn.cutemc.rokidmcp.share.protocol.local.CommandError
import cn.cutemc.rokidmcp.share.protocol.local.CommandFailure
import cn.cutemc.rokidmcp.share.protocol.local.ChunkData
import cn.cutemc.rokidmcp.share.protocol.local.ChunkEnd
import cn.cutemc.rokidmcp.share.protocol.local.ChunkStart
import cn.cutemc.rokidmcp.share.protocol.local.CommandAck
import cn.cutemc.rokidmcp.share.protocol.local.DisplayingCommandStatus
import cn.cutemc.rokidmcp.share.protocol.local.LocalProtocolChecksums
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

        val logs = captureTimberLogs {
            bridge.handleRelaySessionEvent(RelaySessionEvent.CommandDispatched(displayDispatch()))
            runCurrent()
        }

        assertEquals("req_display_1", runtimeUpdates.first().first)
        assertEquals(LocalMessageType.COMMAND, forwardedHeaders.single().type)
        assertTrue(webSocket.sentTexts.any { it.contains("\"type\":\"command_ack\"") })
        assertTrue(webSocket.sentTexts.any { it.contains("\"status\":\"forwarding_to_glasses\"") })
        assertTrue(webSocket.sentTexts.any { it.contains("\"status\":\"waiting_glasses_ack\"") })
        logs.assertLog(Log.INFO, "relay-command", "received relay command dispatch requestId=req_display_1 action=DISPLAY_TEXT")
        logs.assertLog(Log.INFO, "relay-command", "forwarding local command requestId=req_display_1 action=DISPLAY_TEXT")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("hello glasses") })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

        val logs = captureTimberLogs {
            bridge.handleRelaySessionEvent(RelaySessionEvent.CommandDispatched(captureDispatch()))
            bridge.handleLocalSessionEvent(
                PhoneLocalSessionEvent.FrameReceived(
                    header = LocalFrameHeader(
                        type = LocalMessageType.COMMAND_ACK,
                        requestId = "req_capture_1",
                        timestamp = 1_717_172_601L,
                        payload = CommandAck(
                            action = CommandAction.CAPTURE_PHOTO,
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
                        payload = ChunkData(
                            index = 0,
                            offset = 0L,
                            size = imageBytes.size,
                            chunkChecksum = LocalProtocolChecksums.crc32(imageBytes),
                        ),
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
        }

        val resultIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"type\":\"command_result\"") }
        val uploadedIndex = webSocket.sentTexts.indexOfFirst { it.contains("\"status\":\"image_uploaded\"") }
        assertEquals(1, uploadCalls)
        assertTrue(uploadedIndex >= 0)
        assertTrue(resultIndex > uploadedIndex)
        assertEquals(null, runtimeUpdates.last().first)
        logs.assertLog(Log.INFO, "relay-command", "received relay command dispatch requestId=req_capture_1 action=CAPTURE_PHOTO")
        logs.assertLog(Log.INFO, "relay-command", "forwarding local command requestId=req_capture_1 action=CAPTURE_PHOTO")
        logs.assertLog(Log.INFO, "relay-command", "received glasses command ack requestId=req_capture_1 action=CAPTURE_PHOTO")
        logs.assertLog(Log.INFO, "relay-command", "received glasses command result requestId=req_capture_1 action=CAPTURE_PHOTO")
        logs.assertLog(Log.INFO, "relay-command", "received image chunk transfer start requestId=req_capture_1 transferId=trf_test_1")
        logs.assertLog(Log.VERBOSE, "relay-command", "received image chunk data requestId=req_capture_1 transferId=trf_test_1 chunkIndex=0")
        logs.assertLog(Log.VERBOSE, "image-assembler", "received image chunk requestId=req_capture_1 transferId=trf_test_1 chunkIndex=0")
        logs.assertLog(Log.INFO, "relay-command", "received image chunk transfer end requestId=req_capture_1 transferId=trf_test_1 totalChunks=1")
        logs.assertLog(Log.INFO, "image-assembler", "assembled image successfully requestId=req_capture_1 transferId=trf_test_1")
        logs.assertLog(Log.INFO, "relay-command", "triggering relay image upload requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1")
        logs.assertLog(Log.INFO, "relay-command", "completed relay image upload requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("upl_test_1") })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `chunk data without a binary body fails the active capture command`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val client = relayClient(webSocket)
        val runtimeUpdates = mutableListOf<Triple<String?, String?, String?>>()
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = FakeClock(1_717_172_700L),
            relaySessionClient = client,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = FakeClock(1_717_172_700L),
                sender = LocalFrameSender { _, _ -> Unit },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(httpExecutor = RelayHttpExecutor { error("unused") }),
            runtimeUpdater = { requestId, code, message -> runtimeUpdates += Triple(requestId, code, message) },
        )

        val logs = captureTimberLogs {
            bridge.handleRelaySessionEvent(RelaySessionEvent.CommandDispatched(captureDispatch()))
            bridge.handleLocalSessionEvent(
                PhoneLocalSessionEvent.FrameReceived(
                    header = LocalFrameHeader(
                        type = LocalMessageType.CHUNK_DATA,
                        requestId = "req_capture_1",
                        transferId = "trf_test_1",
                        timestamp = 1_717_172_701L,
                        payload = ChunkData(
                            index = 0,
                            offset = 0L,
                            size = 4,
                            chunkChecksum = "deadbeef",
                        ),
                    ),
                    body = null,
                ),
            )
        }

        assertTrue(webSocket.sentTexts.any { it.contains("\"type\":\"command_error\"") })
        assertTrue(webSocket.sentTexts.any { it.contains("PROTOCOL_INVALID_PAYLOAD") })
        assertEquals(Triple(null, "PROTOCOL_INVALID_PAYLOAD", "chunk_data frame is missing its binary body"), runtimeUpdates.last())
        logs.assertLog(Log.ERROR, "relay-command", "image assembly failed for requestId=req_capture_1 frameType=CHUNK_DATA")
        logs.assertNoSensitiveData()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `local command status and error logs are emitted safely`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val client = relayClient(webSocket)
        val runtimeUpdates = mutableListOf<Triple<String?, String?, String?>>()
        val bridge = RelayCommandBridge(
            relayBaseUrl = "https://relay.example.com",
            deviceId = "phone-device",
            clock = FakeClock(1_717_172_800L),
            relaySessionClient = client,
            activeCommandRegistry = ActiveCommandRegistry(),
            localCommandForwarder = LocalCommandForwarder(
                clock = FakeClock(1_717_172_800L),
                sender = LocalFrameSender { _, _ -> Unit },
            ),
            incomingImageAssembler = IncomingImageAssembler(),
            relayImageUploader = RelayImageUploader(httpExecutor = RelayHttpExecutor { error("unused") }),
            runtimeUpdater = { requestId, code, message -> runtimeUpdates += Triple(requestId, code, message) },
        )

        val logs = captureTimberLogs {
            bridge.handleRelaySessionEvent(RelaySessionEvent.CommandDispatched(displayDispatch()))
            bridge.handleLocalSessionEvent(
                PhoneLocalSessionEvent.FrameReceived(
                    header = LocalFrameHeader(
                        type = LocalMessageType.COMMAND_STATUS,
                        requestId = "req_display_1",
                        timestamp = 1_717_172_801L,
                        payload = DisplayingCommandStatus(
                            statusAt = 1_717_172_801L,
                            detailCode = "DISPLAYING",
                            detailMessage = "showing text",
                        ),
                    ),
                    body = null,
                ),
            )
            bridge.handleLocalSessionEvent(
                PhoneLocalSessionEvent.FrameReceived(
                    header = LocalFrameHeader(
                        type = LocalMessageType.COMMAND_ERROR,
                        requestId = "req_display_1",
                        timestamp = 1_717_172_802L,
                        payload = CommandError(
                            action = CommandAction.DISPLAY_TEXT,
                            failedAt = 1_717_172_802L,
                            error = CommandFailure(
                                code = "DISPLAY_FAILED",
                                message = "text render failed",
                                retryable = false,
                            ),
                        ),
                    ),
                    body = null,
                ),
            )
            runCurrent()
        }

        assertTrue(webSocket.sentTexts.any { it.contains("\"type\":\"command_error\"") })
        assertEquals(Triple(null, "DISPLAY_FAILED", "text render failed"), runtimeUpdates.last())
        logs.assertLog(Log.INFO, "relay-command", "received glasses command status requestId=req_display_1 action=DISPLAY_TEXT status=displaying")
        logs.assertLog(Log.WARN, "relay-command", "received glasses command error requestId=req_display_1 action=DISPLAY_TEXT code=DISPLAY_FAILED retryable=false")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("showing text") || it.message.contains("text render failed") })
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
