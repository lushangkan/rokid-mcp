package cn.cutemc.rokidmcp.glasses.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.core.content.ContextCompat
import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber

private const val CAMERA2_CAPTURE_TIMEOUT_MS = 15_000L

class Camera2CameraAdapter(
    private val context: Context,
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(context),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val hasCameraPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    },
    private val cameraManagerProvider: (Context) -> CameraManager = { adapterContext ->
        requireNotNull(adapterContext.getSystemService(CameraManager::class.java)) {
            "camera manager is unavailable"
        }
    },
) : CameraAdapter {
    override suspend fun capture(quality: CapturePhotoQuality?): CameraCapture {
        ensureCameraPermission()

        var imageReader: ImageReader? = null
        var captureSession: CameraCaptureSession? = null
        var cameraDevice: CameraDevice? = null

        try {
            val cameraManager = loadCameraManager()
            val target = selectCameraTarget(cameraManager)
            Timber.tag("camera").i(
                "using Camera2 fallback cameraId=%s lensFacing=%s size=%dx%d",
                target.cameraId,
                target.lensFacing,
                target.size.width,
                target.size.height,
            )
            imageReader = ImageReader.newInstance(target.size.width, target.size.height, ImageFormat.JPEG, 2)
            cameraDevice = openCamera(cameraManager, target.cameraId)
            captureSession = createCaptureSession(cameraDevice, imageReader)
            val bytes = captureJpeg(cameraDevice, captureSession, imageReader, quality)
            val (width, height) = decodeJpegDimensions(bytes)

            return CameraCapture(
                bytes = bytes,
                width = width,
                height = height,
            )
        } finally {
            imageReader?.let { reader ->
                runCatching {
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                }.onFailure { error ->
                    Timber.tag("camera").w(error, "failed to close Camera2 image reader")
                }
            }
            captureSession?.let { session ->
                runCatching {
                    session.close()
                }.onFailure { error ->
                    Timber.tag("camera").w(error, "failed to close Camera2 capture session")
                }
            }
            cameraDevice?.let { device ->
                runCatching {
                    device.close()
                }.onFailure { error ->
                    Timber.tag("camera").w(error, "failed to close Camera2 device")
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

    private fun loadCameraManager(): CameraManager = try {
        cameraManagerProvider(context)
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to load CameraManager")
        throw mapUnavailable(error, "camera manager is unavailable")
    }

    private fun selectCameraTarget(cameraManager: CameraManager): Camera2Target = try {
        cameraManager.cameraIdList
            .mapNotNull { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@mapNotNull null
                val jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)
                    ?.filter { it.width > 0 && it.height > 0 }
                    .orEmpty()
                if (jpegSizes.isEmpty()) {
                    return@mapNotNull null
                }

                Camera2Target(
                    cameraId = cameraId,
                    size = jpegSizes.maxBy { it.width.toLong() * it.height.toLong() },
                    lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING),
                )
            }
            .minWithOrNull(compareBy<Camera2Target>({ it.lensFacing.preferenceScore() }, { it.cameraId }))
            ?: throw CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                message = "no camera is available for capture_photo",
            )
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to inspect Camera2 targets")
        throw mapUnavailable(error, "camera is unavailable")
    }

    private suspend fun openCamera(cameraManager: CameraManager, cameraId: String): CameraDevice = try {
        withTimeout(CAMERA2_CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                cameraManager.openCamera(
                    cameraId,
                    callbackExecutor,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (!continuation.isActive) {
                                camera.close()
                                return
                            }
                            continuation.resume(camera)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (!continuation.isActive) {
                                return
                            }
                            continuation.resumeWithException(
                                CameraCaptureException(
                                    code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                                    message = "camera disconnected before capture could start",
                                ),
                            )
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (!continuation.isActive) {
                                return
                            }
                            continuation.resumeWithException(
                                CameraCaptureException(
                                    code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                                    message = "camera open failed with error code $error",
                                ),
                            )
                        }
                    },
                )
            }
        }
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to open Camera2 device cameraId=$cameraId")
        throw mapUnavailable(error, "camera is unavailable")
    }

    private suspend fun createCaptureSession(
        cameraDevice: CameraDevice,
        imageReader: ImageReader,
    ): CameraCaptureSession = try {
        withTimeout(CAMERA2_CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                cameraDevice.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(imageReader.surface)),
                        callbackExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (!continuation.isActive) {
                                    session.close()
                                    return
                                }
                                continuation.resume(session)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                session.close()
                                if (!continuation.isActive) {
                                    return
                                }
                                continuation.resumeWithException(
                                    CameraCaptureException(
                                        code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                                        message = "camera capture session could not be configured",
                                    ),
                                )
                            }
                        },
                    ),
                )
            }
        }
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to create Camera2 capture session")
        throw mapUnavailable(error, "camera is unavailable")
    }

    private suspend fun captureJpeg(
        cameraDevice: CameraDevice,
        captureSession: CameraCaptureSession,
        imageReader: ImageReader,
        quality: CapturePhotoQuality?,
    ): ByteArray = try {
        withTimeout(CAMERA2_CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                imageReader.setOnImageAvailableListener(
                    { reader ->
                        if (!continuation.isActive) {
                            return@setOnImageAvailableListener
                        }

                        val image = try {
                            reader.acquireLatestImage()
                        } catch (error: Throwable) {
                            continuation.resumeWithException(mapCaptureFailure(error, "camera image could not be acquired"))
                            return@setOnImageAvailableListener
                        }

                        if (image == null) {
                            return@setOnImageAvailableListener
                        }

                        val bytes = try {
                            image.use {
                                val buffer = it.planes.firstOrNull()?.buffer
                                    ?: throw IllegalStateException("camera image plane is missing")
                                ByteArray(buffer.remaining()).also(buffer::get)
                            }
                        } catch (error: Throwable) {
                            continuation.resumeWithException(mapCaptureFailure(error, "camera image could not be read"))
                            return@setOnImageAvailableListener
                        }

                        continuation.resume(bytes)
                    },
                    mainHandler,
                )

                val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.JPEG_QUALITY, quality.toJpegQuality())
                }.build()

                try {
                    captureSession.capture(
                        request,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure,
                            ) {
                                if (!continuation.isActive) {
                                    return
                                }
                                continuation.resumeWithException(
                                    CameraCaptureException(
                                        code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                                        message = "camera capture failed with reason ${failure.reason}",
                                    ),
                                )
                            }

                            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                                if (!continuation.isActive) {
                                    return
                                }
                                continuation.resumeWithException(
                                    CameraCaptureException(
                                        code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                                        message = "camera capture sequence was aborted",
                                    ),
                                )
                            }
                        },
                        mainHandler,
                    )
                } catch (error: Throwable) {
                    if (!continuation.isActive) {
                        throw error
                    }
                    continuation.resumeWithException(mapCaptureFailure(error, "camera capture request failed"))
                }
            }
        }
    } catch (error: Throwable) {
        Timber.tag("camera").e(error, "failed to capture JPEG with Camera2")
        throw mapCaptureFailure(error, "camera capture failed")
    }

    private fun mapUnavailable(error: Throwable, fallbackMessage: String): CameraCaptureException {
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
            is CameraAccessException,
            is IllegalStateException,
            -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                message = error.message ?: fallbackMessage,
            )
            else -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_UNAVAILABLE,
                message = error.message ?: fallbackMessage,
            )
        }
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
            else -> CameraCaptureException(
                code = LocalProtocolErrorCodes.CAMERA_CAPTURE_FAILED,
                message = error.message ?: fallbackMessage,
            )
        }
    }
}

private data class Camera2Target(
    val cameraId: String,
    val size: Size,
    val lensFacing: Int?,
)

private fun Int?.preferenceScore(): Int = when (this) {
    CameraCharacteristics.LENS_FACING_BACK -> 0
    CameraCharacteristics.LENS_FACING_FRONT -> 1
    else -> 2
}

private fun CapturePhotoQuality?.toJpegQuality(): Byte = when (this) {
    CapturePhotoQuality.LOW -> 70.toByte()
    CapturePhotoQuality.MEDIUM -> 85.toByte()
    CapturePhotoQuality.HIGH,
    null,
    -> 95.toByte()
}
