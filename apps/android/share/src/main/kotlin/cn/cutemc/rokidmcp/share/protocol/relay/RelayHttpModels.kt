package cn.cutemc.rokidmcp.share.protocol.relay

import cn.cutemc.rokidmcp.share.protocol.constants.CapturePhotoQuality
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.CommandStatus
import cn.cutemc.rokidmcp.share.protocol.constants.DeviceSessionState
import cn.cutemc.rokidmcp.share.protocol.constants.ImageStatus
import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.RelayProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.SetupState
import cn.cutemc.rokidmcp.share.protocol.constants.TerminalErrorCode
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object RelayProtocolJson {
    val default: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
}

@Serializable
data class TerminalError(
    val code: TerminalErrorCode,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)

@Serializable
data class RelayError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)

@Serializable
data class ErrorResponse(
    val ok: Boolean = false,
    val error: RelayError,
    val timestamp: Long,
)

@Serializable
data class DisplayTextCommandPayload(
    val text: String,
    val durationMs: Long,
)

@Serializable
data class CapturePhotoCommandPayload(
    val quality: CapturePhotoQuality? = null,
)

@Serializable(with = SubmitCommandRequestSerializer::class)
sealed interface SubmitCommandRequest {
    val deviceId: String
    val action: CommandAction
}

@Serializable
data class SubmitDisplayTextCommandRequest(
    override val deviceId: String,
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    val payload: DisplayTextCommandPayload,
) : SubmitCommandRequest

@Serializable
data class SubmitCapturePhotoCommandRequest(
    override val deviceId: String,
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val payload: CapturePhotoCommandPayload,
) : SubmitCommandRequest

@Serializable
enum class CommandSubmissionStatus {
    @SerialName("CREATED")
    CREATED,

    @SerialName("DISPATCHED_TO_PHONE")
    DISPATCHED_TO_PHONE,
}

@Serializable
data class ReservedImage(
    val imageId: String,
    val transferId: String,
    val status: ImageStatus = ImageStatus.RESERVED,
    val mimeType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val expiresAt: Long,
)

@Serializable(with = SubmitCommandResponseSerializer::class)
sealed interface SubmitCommandResponse {
    val ok: Boolean
    val requestId: String
    val deviceId: String
    val action: CommandAction
    val status: CommandSubmissionStatus
    val createdAt: Long
    val statusUrl: String
}

@Serializable
data class SubmitDisplayTextCommandResponse(
    override val ok: Boolean = true,
    override val requestId: String,
    override val deviceId: String,
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    override val status: CommandSubmissionStatus,
    override val createdAt: Long,
    override val statusUrl: String,
) : SubmitCommandResponse

@Serializable
data class SubmitCapturePhotoCommandResponse(
    override val ok: Boolean = true,
    override val requestId: String,
    override val deviceId: String,
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val status: CommandSubmissionStatus,
    override val createdAt: Long,
    override val statusUrl: String,
    val image: ReservedImage,
) : SubmitCommandResponse

@Serializable(with = CommandResultSerializer::class)
sealed interface CommandResult {
    val action: CommandAction
}

@Serializable
data class DisplayTextCommandResult(
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    val displayed: Boolean = true,
    val durationMs: Long,
) : CommandResult

@Serializable
data class CapturePhotoCommandResult(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    val imageId: String,
    val transferId: String,
    val mimeType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val size: Long,
    val width: Int,
    val height: Int,
    val sha256: String? = null,
) : CommandResult

@Serializable
data class CommandImage(
    val imageId: String,
    val transferId: String,
    val status: ImageStatus,
    val mimeType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sha256: String? = null,
    val expiresAt: Long? = null,
    val uploadedAt: Long? = null,
)

@Serializable
data class CommandRecord(
    val requestId: String,
    val deviceId: String,
    val action: CommandAction,
    val status: CommandStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val acknowledgedAt: Long? = null,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val result: CommandResult? = null,
    val error: TerminalError? = null,
    val image: CommandImage? = null,
)

@Serializable
data class CommandStatusResponse(
    val ok: Boolean = true,
    val command: CommandRecord,
    val timestamp: Long,
)

typealias GetCommandStatusResponse = CommandStatusResponse

@Serializable
data class GetDeviceStatusParams(
    val deviceId: String,
)

@Serializable
data class GetDeviceStatusDevice(
    val deviceId: String,
    val connected: Boolean,
    val sessionState: DeviceSessionState,
    val setupState: SetupState,
    val runtimeState: RuntimeState,
    val capabilities: List<CommandAction>,
    val activeCommandRequestId: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastSeenAt: Long? = null,
    val sessionId: String? = null,
)

@Serializable
data class GetDeviceStatusResponse(
    val ok: Boolean = true,
    val device: GetDeviceStatusDevice,
    val timestamp: Long,
)

@Serializable
data class ImageUploadHeaders(
    val contentType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val contentLength: Long? = null,
    val deviceId: String,
    val requestId: String,
    val sha256: String? = null,
)

@Serializable
data class ImageUploadRequest(
    val imageId: String,
    val transferId: String,
    val uploadToken: String,
    val headers: ImageUploadHeaders,
)

@Serializable
data class UploadedImage(
    val imageId: String,
    val transferId: String,
    val status: ImageStatus = ImageStatus.UPLOADED,
    val mimeType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val size: Long,
    val sha256: String? = null,
    val uploadedAt: Long,
)

@Serializable
data class ImageUploadResponse(
    val ok: Boolean = true,
    val image: UploadedImage,
    val timestamp: Long,
)

@Serializable
data class DownloadedImage(
    val imageId: String,
    val transferId: String,
    val status: ImageStatus = ImageStatus.UPLOADED,
    val mimeType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val size: Long,
    val sha256: String? = null,
)

@Serializable
data class ImageDownloadResponse(
    val ok: Boolean = true,
    val image: DownloadedImage,
    val timestamp: Long,
)

object SubmitCommandRequestSerializer : JsonContentPolymorphicSerializer<SubmitCommandRequest>(SubmitCommandRequest::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SubmitCommandRequest> = when (element.actionValue()) {
        CommandAction.DISPLAY_TEXT.serialName -> SubmitDisplayTextCommandRequest.serializer()
        CommandAction.CAPTURE_PHOTO.serialName -> SubmitCapturePhotoCommandRequest.serializer()
        else -> throw IllegalArgumentException("Unknown submit command action")
    }
}

object SubmitCommandResponseSerializer : JsonContentPolymorphicSerializer<SubmitCommandResponse>(SubmitCommandResponse::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SubmitCommandResponse> = when (element.actionValue()) {
        CommandAction.DISPLAY_TEXT.serialName -> SubmitDisplayTextCommandResponse.serializer()
        CommandAction.CAPTURE_PHOTO.serialName -> SubmitCapturePhotoCommandResponse.serializer()
        else -> throw IllegalArgumentException("Unknown submit command response action")
    }
}

object CommandResultSerializer : JsonContentPolymorphicSerializer<CommandResult>(CommandResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CommandResult> = when (element.actionValue()) {
        CommandAction.DISPLAY_TEXT.serialName -> DisplayTextCommandResult.serializer()
        CommandAction.CAPTURE_PHOTO.serialName -> CapturePhotoCommandResult.serializer()
        else -> throw IllegalArgumentException("Unknown command result action")
    }
}

private fun JsonElement.actionValue(): String = jsonObject["action"]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("Missing action discriminator")

private val CommandAction.serialName: String
    get() = when (this) {
        CommandAction.DISPLAY_TEXT -> "display_text"
        CommandAction.CAPTURE_PHOTO -> "capture_photo"
    }
