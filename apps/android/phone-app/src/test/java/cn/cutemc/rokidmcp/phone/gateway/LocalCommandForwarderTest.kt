package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.local.CapturePhotoCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCommandForwarderTest {
    @Test
    fun `forwards display text command as local command frame`() = runTest {
        var sentHeader: LocalFrameHeader<*>? = null
        var sentBody: ByteArray? = null
        val forwarder = LocalCommandForwarder(
            clock = FakeClock(1_717_172_100L),
            sender = LocalFrameSender { header, body ->
                sentHeader = header
                sentBody = body
            },
        )

        val logs = captureTimberLogs {
            forwarder.forward(
                ActiveDisplayTextRelayCommand(
                    requestId = "req_display_1",
                    timeoutMs = 30_000L,
                    text = "hello glasses",
                    durationMs = 2_500L,
                ),
            )
        }

        val header = requireNotNull(sentHeader)
        val payload = header.payload as DisplayTextCommand
        assertEquals(LocalMessageType.COMMAND, header.type)
        assertEquals("req_display_1", header.requestId)
        assertEquals("hello glasses", payload.params.text)
        assertEquals(2_500L, payload.params.durationMs)
        assertNull(sentBody)
        logs.assertLog(Log.INFO, "relay-command", "forwarding local command requestId=req_display_1 action=DISPLAY_TEXT")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("hello glasses") })
    }

    @Test
    fun `forwards capture photo command with transfer metadata`() = runTest {
        var sentHeader: LocalFrameHeader<*>? = null
        val forwarder = LocalCommandForwarder(
            clock = FakeClock(1_717_172_200L),
            sender = LocalFrameSender { header, _ -> sentHeader = header },
        )

        val logs = captureTimberLogs {
            forwarder.forward(
                ActiveCapturePhotoRelayCommand(
                    requestId = "req_capture_1",
                    timeoutMs = 90_000L,
                    quality = CapturePhotoQuality.HIGH,
                    image = cn.cutemc.rokidmcp.share.protocol.relay.CommandDispatchImage(
                        imageId = "img_test_1",
                        transferId = "trf_test_1",
                        uploadToken = "upl_test_1",
                        expiresAt = 1_717_272_200L,
                        maxSizeBytes = 4_096L,
                    ),
                ),
            )
        }

        val header = requireNotNull(sentHeader)
        val payload = header.payload as CapturePhotoCommand
        assertEquals(LocalMessageType.COMMAND, header.type)
        assertEquals("req_capture_1", header.requestId)
        assertEquals(CapturePhotoQuality.HIGH, payload.params.quality)
        assertEquals("trf_test_1", payload.transfer.transferId)
        assertEquals(4_096L, payload.transfer.maxBytes)
        logs.assertLog(Log.INFO, "relay-command", "forwarding local command requestId=req_capture_1 action=CAPTURE_PHOTO")
        logs.assertLog(Log.INFO, "relay-command", "transferId=trf_test_1")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("upl_test_1") })
    }
}
