package cn.cutemc.rokidmcp.glasses.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import timber.log.Timber

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
            val (width, height) = decodeJpegDimensions(bytes)

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
                    }.onFailure { error ->
                        Timber.tag("camera").w(error, "failed to unbind CameraX provider after capture")
                    }
                }
                outputFile?.let { file ->
                    runCatching {
                        tempFileDeleter(file)
                    }.onFailure { error ->
                        Timber.tag("camera").w(error, "failed to delete temporary capture file")
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
        Timber.tag("camera").e(error, "failed to load camera provider")
        throw mapUnavailable(error, "camera is unavailable")
    }

    private suspend fun createTempFile(): File = try {
        tempFileFactory(context)
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to create temporary capture file")
        throw mapCaptureFailure(error, "camera capture could not allocate temporary storage")
    }

    private suspend fun readCaptureBytes(file: File): ByteArray = try {
        captureBytesReader(file)
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to read captured image bytes")
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
        Timber.tag("camera").e(error, "failed to bind CameraX image capture use case")
        throw mapUnavailable(error, "camera is unavailable")
    }

    private fun selectCamera(cameraProvider: ProcessCameraProvider): CameraSelector {
        val preferredSelectors = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA,
        )

        val preferredSelector = preferredSelectors.firstOrNull { selector ->
            try {
                cameraProvider.hasCamera(selector)
            } catch (error: CameraInfoUnavailableException) {
                Timber.tag("camera").w(error, "failed to inspect camera availability")
                false
            }
        }
        if (preferredSelector != null) {
            return preferredSelector
        }

        Timber.tag("camera").w(
            "preferred front/back selectors are unavailable; falling back to the first CameraX camera",
        )
        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos -> cameraInfos.take(1) }
            .build()
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
                    Timber.tag("camera").e(exception, "CameraX image capture callback failed")
                    continuation.resumeWithException(exception)
                }
            },
        )
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
                Timber.tag("camera").e(error, "failed to resolve ProcessCameraProvider future")
                continuation.resumeWithException(error)
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}
