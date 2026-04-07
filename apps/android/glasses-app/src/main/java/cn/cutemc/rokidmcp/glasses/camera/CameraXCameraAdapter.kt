package cn.cutemc.rokidmcp.glasses.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class CameraXCameraAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context),
    private val hasCameraPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    },
    private val cameraProviderLoader: suspend (Context) -> ProcessCameraProvider = { adapterContext ->
        awaitCameraProvider(adapterContext)
    },
    private val tempFileFactory: suspend (Context) -> File = { adapterContext ->
        withContext(ioDispatcher) {
            File.createTempFile("rokid-capture-", ".jpg", adapterContext.cacheDir)
        }
    },
    private val captureBytesReader: suspend (File) -> ByteArray = { file ->
        withContext(ioDispatcher) {
            file.readBytes()
        }
    },
    private val tempFileDeleter: suspend (File) -> Unit = { file ->
        withContext(ioDispatcher) {
            file.delete()
        }
    },
) : CameraAdapter {
    override suspend fun capture(quality: CapturePhotoQuality?): CameraCapture {
        ensureCameraPermission()

        var cameraProvider: ProcessCameraProvider? = null
        var outputFile: File? = null

        try {
            cameraProvider = loadCameraProvider()
            outputFile = createTempFile()
            val imageCapture = createImageCapture(quality)
            bindImageCapture(cameraProvider, imageCapture)
            imageCapture.captureToFile(outputFile, mainExecutor)
            val bytes = readCaptureBytes(outputFile)
            val (width, height) = decodeJpegMetadata(bytes)

            return CameraCapture(
                bytes = bytes,
                width = width,
                height = height,
            )
        } finally {
            withContext(NonCancellable) {
                cameraProvider?.let { provider ->
                    runCatching {
                        withContext(Dispatchers.Main.immediate) {
                            provider.unbindAll()
                        }
                    }
                }
                outputFile?.let { file ->
                    runCatching {
                        tempFileDeleter(file)
                    }
                }
            }
        }
    }

    private fun ensureCameraPermission() {
        if (!hasCameraPermission()) {
            throw CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                message = "camera permission is not granted",
            )
        }
    }

    private suspend fun loadCameraProvider(): ProcessCameraProvider = try {
        cameraProviderLoader(context)
    } catch (error: Throwable) {
        throw mapUnavailable(error, "camera is unavailable")
    }

    private suspend fun createTempFile(): File = try {
        tempFileFactory(context)
    } catch (error: Throwable) {
        throw mapCaptureFailure(error, "camera capture could not allocate temporary storage")
    }

    private suspend fun readCaptureBytes(file: File): ByteArray = try {
        captureBytesReader(file)
    } catch (error: Throwable) {
        throw mapCaptureFailure(error, "camera capture could not be read")
    }

    private fun createImageCapture(quality: CapturePhotoQuality?): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(quality.toCaptureMode())
        .build()

    private suspend fun bindImageCapture(
        cameraProvider: ProcessCameraProvider,
        imageCapture: ImageCapture,
    ) = try {
        withContext(Dispatchers.Main.immediate) {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selectCamera(cameraProvider),
                imageCapture,
            )
        }
    } catch (error: Throwable) {
        throw mapUnavailable(error, "camera is unavailable")
    }

    private fun selectCamera(cameraProvider: ProcessCameraProvider): CameraSelector {
        val preferredSelectors = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA,
        )

        return preferredSelectors.firstOrNull { selector ->
            try {
                cameraProvider.hasCamera(selector)
            } catch (_: CameraInfoUnavailableException) {
                false
            }
        } ?: throw CameraCaptureException(
            code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
            message = "no camera is available for capture_photo",
        )
    }

    private suspend fun ImageCapture.captureToFile(file: File, executor: Executor) = suspendCancellableCoroutine<Unit> { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (!continuation.isActive) {
                        return
                    }
                    continuation.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (!continuation.isActive) {
                        return
                    }
                    continuation.resumeWithException(exception)
                }
            },
        )
    }

    private fun decodeJpegMetadata(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        require(width > 0 && height > 0) {
            "camera returned a jpeg payload without valid dimensions"
        }
        return width to height
    }

    private fun mapUnavailable(error: Throwable, fallbackMessage: String): CameraCaptureException {
        if (error is CancellationException) {
            throw error
        }
        if (error is CameraCaptureException) {
            return error
        }
        return CameraCaptureException(
            code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
            message = error.message ?: fallbackMessage,
        )
    }

    private fun mapCaptureFailure(error: Throwable, fallbackMessage: String): CameraCaptureException {
        if (error is CancellationException) {
            throw error
        }
        if (error is CameraCaptureException) {
            return error
        }
        return when (error) {
            is SecurityException -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                message = "camera permission is not granted",
            )
            is IllegalStateException -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                message = error.message ?: "camera is unavailable",
            )
            is ImageCaptureException,
            is IllegalArgumentException,
            -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                message = error.message ?: fallbackMessage,
            )
            else -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                message = error.message ?: fallbackMessage,
            )
        }
    }
}

private fun CapturePhotoQuality?.toCaptureMode(): Int = when (this) {
    CapturePhotoQuality.LOW -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    CapturePhotoQuality.MEDIUM,
    CapturePhotoQuality.HIGH,
    null,
    -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
        {
            try {
                if (!continuation.isActive) {
                    return@addListener
                }
                continuation.resume(future.get())
            } catch (error: Exception) {
                if (!continuation.isActive) {
                    return@addListener
                }
                continuation.resumeWithException(error)
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}
