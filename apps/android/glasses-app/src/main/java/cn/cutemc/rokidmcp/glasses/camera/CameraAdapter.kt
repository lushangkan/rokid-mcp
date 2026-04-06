package cn.cutemc.rokidmcp.glasses.camera

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality

interface CameraAdapter {
    suspend fun capture(quality: CapturePhotoQuality?): CameraCapture
}

data class CameraCapture(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(bytes.isNotEmpty()) { "captured jpeg bytes must not be empty" }
        require(width > 0) { "captured jpeg width must be positive" }
        require(height > 0) { "captured jpeg height must be positive" }
    }
}

class CameraCaptureException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)
