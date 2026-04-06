package cn.cutemc.rokidmcp.share.protocol.local

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
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
enum class LocalCommandProgress {
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
    val supportedActions: List<CommandAction>,
)

@Serializable
data class HelloAckPayload(
    val accepted: Boolean,
    val role: LinkRole = LinkRole.GLASSES,
    val glassesInfo: GlassesInfo? = null,
    val capabilities: List<CommandAction>? = null,
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
data class DisplayTextCommand(
    val action: CommandAction = CommandAction.DISPLAY_TEXT,
    val timeoutMs: Long,
    val params: DisplayTextCommandParams,
)

@Serializable
data class DisplayTextCommandParams(
    val text: String,
    val durationMs: Long,
    val priority: String? = null,
)

@Serializable
data class CapturePhotoCommand(
    val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val timeoutMs: Long,
    val params: CapturePhotoCommandParams,
    val transfer: CaptureTransfer,
)

@Serializable
data class CapturePhotoCommandParams(
    val quality: CapturePhotoQuality? = null,
)

@Serializable
data class CaptureTransfer(
    val transferId: String,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val maxBytes: Long,
)

@Serializable
data class CommandAck(
    val action: CommandAction,
    val acceptedAt: Long,
    val runtimeState: LocalRuntimeState,
)

@Serializable(with = LocalCommandStatusSerializer::class)
sealed interface LocalCommandStatus {
    val action: CommandAction
    val status: LocalCommandProgress
    val statusAt: Long
    val detailCode: String?
    val detailMessage: String?
}

@Serializable
data class ExecutingCommandStatus(
    override val action: CommandAction,
    override val status: LocalCommandProgress = LocalCommandProgress.EXECUTING,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : LocalCommandStatus

@Serializable
data class DisplayingCommandStatus(
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    override val status: LocalCommandProgress = LocalCommandProgress.DISPLAYING,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : LocalCommandStatus

@Serializable
data class CapturingCommandStatus(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val status: LocalCommandProgress = LocalCommandProgress.CAPTURING,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : LocalCommandStatus

@Serializable(with = LocalCommandResultSerializer::class)
sealed interface LocalCommandResult {
    val action: CommandAction
}

@Serializable
data class DisplayTextResult(
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    val completedAt: Long,
    val result: DisplayTextOutcome,
) : LocalCommandResult

@Serializable
data class DisplayTextOutcome(
    val displayed: Boolean = true,
    val durationMs: Long,
)

@Serializable
data class CapturePhotoResult(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val completedAt: Long,
    val result: CapturePhotoOutcome,
) : LocalCommandResult

@Serializable
data class CapturePhotoOutcome(
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val size: Long,
    val width: Int,
    val height: Int,
    val sha256: String? = null,
)

@Serializable
data class CommandError(
    val action: CommandAction,
    val failedAt: Long,
    val error: CommandFailure,
)

@Serializable
data class CommandFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)

@Serializable
data class ChunkStart(
    val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val totalSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val sha256: String? = null,
)

@Serializable
data class ChunkData(
    val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val index: Int,
    val offset: Long,
    val size: Int,
    val chunkChecksum: String,
)

@Serializable
data class ChunkEnd(
    val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val totalChunks: Int,
    val totalSize: Long,
    val sha256: String? = null,
)
