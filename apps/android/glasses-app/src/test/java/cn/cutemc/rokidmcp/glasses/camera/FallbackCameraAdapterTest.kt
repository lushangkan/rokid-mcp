package cn.cutemc.rokidmcp.glasses.camera

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCameraAdapterTest {
    @Test
    fun `fallback adapter returns primary capture when primary succeeds`() = runTest {
        val primaryCapture = CameraCapture(
            bytes = "primary-jpeg".encodeToByteArray(),
            width = 640,
            height = 480,
        )
        var fallbackInvoked = false
        val adapter = FallbackCameraAdapter(
            primary = FakeDelegatingCameraAdapter(result = primaryCapture),
            fallback = object : CameraAdapter {
                override suspend fun capture(quality: CapturePhotoQuality?): CameraCapture {
                    fallbackInvoked = true
                    return CameraCapture(
                        bytes = "fallback-jpeg".encodeToByteArray(),
                        width = 320,
                        height = 240,
                    )
                }
            },
        )

        val capture = adapter.capture(CapturePhotoQuality.MEDIUM)

        assertSame(primaryCapture, capture)
        assertTrue(!fallbackInvoked)
    }

    @Test
    fun `fallback adapter retries with fallback when primary camera is unavailable`() = runTest {
        val fallbackCapture = CameraCapture(
            bytes = "fallback-jpeg".encodeToByteArray(),
            width = 320,
            height = 240,
        )
        val adapter = FallbackCameraAdapter(
            primary = FakeDelegatingCameraAdapter(
                failure = CameraCaptureException(
                    code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                    message = "CameraX init failed",
                ),
            ),
            fallback = FakeDelegatingCameraAdapter(result = fallbackCapture),
        )

        val capture = adapter.capture(CapturePhotoQuality.HIGH)

        assertSame(fallbackCapture, capture)
    }

    @Test
    fun `fallback adapter does not mask primary capture failures`() = runTest {
        val adapter = FallbackCameraAdapter(
            primary = FakeDelegatingCameraAdapter(
                failure = CameraCaptureException(
                    code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                    message = "jpeg payload invalid",
                ),
            ),
            fallback = FakeDelegatingCameraAdapter(
                result = CameraCapture(
                    bytes = "fallback-jpeg".encodeToByteArray(),
                    width = 320,
                    height = 240,
                ),
            ),
        )

        val error = try {
            adapter.capture(CapturePhotoQuality.LOW)
            throw AssertionError("Expected CameraCaptureException")
        } catch (error: CameraCaptureException) {
            error
        }

        assertEquals(LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED, error.code)
        assertEquals("jpeg payload invalid", error.message)
    }
}

private class FakeDelegatingCameraAdapter(
    private val result: CameraCapture? = null,
    private val failure: CameraCaptureException? = null,
) : CameraAdapter {
    override suspend fun capture(quality: CapturePhotoQuality?): CameraCapture {
        failure?.let { throw it }
        return requireNotNull(result)
    }
}
