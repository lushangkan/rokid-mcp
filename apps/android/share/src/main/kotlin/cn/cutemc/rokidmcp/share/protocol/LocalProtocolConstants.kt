package cn.cutemc.rokidmcp.share.protocol

object LocalProtocolConstants {
    const val PROTOCOL_VERSION = "1.0"
    const val PROTOCOL_NAME = "rokid-local-link"
    const val FRAME_MAGIC = 0x524B4C31
    const val FRAME_HEADER_MAX_BYTES = 8 * 1024
    const val CHUNK_SIZE_BYTES = 16 * 1024
    const val MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024
    const val HELLO_ACK_TIMEOUT_MS = 5_000L
    const val PING_INTERVAL_MS = 5_000L
    const val PONG_TIMEOUT_MS = 5_000L
    const val IDLE_TIMEOUT_MS = 15_000L
    const val PING_MAX_MISSES = 3
    const val IMAGE_MIME_TYPE_JPEG = "image/jpeg"
    const val CHUNK_CHECKSUM_ALGO = "CRC32"
    const val FILE_CHECKSUM_ALGO = "SHA-256"
}
