package cn.cutemc.rokidmcp.glasses.camera

import android.graphics.BitmapFactory
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

internal fun decodeJpegDimensions(bytes: ByteArray): Pair<Int, Int> {
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
