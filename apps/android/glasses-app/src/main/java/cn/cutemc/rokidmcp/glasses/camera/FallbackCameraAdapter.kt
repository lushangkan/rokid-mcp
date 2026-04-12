package cn.cutemc.rokidmcp.glasses.camera

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import timber.log.Timber

class FallbackCameraAdapter(
    private val primary: CameraAdapter,
    private val fallback: CameraAdapter,
) : CameraAdapter {
    override suspend fun capture(quality: CapturePhotoQuality?): CameraCapture = try {
        primary.capture(quality)
    } catch (error: CameraCaptureException) {
        if (error.code != LocalProtocolErrorCodes.CAMERA_UNAVAILABLE) {
            throw error
        }

        Timber.tag("camera").w(
            error,
            "primary camera adapter is unavailable; retrying capture with fallback adapter",
        )
        fallback.capture(quality)
    }
}
