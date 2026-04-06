package cn.cutemc.rokidmcp.share.protocol.relay

import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.constants.RelayProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.constants.RuntimeState
import cn.cutemc.rokidmcp.share.protocol.constants.SetupState
import cn.cutemc.rokidmcp.share.protocol.constants.TerminalErrorCode
import cn.cutemc.rokidmcp.share.protocol.constants.UplinkState
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class RelayMessageType {
    @SerialName("hello")
    HELLO,

    @SerialName("hello_ack")
    HELLO_ACK,

    @SerialName("heartbeat")
    HEARTBEAT,

    @SerialName("phone_state_update")
    PHONE_STATE_UPDATE,

    @SerialName("command")
    COMMAND,

    @SerialName("command_cancel")
    COMMAND_CANCEL,

    @SerialName("command_ack")
    COMMAND_ACK,

    @SerialName("command_status")
    COMMAND_STATUS,

    @SerialName("command_result")
    COMMAND_RESULT,

    @SerialName("command_error")
    COMMAND_ERROR,
}

@Serializable
data class RelayPhoneInfo(
    val brand: String? = null,
    val model: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
)

@Serializable
data class TargetGlasses(
    val bluetoothName: String? = null,
    val bluetoothAddress: String? = null,
)

@Serializable
data class RelayConfig(
    val baseUrl: String? = null,
)

@Serializable
data class RelayHelloPayload(
    val authToken: String,
    val appVersion: String,
    val appBuild: String? = null,
    val phoneInfo: RelayPhoneInfo,
    val setupState: SetupState,
    val runtimeState: RuntimeState,
    val uplinkState: UplinkState,
    val capabilities: List<CommandAction>,
    val targetGlasses: TargetGlasses? = null,
    val relayConfig: RelayConfig? = null,
)

@Serializable
data class RelayHelloMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.HELLO,
    val deviceId: String,
    val timestamp: Long,
    val payload: RelayHelloPayload,
)

@Serializable
data class RelayHeartbeatPayload(
    val seq: Long,
    val runtimeState: RuntimeState,
    val uplinkState: UplinkState,
    val pendingCommandCount: Int,
    val activeCommandRequestId: String? = null,
)

@Serializable
data class RelayHeartbeatMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.HEARTBEAT,
    val deviceId: String,
    val sessionId: String,
    val timestamp: Long,
    val payload: RelayHeartbeatPayload,
)

@Serializable
data class RelayPhoneStateUpdatePayload(
    val setupState: SetupState,
    val runtimeState: RuntimeState,
    val uplinkState: UplinkState,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val activeCommandRequestId: String? = null,
)

@Serializable
data class RelayPhoneStateUpdateMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.PHONE_STATE_UPDATE,
    val deviceId: String,
    val sessionId: String,
    val timestamp: Long,
    val payload: RelayPhoneStateUpdatePayload,
)

@Serializable
data class RelayLimits(
    val maxPendingCommands: Int = RelayProtocolConstants.GLOBAL_ACTIVE_COMMAND_LIMIT,
    val maxImageUploadSizeBytes: Long,
    val acceptedImageContentTypes: List<String> = RelayProtocolConstants.ACCEPTED_IMAGE_CONTENT_TYPES,
)

@Serializable
data class RelayHelloAckPayload(
    val sessionId: String,
    val serverTime: Long,
    val heartbeatIntervalMs: Long,
    val heartbeatTimeoutMs: Long,
    val sessionTtlMs: Long? = null,
    val limits: RelayLimits,
)

@Serializable
data class RelayHelloAckMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.HELLO_ACK,
    val deviceId: String,
    val timestamp: Long,
    val payload: RelayHelloAckPayload,
)

@Serializable
data class CommandDispatchImage(
    val imageId: String,
    val transferId: String,
    val uploadToken: String,
    val contentType: String = RelayProtocolConstants.DEFAULT_IMAGE_CONTENT_TYPE,
    val expiresAt: Long,
    val maxSizeBytes: Long,
)

@Serializable(with = CommandDispatchPayloadSerializer::class)
sealed interface CommandDispatchPayload {
    val action: CommandAction
    val timeoutMs: Long
}

@Serializable
data class DisplayTextCommandDispatchPayload(
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    override val timeoutMs: Long,
    val params: DisplayTextCommandPayload,
) : CommandDispatchPayload

@Serializable
data class CapturePhotoCommandDispatchPayload(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val timeoutMs: Long,
    val params: CapturePhotoCommandPayload,
    val image: CommandDispatchImage,
) : CommandDispatchPayload

@Serializable
data class CommandDispatchMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.COMMAND,
    val deviceId: String,
    val requestId: String,
    val sessionId: String,
    val timestamp: Long,
    val payload: CommandDispatchPayload,
)

@Serializable
data class CommandCancelPayload(
    val action: CommandAction,
    val cancelledAt: Long,
    val reasonCode: TerminalErrorCode? = null,
    val reasonMessage: String? = null,
)

@Serializable
data class CommandCancelMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.COMMAND_CANCEL,
    val deviceId: String,
    val requestId: String,
    val sessionId: String,
    val timestamp: Long,
    val payload: CommandCancelPayload,
)

@Serializable
data class CommandAckPayload(
    val action: CommandAction,
    val acknowledgedAt: Long,
    val runtimeState: RuntimeState,
)

@Serializable
data class CommandAckMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.COMMAND_ACK,
    val deviceId: String,
    val requestId: String,
    val sessionId: String? = null,
    val timestamp: Long,
    val payload: CommandAckPayload,
)

@Serializable
enum class CommandExecutionStatus {
    @SerialName("forwarding_to_glasses")
    FORWARDING_TO_GLASSES,

    @SerialName("waiting_glasses_ack")
    WAITING_GLASSES_ACK,

    @SerialName("executing")
    EXECUTING,

    @SerialName("displaying")
    DISPLAYING,

    @SerialName("capturing")
    CAPTURING,

    @SerialName("image_captured")
    IMAGE_CAPTURED,

    @SerialName("uploading_image")
    UPLOADING_IMAGE,

    @SerialName("image_uploaded")
    IMAGE_UPLOADED,
}

@Serializable
data class CommandStatusImageProgress(
    val imageId: String,
    val transferId: String,
    val uploadStartedAt: Long? = null,
    val uploadedAt: Long? = null,
    val sha256: String? = null,
)

@Serializable(with = CommandStatusPayloadSerializer::class)
sealed interface CommandStatusPayload {
    val action: CommandAction
    val status: CommandExecutionStatus
    val statusAt: Long
    val detailCode: String?
    val detailMessage: String?
}

@Serializable
data class ForwardingToGlassesStatus(
    override val action: CommandAction,
    override val status: CommandExecutionStatus = CommandExecutionStatus.FORWARDING_TO_GLASSES,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : CommandStatusPayload

@Serializable
data class WaitingGlassesAckStatus(
    override val action: CommandAction,
    override val status: CommandExecutionStatus = CommandExecutionStatus.WAITING_GLASSES_ACK,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : CommandStatusPayload

@Serializable
data class ExecutingStatus(
    override val action: CommandAction,
    override val status: CommandExecutionStatus = CommandExecutionStatus.EXECUTING,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : CommandStatusPayload

@Serializable
data class DisplayingStatus(
    override val action: CommandAction = CommandAction.DISPLAY_TEXT,
    override val status: CommandExecutionStatus = CommandExecutionStatus.DISPLAYING,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : CommandStatusPayload

@Serializable
data class CapturingStatus(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val status: CommandExecutionStatus = CommandExecutionStatus.CAPTURING,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
) : CommandStatusPayload

@Serializable
data class ImageCapturedStatus(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val status: CommandExecutionStatus = CommandExecutionStatus.IMAGE_CAPTURED,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
    val image: CommandStatusImageProgress,
) : CommandStatusPayload

@Serializable
data class UploadingImageStatus(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val status: CommandExecutionStatus = CommandExecutionStatus.UPLOADING_IMAGE,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
    val image: CommandStatusImageProgress,
) : CommandStatusPayload

@Serializable
data class ImageUploadedStatus(
    override val action: CommandAction = CommandAction.CAPTURE_PHOTO,
    override val status: CommandExecutionStatus = CommandExecutionStatus.IMAGE_UPLOADED,
    override val statusAt: Long,
    override val detailCode: String? = null,
    override val detailMessage: String? = null,
    val image: CommandStatusImageProgress,
) : CommandStatusPayload

@Serializable
data class CommandStatusMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.COMMAND_STATUS,
    val deviceId: String,
    val requestId: String,
    val sessionId: String? = null,
    val timestamp: Long,
    val payload: CommandStatusPayload,
)

@Serializable
data class CommandResultPayload(
    val completedAt: Long,
    val result: CommandResult,
)

@Serializable
data class CommandResultMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.COMMAND_RESULT,
    val deviceId: String,
    val requestId: String,
    val sessionId: String? = null,
    val timestamp: Long,
    val payload: CommandResultPayload,
)

@Serializable
data class CommandErrorPayload(
    val action: CommandAction,
    val failedAt: Long,
    val error: TerminalError,
)

@Serializable
data class CommandErrorMessage(
    val version: String = RelayProtocolConstants.PROTOCOL_VERSION,
    val type: RelayMessageType = RelayMessageType.COMMAND_ERROR,
    val deviceId: String,
    val requestId: String,
    val sessionId: String? = null,
    val timestamp: Long,
    val payload: CommandErrorPayload,
)

object CommandDispatchPayloadSerializer : JsonContentPolymorphicSerializer<CommandDispatchPayload>(CommandDispatchPayload::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CommandDispatchPayload> = when (element.actionValue()) {
        CommandAction.DISPLAY_TEXT.serialName -> DisplayTextCommandDispatchPayload.serializer()
        CommandAction.CAPTURE_PHOTO.serialName -> CapturePhotoCommandDispatchPayload.serializer()
        else -> throw IllegalArgumentException("Unknown command dispatch action")
    }
}

object CommandStatusPayloadSerializer : JsonContentPolymorphicSerializer<CommandStatusPayload>(CommandStatusPayload::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CommandStatusPayload> = when (element.statusValue()) {
        CommandExecutionStatus.FORWARDING_TO_GLASSES.serialName -> ForwardingToGlassesStatus.serializer()
        CommandExecutionStatus.WAITING_GLASSES_ACK.serialName -> WaitingGlassesAckStatus.serializer()
        CommandExecutionStatus.EXECUTING.serialName -> ExecutingStatus.serializer()
        CommandExecutionStatus.DISPLAYING.serialName -> DisplayingStatus.serializer()
        CommandExecutionStatus.CAPTURING.serialName -> CapturingStatus.serializer()
        CommandExecutionStatus.IMAGE_CAPTURED.serialName -> ImageCapturedStatus.serializer()
        CommandExecutionStatus.UPLOADING_IMAGE.serialName -> UploadingImageStatus.serializer()
        CommandExecutionStatus.IMAGE_UPLOADED.serialName -> ImageUploadedStatus.serializer()
        else -> throw IllegalArgumentException("Unknown command status payload")
    }
}

private fun JsonElement.actionValue(): String = jsonObject["action"]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("Missing action discriminator")

private fun JsonElement.statusValue(): String = jsonObject["status"]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("Missing status discriminator")

private val CommandAction.serialName: String
    get() = when (this) {
        CommandAction.DISPLAY_TEXT -> "display_text"
        CommandAction.CAPTURE_PHOTO -> "capture_photo"
    }

private val CommandExecutionStatus.serialName: String
    get() = when (this) {
        CommandExecutionStatus.FORWARDING_TO_GLASSES -> "forwarding_to_glasses"
        CommandExecutionStatus.WAITING_GLASSES_ACK -> "waiting_glasses_ack"
        CommandExecutionStatus.EXECUTING -> "executing"
        CommandExecutionStatus.DISPLAYING -> "displaying"
        CommandExecutionStatus.CAPTURING -> "capturing"
        CommandExecutionStatus.IMAGE_CAPTURED -> "image_captured"
        CommandExecutionStatus.UPLOADING_IMAGE -> "uploading_image"
        CommandExecutionStatus.IMAGE_UPLOADED -> "image_uploaded"
    }
