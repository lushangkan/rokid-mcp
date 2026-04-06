# Phone-Glasses Protocol Schemas

## 1. 目标

本文档定义 MVP 阶段 `Phone <-> Glasses` 本地链路协议的完整 schema，用于指导：

- `apps/phone-android/`
- `apps/glasses-android/`

直接落地 Kotlin `enum class`、`data class`、codec、校验器与协议适配层。

## 2. 依赖文档

本文档依赖以下文档：

- `docs/phone-glasses-protocol-constants.md`
- `docs/mvp-technical-implementation.md`
- `docs/relay-protocol-constants.md`
- `docs/relay-protocol-schemas.md`

如果本文件与常量表冲突，以常量表为准。

## 3. 传输与编码约定

### 3.1 传输约定

- 本地链路使用 Bluetooth Classic RFCOMM Socket
- 控制消息与图片数据复用同一条 socket
- RFCOMM 是字节流，必须使用自定义分帧格式
- 图片不做 base64 编码，`chunk_data.body` 直接传原始二进制

### 3.2 分帧格式

```text
[magic: uint32][headerLength: uint32][bodyLength: uint32][headerCrc32: uint32][headerJson UTF-8][body bytes]
```

字段说明：

| 字段 | 类型 | 说明 |
| - | - | - |
| `magic` | `uint32` | 固定为 `FRAME_MAGIC` |
| `headerLength` | `uint32` | `headerJson` 字节长度 |
| `bodyLength` | `uint32` | `body bytes` 字节长度 |
| `headerCrc32` | `uint32` | `headerJson` 的 CRC32 |
| `headerJson` | UTF-8 JSON | 消息头 |
| `body bytes` | raw bytes | 仅 `chunk_data` 使用 |

### 3.3 分帧校验顺序

接收侧必须按以下顺序校验：

1. 校验 `magic`
2. 校验 `headerLength <= FRAME_HEADER_MAX_BYTES`
3. 校验 `bodyLength` 是否在允许范围内
4. 按长度读取 `headerJson` 与 `body bytes`
5. 校验 `headerCrc32`
6. 解析 `headerJson`
7. 对 `chunk_data` 校验 `payload.size == bodyLength`
8. 对文件传输校验 `chunkChecksum`
9. 在 `chunk_end` 后校验整文件 `sha256`

如果任一步失败：

- 该 command 失败
- 当前 session 视错误类型决定是否关闭

### 3.4 JSON 编码约定

- wire format 中所有字段使用 camelCase
- Kotlin 侧使用 `enum class` + serializer 映射到协议字符串值
- 未声明可空的字段，不允许传 `null`
- 未声明可选的字段，必须存在

## 4. Kotlin 基础类型建议

```kotlin
object LocalProtocolConstants {
    const val PROTOCOL_VERSION = "1.0"
    const val PROTOCOL_NAME = "rokid-local-link"
    const val FRAME_MAGIC = 0x524B4C31
    const val FRAME_HEADER_MAX_BYTES = 8 * 1024
    const val CHUNK_SIZE_BYTES = 16 * 1024
    const val MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
    const val IMAGE_MIME_TYPE_JPEG = "image/jpeg"
}

enum class LocalMessageType {
    HELLO,
    HELLO_ACK,
    PING,
    PONG,
    COMMAND,
    COMMAND_ACK,
    COMMAND_STATUS,
    COMMAND_RESULT,
    COMMAND_ERROR,
    CHUNK_START,
    CHUNK_DATA,
    CHUNK_END,
}

enum class LocalAction {
    DISPLAY_TEXT,
    CAPTURE_PHOTO,
}

enum class LinkRole {
    PHONE,
    GLASSES,
}

enum class LocalRuntimeState {
    READY,
    BUSY,
    ERROR,
}

enum class LocalCommandStatus {
    EXECUTING,
    DISPLAYING,
    CAPTURING,
}

data class LocalFrameHeader<T : Any>(
    val version: String = LocalProtocolConstants.PROTOCOL_VERSION,
    val type: LocalMessageType,
    val requestId: String? = null,
    val transferId: String? = null,
    val timestamp: Long,
    val payload: T,
)
```

## 5. Header 通用校验规则

| 字段 | 规则 |
| - | - |
| `version` | 必须等于 `1.0` |
| `type` | 必须属于已定义消息类型 |
| `requestId` | 命令与 chunk 相关消息必填 |
| `transferId` | 仅 `chunk_*` 必填 |
| `timestamp` | 必须是正整数毫秒时间戳 |
| `payload` | 必须为 object |

## 6. 握手与保活消息

## 6.1 `hello`

用途：Phone 在 socket 建立后声明身份与能力。

```kotlin
data class HelloPayload(
    val protocolName: String = LocalProtocolConstants.PROTOCOL_NAME,
    val role: LinkRole = LinkRole.PHONE,
    val deviceId: String,
    val appVersion: String,
    val appBuild: String? = null,
    val supportedActions: List<LocalAction>,
)

typealias HelloMessage = LocalFrameHeader<HelloPayload>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.protocolName` | 是 | 固定为 `rokid-local-link` |
| `payload.role` | 是 | 固定为 `PHONE` |
| `payload.deviceId` | 是 | 沿用 Phone 在 Relay 侧的设备标识 |
| `payload.appVersion` | 是 | Phone App 版本 |
| `payload.appBuild` | 否 | 构建号 |
| `payload.supportedActions` | 是 | 当前愿意桥接的动作列表 |

## 6.2 `hello_ack`

用途：Glasses 确认是否接受当前会话。

```kotlin
data class HelloAckPayload(
    val accepted: Boolean,
    val role: LinkRole = LinkRole.GLASSES,
    val glassesInfo: GlassesInfo? = null,
    val capabilities: List<LocalAction>? = null,
    val runtimeState: LocalRuntimeState? = null,
    val error: HelloError? = null,
)

data class GlassesInfo(
    val model: String? = null,
    val appVersion: String,
)

data class HelloError(
    val code: String,
    val message: String,
)

typealias HelloAckMessage = LocalFrameHeader<HelloAckPayload>
```

约束：

- `accepted = true` 时，`error` 不应存在
- `accepted = false` 时，`error` 必填
- `accepted = false` 后，Glasses 应主动关闭当前会话

## 6.3 `ping`

用途：Phone 在空闲时发起保活。

```kotlin
data class PingPayload(
    val seq: Long,
    val nonce: String,
)

typealias PingMessage = LocalFrameHeader<PingPayload>
```

## 6.4 `pong`

用途：Glasses 对 `ping` 的立即应答。

```kotlin
data class PongPayload(
    val seq: Long,
    val nonce: String,
)

typealias PongMessage = LocalFrameHeader<PongPayload>
```

约束：

- `seq` 必须与对应 `ping` 一致
- `nonce` 必须原样回显

## 7. 命令消息

## 7.1 `command`

用途：Phone 向 Glasses 下发业务命令。

### 7.1.1 `display_text`

```kotlin
data class DisplayTextCommandPayload(
    val action: LocalAction = LocalAction.DISPLAY_TEXT,
    val timeoutMs: Long,
    val params: DisplayTextParams,
)

data class DisplayTextParams(
    val text: String,
    val durationMs: Long,
    val priority: String? = null,
)

typealias LocalDisplayTextCommandMessage = LocalFrameHeader<DisplayTextCommandPayload>
```

### 7.1.2 `capture_photo`

```kotlin
data class CapturePhotoCommandPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val timeoutMs: Long,
    val params: CapturePhotoParams,
    val transfer: CaptureTransfer,
)

data class CapturePhotoParams(
    val quality: String? = null,
)

data class CaptureTransfer(
    val transferId: String,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val maxBytes: Long,
)

typealias LocalCapturePhotoCommandMessage = LocalFrameHeader<CapturePhotoCommandPayload>
```

共同约束：

- `requestId` 必填
- `timeoutMs` 表示本地执行预算
- `capture_photo.transfer.transferId` 必填
- `capture_photo.transfer.maxBytes` 不得超过 `MAX_IMAGE_SIZE_BYTES`

## 7.2 `command_ack`

用途：Glasses 接受执行命令。

```kotlin
data class CommandAckPayload(
    val action: LocalAction,
    val acceptedAt: Long,
    val runtimeState: LocalRuntimeState,
)

typealias LocalCommandAckMessage = LocalFrameHeader<CommandAckPayload>
```

约束：

- 只在命令被接受时发送
- `capture_photo` 一般返回 `runtimeState = BUSY`
- 若不能接受命令，应直接发送 `command_error`

## 7.3 `command_status`

用途：Glasses 上报本地执行中状态。

```kotlin
data class CommandStatusPayload(
    val action: LocalAction,
    val status: LocalCommandStatus,
    val statusAt: Long,
    val detailCode: String? = null,
    val detailMessage: String? = null,
)

typealias LocalCommandStatusMessage = LocalFrameHeader<CommandStatusPayload>
```

约束：

- `DISPLAYING` 仅适用于 `DISPLAY_TEXT`
- `CAPTURING` 仅适用于 `CAPTURE_PHOTO`

## 7.4 `command_result`

用途：Glasses 上报命令本地成功完成。

### 7.4.1 `display_text`

```kotlin
data class DisplayTextCommandResultPayload(
    val action: LocalAction = LocalAction.DISPLAY_TEXT,
    val completedAt: Long,
    val result: DisplayTextResult,
)

data class DisplayTextResult(
    val displayed: Boolean = true,
    val durationMs: Long,
)

typealias LocalDisplayTextCommandResultMessage = LocalFrameHeader<DisplayTextCommandResultPayload>
```

### 7.4.2 `capture_photo`

```kotlin
data class CapturePhotoCommandResultPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val completedAt: Long,
    val result: CapturePhotoResult,
)

data class CapturePhotoResult(
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val size: Long,
    val width: Int,
    val height: Int,
    val sha256: String? = null,
)

typealias LocalCapturePhotoCommandResultMessage = LocalFrameHeader<CapturePhotoCommandResultPayload>
```

补充约束：

- 本地 `capture_photo` 结果不带 `imageId`
- 对 Glasses 来说，本地成功表示“图片已完整交付给 Phone”
- 不表示“Phone 已上传到 Relay”

## 7.5 `command_error`

用途：Glasses 上报命令失败。

```kotlin
data class CommandErrorPayload(
    val action: LocalAction,
    val failedAt: Long,
    val error: CommandError,
)

data class CommandError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: Map<String, Any>? = null,
)

typealias LocalCommandErrorMessage = LocalFrameHeader<CommandErrorPayload>
```

## 8. 图片分块消息

`chunk_*` 只用于 `capture_photo`。

## 8.1 `chunk_start`

用途：Glasses 声明将开始发送图片。

```kotlin
data class ChunkStartPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val totalSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val sha256: String? = null,
)

typealias ChunkStartMessage = LocalFrameHeader<ChunkStartPayload>
```

约束：

- `requestId` 必填
- `transferId` 必填
- `totalSize > 0`
- `totalSize <= MAX_IMAGE_SIZE_BYTES`

## 8.2 `chunk_data`

用途：Glasses 发送单个图片数据块。

```kotlin
data class ChunkDataPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val index: Int,
    val offset: Long,
    val size: Int,
    val chunkChecksum: String,
)

typealias ChunkDataMessage = LocalFrameHeader<ChunkDataPayload>
```

Body 规则：

- `body bytes` 是原始图片块内容
- `payload.size == bodyLength`
- `payload.index` 从 `0` 开始单调递增
- `payload.offset` 必须与累计写入长度一致
- `payload.chunkChecksum` 固定使用 `CRC32`

## 8.3 `chunk_end`

用途：Glasses 声明本次图片数据发送结束。

```kotlin
data class ChunkEndPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val totalChunks: Int,
    val totalSize: Long,
    val sha256: String? = null,
)

typealias ChunkEndMessage = LocalFrameHeader<ChunkEndPayload>
```

接收侧要求：

- chunk 全部落盘后再校验 `sha256`
- 校验失败即认为该 command 失败

## 9. Session 级事件建议

本地链路不建议使用全局 EventBus，推荐每个核心模块暴露自己的 `SharedFlow<SealedEvent>`。

### 9.1 Phone Session Event

```kotlin
sealed interface PhoneSessionEvent {
    data class HelloAccepted(val payload: HelloAckPayload) : PhoneSessionEvent
    data class PongReceived(val seq: Long) : PhoneSessionEvent
    data class CommandAckReceived(val message: LocalCommandAckMessage) : PhoneSessionEvent
    data class CommandStatusReceived(val message: LocalCommandStatusMessage) : PhoneSessionEvent
    data class CommandResultReceived(val message: LocalFrameHeader<*>) : PhoneSessionEvent
    data class CommandErrorReceived(val message: LocalCommandErrorMessage) : PhoneSessionEvent
    data class ChunkStartReceived(val message: ChunkStartMessage) : PhoneSessionEvent
    data class ChunkDataReceived(val message: ChunkDataMessage, val body: ByteArray) : PhoneSessionEvent
    data class ChunkEndReceived(val message: ChunkEndMessage) : PhoneSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : PhoneSessionEvent
    data class SessionClosed(val reason: String) : PhoneSessionEvent
}
```

### 9.2 Glasses Session Event

```kotlin
sealed interface GlassesSessionEvent {
    data class HelloReceived(val message: HelloMessage) : GlassesSessionEvent
    data class PingReceived(val message: PingMessage) : GlassesSessionEvent
    data class CommandReceived(val message: LocalFrameHeader<*>) : GlassesSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : GlassesSessionEvent
    data class SessionClosed(val reason: String) : GlassesSessionEvent
}
```

## 10. Command 级错误收口规则

冻结以下规则：

- Phone 端最终命令失败收口者是 `RelayCommandBridge`
- Glasses 端最终命令失败收口者是 `CommandDispatcher`
- 其他模块只做局部 cleanup，不直接做最终 command 失败收口

## 11. 下一步

建议继续维护：

1. `docs/phone-glasses-protocol-sequences.md`
2. `docs/phone-glasses-module-design.md`
