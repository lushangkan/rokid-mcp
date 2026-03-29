package cn.cutemc.rokidmcp.share.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class LocalMessageType {
    @SerialName("hello")
    HELLO,

    @SerialName("hello_ack")
    HELLO_ACK,

    @SerialName("ping")
    PING,

    @SerialName("pong")
    PONG,

    @SerialName("command")
    COMMAND,

    @SerialName("command_ack")
    COMMAND_ACK,

    @SerialName("command_status")
    COMMAND_STATUS,

    @SerialName("command_result")
    COMMAND_RESULT,

    @SerialName("command_error")
    COMMAND_ERROR,

    @SerialName("chunk_start")
    CHUNK_START,

    @SerialName("chunk_data")
    CHUNK_DATA,

    @SerialName("chunk_end")
    CHUNK_END,
}

@Serializable
enum class LocalAction {
    @SerialName("display_text")
    DISPLAY_TEXT,

    @SerialName("capture_photo")
    CAPTURE_PHOTO,
}

@Serializable
enum class LinkRole {
    @SerialName("PHONE")
    PHONE,

    @SerialName("GLASSES")
    GLASSES,
}

@Serializable
enum class LocalRuntimeState {
    @SerialName("READY")
    READY,

    @SerialName("BUSY")
    BUSY,

    @SerialName("ERROR")
    ERROR,
}

@Serializable
enum class LocalCommandStatus {
    @SerialName("executing")
    EXECUTING,

    @SerialName("displaying")
    DISPLAYING,

    @SerialName("capturing")
    CAPTURING,
}

data class LocalFrameHeader<out T : Any>(
    val version: String = LocalProtocolConstants.PROTOCOL_VERSION,
    val type: LocalMessageType,
    val requestId: String? = null,
    val transferId: String? = null,
    val timestamp: Long,
    val payload: T,
)

@Serializable
data class HelloPayload(
    val protocolName: String = LocalProtocolConstants.PROTOCOL_NAME,
    val role: LinkRole = LinkRole.PHONE,
    val deviceId: String,
    val appVersion: String,
    val appBuild: String? = null,
    val supportedActions: List<LocalAction>,
)

@Serializable
data class HelloAckPayload(
    val accepted: Boolean,
    val role: LinkRole = LinkRole.GLASSES,
    val glassesInfo: GlassesInfo? = null,
    val capabilities: List<LocalAction>? = null,
    val runtimeState: LocalRuntimeState? = null,
    val error: HelloError? = null,
)

@Serializable
data class GlassesInfo(
    val model: String? = null,
    val appVersion: String,
)

@Serializable
data class HelloError(
    val code: String,
    val message: String,
)

@Serializable
data class PingPayload(
    val seq: Long,
    val nonce: String,
)

@Serializable
data class PongPayload(
    val seq: Long,
    val nonce: String,
)

@Serializable
data class DisplayTextCommandPayload(
    val action: LocalAction = LocalAction.DISPLAY_TEXT,
    val timeoutMs: Long,
    val params: DisplayTextParams,
)

@Serializable
data class DisplayTextParams(
    val text: String,
    val durationMs: Long,
    val priority: String? = null,
)

@Serializable
data class CapturePhotoCommandPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val timeoutMs: Long,
    val params: CapturePhotoParams,
    val transfer: CaptureTransfer,
)

@Serializable
data class CapturePhotoParams(
    val quality: String? = null,
)

@Serializable
data class CaptureTransfer(
    val transferId: String,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val maxBytes: Long,
)

@Serializable
data class CommandAckPayload(
    val action: LocalAction,
    val acceptedAt: Long,
    val runtimeState: LocalRuntimeState,
)

@Serializable
data class CommandStatusPayload(
    val action: LocalAction,
    val status: LocalCommandStatus,
    val statusAt: Long,
    val detailCode: String? = null,
    val detailMessage: String? = null,
)

@Serializable
data class DisplayTextCommandResultPayload(
    val action: LocalAction = LocalAction.DISPLAY_TEXT,
    val completedAt: Long,
    val result: DisplayTextResult,
)

@Serializable
data class DisplayTextResult(
    val displayed: Boolean = true,
    val durationMs: Long,
)

@Serializable
data class CapturePhotoCommandResultPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val completedAt: Long,
    val result: CapturePhotoResult,
)

@Serializable
data class CapturePhotoResult(
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val size: Long,
    val width: Int,
    val height: Int,
    val sha256: String? = null,
)

@Serializable
data class CommandErrorPayload(
    val action: LocalAction,
    val failedAt: Long,
    val error: CommandError,
)

@Serializable
data class CommandError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)

@Serializable
data class ChunkStartPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val totalSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val sha256: String? = null,
)

@Serializable
data class ChunkDataPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val index: Int,
    val offset: Long,
    val size: Int,
    val chunkChecksum: String,
)

@Serializable
data class ChunkEndPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val totalChunks: Int,
    val totalSize: Long,
    val sha256: String? = null,
)
