package cn.cutemc.rokidmcp.share.protocol.relay

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.CommandStatus
import cn.cutemc.rokidmcp.share.protocol.constants.ImageStatus
import cn.cutemc.rokidmcp.share.protocol.constants.RelayProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.SetupState
import cn.cutemc.rokidmcp.share.protocol.constants.TerminalErrorCode
import cn.cutemc.rokidmcp.share.protocol.constants.UplinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolSerializationTest {
    private val json = RelayProtocolJson.default

    @Test
    fun `submit command request decodes display text variant`() {
        val request = json.decodeFromString(SubmitCommandRequestSerializer, """
            {
              "deviceId": "abc12345",
              "action": "display_text",
              "payload": {
                "text": "hello",
                "durationMs": 3000
              }
            }
        """.trimIndent())

        assertTrue(request is SubmitDisplayTextCommandRequest)
        request as SubmitDisplayTextCommandRequest
        assertEquals(CommandAction.DISPLAY_TEXT, request.action)
        assertEquals("hello", request.payload.text)
        assertEquals(3000L, request.payload.durationMs)
    }

    @Test
    fun `submit command response decodes reserved image capture photo variant`() {
        val response = json.decodeFromString(SubmitCommandResponseSerializer, """
            {
              "ok": true,
              "requestId": "req_abcdef",
              "deviceId": "abc12345",
              "action": "capture_photo",
              "status": "CREATED",
              "createdAt": 1717172000,
              "statusUrl": "/api/v1/commands/req_abcdef",
              "image": {
                "imageId": "img_abcdef",
                "transferId": "trf_abcdef",
                "status": "RESERVED",
                "mimeType": "image/jpeg",
                "expiresAt": 1717172100
              }
            }
        """.trimIndent())

        assertTrue(response is SubmitCapturePhotoCommandResponse)
        response as SubmitCapturePhotoCommandResponse
        assertEquals(ImageStatus.RESERVED, response.image.status)
        assertEquals("trf_abcdef", response.image.transferId)
    }

    @Test
    fun `command status message decodes image uploaded progress variant`() {
        val message = json.decodeFromString(CommandStatusMessage.serializer(), """
            {
              "version": "1.0",
              "type": "command_status",
              "deviceId": "abc12345",
              "requestId": "req_abcdef",
              "sessionId": "ses_abcdef",
              "timestamp": 1717172000,
              "payload": {
                "action": "capture_photo",
                "status": "image_uploaded",
                "statusAt": 1717172001,
                "image": {
                  "imageId": "img_abcdef",
                  "transferId": "trf_abcdef",
                  "uploadStartedAt": 1717171999,
                  "uploadedAt": 1717172001,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
              }
            }
        """.trimIndent())

        assertTrue(message.payload is ImageUploadedStatus)
        val payload = message.payload as ImageUploadedStatus
        assertEquals(CommandAction.CAPTURE_PHOTO, payload.action)
        assertEquals("img_abcdef", payload.image.imageId)
    }

    @Test
    fun `command result serializer decodes capture photo result`() {
        val result = json.decodeFromString(CommandResultSerializer, """
            {
              "action": "capture_photo",
              "imageId": "img_abcdef",
              "transferId": "trf_abcdef",
              "mimeType": "image/jpeg",
              "size": 1024,
              "width": 1280,
              "height": 720,
              "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            }
        """.trimIndent())

        assertTrue(result is CapturePhotoCommandResult)
        result as CapturePhotoCommandResult
        assertEquals(1024L, result.size)
        assertEquals("trf_abcdef", result.transferId)
    }

    @Test
    fun `relay hello message keeps frozen protocol constants`() {
        val message = RelayHelloMessage(
            deviceId = "abc12345",
            timestamp = 1717172000,
            payload = RelayHelloPayload(
                authToken = "token",
                appVersion = "1.0.0",
                phoneInfo = RelayPhoneInfo(model = "Pixel"),
                setupState = SetupState.INITIALIZED,
                runtimeState = RuntimeState.READY,
                uplinkState = UplinkState.CONNECTING,
                capabilities = listOf(CommandAction.DISPLAY_TEXT, CommandAction.CAPTURE_PHOTO),
            ),
        )

        val encoded = json.encodeToString(RelayHelloMessage.serializer(), message)

        assertTrue(encoded.contains("\"version\":\"${RelayProtocolConstants.PROTOCOL_VERSION}\""))
        assertTrue(encoded.contains("\"type\":\"hello\""))
        assertTrue(encoded.contains("\"capabilities\":[\"display_text\",\"capture_photo\"]"))
    }

    @Test
    fun `command record preserves terminal error and image mirrors`() {
        val record = CommandRecord(
            requestId = "req_abcdef",
            deviceId = "abc12345",
            action = CommandAction.CAPTURE_PHOTO,
            status = CommandStatus.FAILED,
            createdAt = 1717172000,
            updatedAt = 1717172001,
            error = TerminalError(
                code = TerminalErrorCode.UPLOAD_FAILED,
                message = "upload failed",
                retryable = true,
            ),
            image = CommandImage(
                imageId = "img_abcdef",
                transferId = "trf_abcdef",
                status = ImageStatus.FAILED,
            ),
        )

        val encoded = json.encodeToString(CommandRecord.serializer(), record)

        assertTrue(encoded.contains("\"code\":\"UPLOAD_FAILED\""))
        assertTrue(encoded.contains("\"status\":\"FAILED\""))
        assertTrue(encoded.contains("\"imageId\":\"img_abcdef\""))
    }

    @Test
    fun `capture photo payload preserves frozen default quality and mime type`() {
        val response = SubmitCapturePhotoCommandResponse(
            requestId = "req_abcdef",
            deviceId = "abc12345",
            status = CommandSubmissionStatus.DISPATCHED_TO_PHONE,
            createdAt = 1717172000,
            statusUrl = "/api/v1/commands/req_abcdef",
            image = ReservedImage(
                imageId = "img_abcdef",
                transferId = "trf_abcdef",
                expiresAt = 1717172100,
            ),
        )

        assertEquals(CapturePhotoQuality.MEDIUM, RelayProtocolConstants.DEFAULT_CAPTURE_PHOTO_QUALITY)
        assertEquals(RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE, response.image.mimeType)
    }
}
