package cn.cutemc.rokidmcp.share.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
}

@Serializable
enum class RelaySetupState {
    @SerialName("UNINITIALIZED")
    UNINITIALIZED,

    @SerialName("INITIALIZED")
    INITIALIZED,
}

@Serializable
enum class RelayRuntimeState {
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
enum class RelayUplinkState {
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
data class RelayPhoneInfo(
    val model: String? = null,
)

@Serializable
data class RelayHelloPayload(
    val authToken: String,
    val appVersion: String,
    val phoneInfo: RelayPhoneInfo = RelayPhoneInfo(),
    val setupState: RelaySetupState,
    val runtimeState: RelayRuntimeState,
    val uplinkState: RelayUplinkState,
    val capabilities: List<LocalAction>,
)

@Serializable
data class RelayHelloMessage(
    val version: String,
    val type: RelayMessageType = RelayMessageType.HELLO,
    val deviceId: String,
    val timestamp: Long,
    val payload: RelayHelloPayload,
)

@Serializable
data class RelayLimits(
    val maxPendingCommands: Int? = null,
    val maxImageUploadSizeBytes: Long? = null,
    val acceptedImageContentTypes: List<String> = emptyList(),
)

@Serializable
data class RelayHelloAckPayload(
    val sessionId: String? = null,
    val serverTime: Long? = null,
    val heartbeatIntervalMs: Long? = null,
    val heartbeatTimeoutMs: Long? = null,
    val limits: RelayLimits? = null,
)

@Serializable
data class RelayHelloAckMessage(
    val version: String,
    val type: RelayMessageType = RelayMessageType.HELLO_ACK,
    val deviceId: String,
    val timestamp: Long,
    val payload: RelayHelloAckPayload,
)

@Serializable
data class RelayHeartbeatPayload(
    val seq: Long,
    val runtimeState: RelayRuntimeState,
    val uplinkState: RelayUplinkState,
    val pendingCommandCount: Int,
    val activeCommandRequestId: String? = null,
)

@Serializable
data class RelayHeartbeatMessage(
    val version: String,
    val type: RelayMessageType = RelayMessageType.HEARTBEAT,
    val deviceId: String,
    val sessionId: String,
    val timestamp: Long,
    val payload: RelayHeartbeatPayload,
)

@Serializable
data class RelayPhoneStateUpdatePayload(
    val setupState: RelaySetupState,
    val runtimeState: RelayRuntimeState,
    val uplinkState: RelayUplinkState,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val activeCommandRequestId: String? = null,
)

@Serializable
data class RelayPhoneStateUpdateMessage(
    val version: String,
    val type: RelayMessageType = RelayMessageType.PHONE_STATE_UPDATE,
    val deviceId: String,
    val sessionId: String,
    val timestamp: Long,
    val payload: RelayPhoneStateUpdatePayload,
)
