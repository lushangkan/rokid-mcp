package cn.cutemc.rokidmcp.glasses.camera

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CameraXCameraAdapterTest {
    @Test
    fun `capture maps missing runtime permission to camera unavailable`() = runTest {
        val adapter = CameraXCameraAdapter(
            context = Application(),
            lifecycleOwner = TestLifecycleOwner(),
            mainExecutor = directExecutor(),
            hasCameraPermission = { false },
        )

        val error = try {
            adapter.capture(CapturePhotoQuality.MEDIUM)
            throw AssertionError("Expected CameraCaptureException")
        } catch (error: CameraCaptureException) {
            error
        }

        assertEquals(LocalProtocolErrorCodes.CAMERA_UNAVAILABLE, error.code)
        assertEquals("camera permission is not granted", error.message)
    }

    @Test
    fun `capture maps provider initialization failures to camera unavailable`() = runTest {
        val adapter = CameraXCameraAdapter(
            context = Application(),
            lifecycleOwner = TestLifecycleOwner(),
            mainExecutor = directExecutor(),
            hasCameraPermission = { true },
            cameraProviderLoader = {
                throw IOException("provider init failed")
            },
        )

        val error = try {
            adapter.capture(CapturePhotoQuality.MEDIUM)
            throw AssertionError("Expected CameraCaptureException")
        } catch (error: CameraCaptureException) {
            error
        }

        assertEquals(LocalProtocolErrorCodes.CAMERA_UNAVAILABLE, error.code)
        assertEquals("provider init failed", error.message)
    }
}

private class TestLifecycleOwner : LifecycleOwner {
    private val testLifecycle = object : Lifecycle() {
        override fun addObserver(observer: LifecycleObserver) = Unit

        override fun removeObserver(observer: LifecycleObserver) = Unit

        override val currentState: State
            get() = State.RESUMED
    }

    override val lifecycle: Lifecycle
        get() = testLifecycle
}

private fun directExecutor(): Executor = Executor { command ->
    command.run()
}
