package cn.cutemc.rokidmcp.share.protocol.constants

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PROTOCOL_VERSION_VALUE = "1.0"
private const val RELAY_PROTOCOL_NAME_VALUE = "rokid-relay-protocol"
private const val LOCAL_PROTOCOL_NAME_VALUE = "rokid-local-link"
private const val DEFAULT_IMAGE_CONTENT_TYPE_VALUE = "image/jpeg"
private const val MAX_IMAGE_UPLOAD_SIZE_BYTES_VALUE = 10 * 1024 * 1024

@Serializable
enum class CommandAction {
    @SerialName("display_text")
    DISPLAY_TEXT,

    @SerialName("capture_photo")
    CAPTURE_PHOTO,
}

@Serializable
enum class CapturePhotoQuality {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}

@Serializable
enum class SetupState {
    @SerialName("UNINITIALIZED")
    UNINITIALIZED,

    @SerialName("INITIALIZED")
    INITIALIZED,
}

@Serializable
enum class RuntimeState {
    @SerialName("DISCONNECTED")
    DISCONNECTED,

    @SerialName("CONNECTING")
    CONNECTING,

    @SerialName("READY")
    READY,

    @SerialName("BUSY")
    BUSY,

    @SerialName("ERROR")
    ERROR,
}

@Serializable
enum class UplinkState {
    @SerialName("OFFLINE")
    OFFLINE,

    @SerialName("CONNECTING")
    CONNECTING,

    @SerialName("ONLINE")
    ONLINE,

    @SerialName("ERROR")
    ERROR,
}

@Serializable
enum class DeviceSessionState {
    @SerialName("OFFLINE")
    OFFLINE,

    @SerialName("ONLINE")
    ONLINE,

    @SerialName("STALE")
    STALE,

    @SerialName("CLOSED")
    CLOSED,
}

@Serializable
enum class CommandStatus {
    @SerialName("CREATED")
    CREATED,

    @SerialName("DISPATCHED_TO_PHONE")
    DISPATCHED_TO_PHONE,

    @SerialName("ACKNOWLEDGED_BY_PHONE")
    ACKNOWLEDGED_BY_PHONE,

    @SerialName("RUNNING")
    RUNNING,

    @SerialName("COMPLETED")
    COMPLETED,

    @SerialName("FAILED")
    FAILED,

    @SerialName("TIMEOUT")
    TIMEOUT,

    @SerialName("CANCELLED")
    CANCELLED,
}

@Serializable
enum class ImageStatus {
    @SerialName("RESERVED")
    RESERVED,

    @SerialName("UPLOADING")
    UPLOADING,

    @SerialName("UPLOADED")
    UPLOADED,

    @SerialName("FAILED")
    FAILED,
}

@Serializable
enum class TerminalErrorCode {
    @SerialName("TIMEOUT")
    TIMEOUT,

    @SerialName("BLUETOOTH_UNAVAILABLE")
    BLUETOOTH_UNAVAILABLE,

    @SerialName("UPLOAD_FAILED")
    UPLOAD_FAILED,

    @SerialName("CHECKSUM_MISMATCH")
    CHECKSUM_MISMATCH,

    @SerialName("UNSUPPORTED_OPERATION")
    UNSUPPORTED_OPERATION,
}

object RelayProtocolConstants {
    const val PROTOCOL_NAME = RELAY_PROTOCOL_NAME_VALUE
    const val PROTOCOL_VERSION = PROTOCOL_VERSION_VALUE
    const val API_VERSION = "v1"

    const val WS_PATH_DEVICE = "/ws/device"
    const val HTTP_PATH_COMMANDS = "/api/v1/commands"
    const val HTTP_PATH_COMMAND_BY_ID = "/api/v1/commands/:requestId"
    const val HTTP_PATH_DEVICE_STATUS = "/api/v1/devices/:deviceId/status"
    const val HTTP_PATH_IMAGE_BY_ID = "/api/v1/images/:imageId"

    const val IMAGE_UPLOAD_METHOD = "PUT"
    const val DEFAULT_IMAGE_CONTENT_TYPE = DEFAULT_IMAGE_CONTENT_TYPE_VALUE
    val ACCEPTED_IMAGE_CONTENT_TYPES = listOf(DEFAULT_IMAGE_CONTENT_TYPE_VALUE)

    const val DISPLAY_TEXT_REPLACEMENT_MODE = "IMMEDIATE_REPLACEMENT"
    const val GLOBAL_ACTIVE_COMMAND_LIMIT = 1

    const val MAX_DISPLAY_TEXT_LENGTH = 500
    const val MAX_DISPLAY_TEXT_DURATION_MS = 60_000L
    val DEFAULT_CAPTURE_PHOTO_QUALITY = CapturePhotoQuality.MEDIUM
    const val DEFAULT_COMMAND_TIMEOUT_MS = 90_000L
    const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L
    const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 15_000L
    const val DEFAULT_SESSION_TTL_MS = 300_000L
    const val DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES = MAX_IMAGE_UPLOAD_SIZE_BYTES_VALUE.toLong()
}

object LocalProtocolConstants {
    const val PROTOCOL_NAME = LOCAL_PROTOCOL_NAME_VALUE
    const val PROTOCOL_VERSION = PROTOCOL_VERSION_VALUE
    const val FRAME_MAGIC = 0x524B4C31
    const val FRAME_HEADER_MAX_BYTES = 8 * 1024
    const val CHUNK_SIZE_BYTES = 16 * 1024
    const val MAX_IMAGE_SIZE_BYTES = MAX_IMAGE_UPLOAD_SIZE_BYTES_VALUE.toLong()
    const val HELLO_ACK_TIMEOUT_MS = 5_000L
    const val PING_INTERVAL_MS = 5_000L
    const val PONG_TIMEOUT_MS = 5_000L
    const val IDLE_TIMEOUT_MS = 15_000L
    const val PING_MAX_MISSES = 3
    const val IMAGE_MIME_TYPE_JPEG = DEFAULT_IMAGE_CONTENT_TYPE_VALUE
    const val CHUNK_CHECKSUM_ALGO = "CRC32"
    const val FILE_CHECKSUM_ALGO = "SHA-256"
}

object LocalProtocolErrorCodes {
    const val PROTOCOL_UNSUPPORTED_VERSION = "PROTOCOL_UNSUPPORTED_VERSION"
    const val PROTOCOL_INVALID_MESSAGE_TYPE = "PROTOCOL_INVALID_MESSAGE_TYPE"
    const val PROTOCOL_INVALID_PAYLOAD = "PROTOCOL_INVALID_PAYLOAD"
    const val PROTOCOL_HEADER_INVALID = "PROTOCOL_HEADER_INVALID"
    const val PROTOCOL_FRAME_TOO_LARGE = "PROTOCOL_FRAME_TOO_LARGE"
    const val PROTOCOL_REQUEST_ID_REQUIRED = "PROTOCOL_REQUEST_ID_REQUIRED"
    const val PROTOCOL_TRANSFER_ID_REQUIRED = "PROTOCOL_TRANSFER_ID_REQUIRED"
    const val BLUETOOTH_CONNECT_FAILED = "BLUETOOTH_CONNECT_FAILED"
    const val BLUETOOTH_DISCONNECTED = "BLUETOOTH_DISCONNECTED"
    const val BLUETOOTH_READ_FAILED = "BLUETOOTH_READ_FAILED"
    const val BLUETOOTH_SEND_FAILED = "BLUETOOTH_SEND_FAILED"
    const val BLUETOOTH_HELLO_TIMEOUT = "BLUETOOTH_HELLO_TIMEOUT"
    const val BLUETOOTH_PONG_TIMEOUT = "BLUETOOTH_PONG_TIMEOUT"
    const val BLUETOOTH_PROTOCOL_ERROR = "BLUETOOTH_PROTOCOL_ERROR"
    const val BLUETOOTH_HELLO_REJECTED = "BLUETOOTH_HELLO_REJECTED"
    const val COMMAND_BUSY = "COMMAND_BUSY"
    const val COMMAND_TIMEOUT = "COMMAND_TIMEOUT"
    const val COMMAND_SEQUENCE_INVALID = "COMMAND_SEQUENCE_INVALID"
    const val DISPLAY_FAILED = "DISPLAY_FAILED"
    const val CAMERA_UNAVAILABLE = "CAMERA_UNAVAILABLE"
    const val CAMERA_CAPTURE_FAILED = "CAMERA_CAPTURE_FAILED"
    const val IMAGE_TRANSFER_INCOMPLETE = "IMAGE_TRANSFER_INCOMPLETE"
    const val IMAGE_CHECKSUM_MISMATCH = "IMAGE_CHECKSUM_MISMATCH"
    const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    const val IMAGE_STORAGE_WRITE_FAILED = "IMAGE_STORAGE_WRITE_FAILED"
    const val UPLOAD_FAILED = "UPLOAD_FAILED"
    const val UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL"
}

object PhoneGatewayErrorCodes {
    const val PHONE_CONFIG_INCOMPLETE = "PHONE_CONFIG_INCOMPLETE"
    const val BLUETOOTH_TRANSPORT_UNAVAILABLE = "BLUETOOTH_TRANSPORT_UNAVAILABLE"
    const val BLUETOOTH_TRANSPORT_ERROR = "BLUETOOTH_TRANSPORT_ERROR"
    const val RELAY_SESSION_ERROR = "RELAY_SESSION_ERROR"
}
