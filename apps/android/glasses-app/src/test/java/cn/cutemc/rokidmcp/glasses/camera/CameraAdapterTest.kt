package cn.cutemc.rokidmcp.glasses.camera

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CameraAdapterTest {
    @Test
    fun `camera adapter returns bytes with dimensions`() = runTest {
        val capture = object : CameraAdapter {
            override suspend fun capture(quality: CapturePhotoQuality?) = CameraCapture(
                bytes = "jpeg-bytes".encodeToByteArray(),
                width = 1280,
                height = 720,
            )
        }.capture(CapturePhotoQuality.HIGH)

        assertArrayEquals("jpeg-bytes".encodeToByteArray(), capture.bytes)
        assertEquals(1280, capture.width)
        assertEquals(720, capture.height)
    }

    @Test
    fun `camera capture requires non empty bytes and positive dimensions`() {
        assertIllegalArgument("captured jpeg bytes must not be empty") {
            CameraCapture(bytes = ByteArray(0), width = 1280, height = 720)
        }
        assertIllegalArgument("captured jpeg width must be positive") {
            CameraCapture(bytes = "jpeg".encodeToByteArray(), width = 0, height = 720)
        }
        assertIllegalArgument("captured jpeg height must be positive") {
            CameraCapture(bytes = "jpeg".encodeToByteArray(), width = 1280, height = 0)
        }
    }

    private fun assertIllegalArgument(expectedMessage: String, block: () -> Unit) {
        val error = try {
            block()
            fail("Expected IllegalArgumentException")
            return
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals(expectedMessage, error.message)
    }
}
