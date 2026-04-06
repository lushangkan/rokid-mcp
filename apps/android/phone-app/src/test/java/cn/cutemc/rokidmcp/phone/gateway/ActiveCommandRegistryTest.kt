package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CapturePhotoCommandPayload
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchImage
import cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchMessage
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandDispatchPayload
import cn.cutemc.rokidmcp.share.protocol.relay.DisplayTextCommandPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActiveCommandRegistryTest {
    @Test
    fun `begin stores active display text command`() {
        val registry = ActiveCommandRegistry()

        val active = registry.begin(displayDispatch())

        assertEquals("req_display_1", active.requestId)
        assertEquals(CommandAction.DISPLAY_TEXT, active.action)
        assertEquals(active, registry.activeOrNull())
    }

    @Test
    fun `begin rejects a second active command`() {
        val registry = ActiveCommandRegistry()
        registry.begin(displayDispatch())

        val error = assertThrows(ActiveCommandRegistryException::class.java) {
            registry.begin(captureDispatch())
        }

        assertEquals(LocalProtocolErrorCodes.COMMAND_BUSY, error.code)
    }

    @Test
    fun `require rejects mismatched request id`() {
        val registry = ActiveCommandRegistry()
        registry.begin(captureDispatch())

        val error = assertThrows(ActiveCommandRegistryException::class.java) {
            registry.require("req_other", CommandAction.CAPTURE_PHOTO)
        }

        assertEquals(LocalProtocolErrorCodes.COMMAND_SEQUENCE_INVALID, error.code)
    }

    private fun displayDispatch() = CommandDispatchMessage(
        deviceId = "phone-device",
        requestId = "req_display_1",
        sessionId = "ses_test",
        timestamp = 1_717_172_000L,
        payload = DisplayTextCommandDispatchPayload(
            timeoutMs = 30_000L,
            params = DisplayTextCommandPayload(
                text = "hello glasses",
                durationMs = 3_000L,
            ),
        ),
    )

    private fun captureDispatch() = CommandDispatchMessage(
        deviceId = "phone-device",
        requestId = "req_capture_1",
        sessionId = "ses_test",
        timestamp = 1_717_172_001L,
        payload = CapturePhotoCommandDispatchPayload(
            timeoutMs = 90_000L,
            params = CapturePhotoCommandPayload(),
            image = CommandDispatchImage(
                imageId = "img_test_1",
                transferId = "trf_test_1",
                uploadToken = "upl_test_1",
                expiresAt = 1_717_272_001L,
                maxSizeBytes = 1_024L,
            ),
        ),
    )
}
