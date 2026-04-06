# Phone-Glasses Protocol Draft

> Note
>
> This draft has been superseded by the finalized document set:
>
> - `docs/phone-glasses-protocol-constants.md`
> - `docs/phone-glasses-protocol-schemas.md`
> - `docs/phone-glasses-protocol-sequences.md`
> - `docs/phone-glasses-module-design.md`

## 1. 目标

本文档给出 MVP 阶段 `Phone <-> Glasses` 本地链路协议草稿，用于补齐以下链路：

- Phone -> Glasses: 命令下发
- Glasses -> Phone: 命令确认、执行状态、结果返回
- Glasses -> Phone: 拍照图片二进制传输
- Phone <-> Glasses: 链路保活、失活判定与重连约定

本文档的目标不是直接复刻 `Phone <-> Relay` 协议，而是定义一套更适合 Bluetooth Classic RFCOMM 的本地协议，同时尽量与 Relay 侧语义保持一致，降低手机端桥接复杂度。

## 2. 依赖文档

本文档依赖以下已有设计：

- `docs/mvp-technical-implementation.md`
- `docs/relay-protocol-constants.md`
- `docs/relay-protocol-schemas.md`
- `docs/relay-protocol-sequences.md`

如果本文件与 Relay 协议文档发生冲突，以 Relay 协议文档中跨端语义为准；如果是本地链路专有字段或传输形式，以本文件为准。

## 3. 范围与非目标

### 3.1 本文覆盖范围

- Bluetooth Classic RFCOMM 长连接
- Phone 作为客户端主动连接，Glasses 作为服务端监听
- 单连接、单命令串行执行
- `display_text` 与 `capture_photo` 两个动作
- 图片通过蓝牙分块从 Glasses 回传到 Phone
- 空闲保活、超时判定、简单重连策略

### 3.2 本文暂不覆盖

- BLE / GATT 协议设计
- 多命令并发复用
- 多文件并发传输
- 断点续传
- 后台长时间保活优化
- 生产级认证与端到端加密增强
- 视频、音频、流式预览等大流量媒体能力

## 4. 设计原则

### 4.1 语义对齐 Relay，传输适配 RFCOMM

本地协议不要求与 Relay WS schema 完全同构，但应保留以下核心语义：

- 所有命令相关消息带 `requestId`
- 成功只通过 `command_result` 表达
- 失败只通过 `command_error` 表达
- 执行中状态通过 `command_status` 表达
- 图片二进制不放进 JSON，而通过独立 chunk 消息传输

### 4.2 Phone 是控制枢纽，Glasses 是执行器

- Phone 负责蓝牙连接管理、命令桥接、上传 Relay、错误收口
- Glasses 负责执行命令、拍照、显示文本、将本地结果回给 Phone
- Relay 侧看到的某些中间态是 Phone 合成的，不应强制要求 Glasses 直接感知

例如：

- `forwarding_to_glasses`
- `waiting_glasses_ack`
- `uploading_image`
- `image_uploaded`

这些更适合由 Phone 自己维护后映射给 Relay。

### 4.3 MVP 先简单可靠

- 单 socket
- 单 active command
- 单文件传输
- 不做 `chunk_ack`
- 不做断点续传
- 传输失败时整次命令失败

RFCOMM 本身是可靠有序字节流，MVP 不需要再叠一层复杂重传协议。

### 4.4 Kotlin 类型约定

MVP 阶段不额外追求前向兼容；如果协议闭集字段发生变动，默认 Phone / Glasses / Relay 相关模块应一起升级。

因此本地协议在 Kotlin 侧建议采用以下约定：

- 闭集字段使用 `enum class`
- 固定协议常量使用 `const val`
- 仅对开放文本字段继续使用 `String`

适合使用 `enum class` 的字段包括：

- 消息类型 `type`
- 动作类型 `action`
- 运行状态 `runtimeState`
- 命令阶段状态 `status`

适合使用 `String` 的字段包括：

- `deviceId`
- `appVersion`
- `detailCode`
- `detailMessage`
- `error.message`

## 5. 连接角色与生命周期

### 5.1 连接角色

推荐连接模型：

- Glasses：启动 `BluetoothServerSocket`，在前台运行时持续监听
- Phone：保存目标眼镜蓝牙地址，作为 RFCOMM client 主动发起连接
- Glasses：只接受系统中已经完成 pair 的蓝牙设备连接，不接受未配对设备

原因：

- Phone 侧负责配对管理与目标设备选择
- Phone 侧状态机已承担 `DISCONNECTED -> CONNECTING -> READY`
- 更符合“Phone 管理链路，Glasses 暴露本地能力”的职责划分

### 5.2 MVP 会话流程

```text
1. Phone 建立 RFCOMM Socket
2. Phone 发送 hello
3. Glasses 返回 hello_ack
4. Phone 进入 READY
5. 链路空闲时，Phone 按固定间隔发送 ping，Glasses 立即返回 pong
6. 任意正常 frame 收发都会刷新 lastSeenAt，不重复额外发心跳
7. 双方进入命令执行阶段时，业务消息本身就视为保活流量
8. 链路失活时由 Phone 主动断开并重连
```

### 5.3 单连接约束

MVP 建议只允许一条有效本地会话：

- 同一时刻 Glasses 仅接受一个 Phone 连接
- 如果出现重复连接，新连接应拒绝或顶掉旧连接，二者只能保留一个
- Phone 侧只维护一个 `targetGlasses`
- Glasses 应优先校验远端设备是否已 pair，再进入协议层 `hello / hello_ack`

## 6. 传输层设计

### 6.1 传输通道

- 使用 Bluetooth Classic RFCOMM Socket
- 使用长连接模型
- 控制消息与图片 chunk 复用同一条 socket

### 6.2 分帧格式

RFCOMM 提供的是字节流，不提供天然消息边界，因此本地协议必须自定义 frame 格式。

推荐格式：

```text
[magic: uint32][headerLength: uint32][bodyLength: uint32][headerCrc32: uint32][headerJson UTF-8][body bytes]
```

字段说明：

| 字段 | 类型 | 说明 |
| - | - | - |
| `magic` | `uint32` | 固定魔数，用于快速校验流是否对齐 |
| `headerLength` | `uint32` | `headerJson` 的字节长度 |
| `bodyLength` | `uint32` | `body bytes` 的字节长度 |
| `headerCrc32` | `uint32` | `headerJson` 的 CRC32 |
| `headerJson` | UTF-8 JSON | 统一消息头，包含 `type`、`requestId`、`payload` |
| `body bytes` | raw bytes | 仅 `chunk_data` 使用；控制消息时固定为空 |

补充约束：

- 字节序建议使用 big-endian
- `magic` 建议固定为 `0x524B4C31`，即 `RKL1`
- `headerLength` 建议限制在 `8 KB` 以内
- `bodyLength` 在控制消息时必须为 `0`
- `chunk_data` 的 `bodyLength` 必须大于 `0`
- 如果读取到非法长度，应直接视为协议错误并断链
- 读取 `headerJson` 后必须重新计算 CRC32，并与 `headerCrc32` 比较
- 任一长度校验或头部 CRC 校验失败，都应丢弃当前会话，避免继续在错位流上读取

### 6.3 分层约定

在本地链路中，消息分成两类：

1. 控制消息：只有 `headerJson`，`bodyLength = 0`
2. 数据消息：`chunk_data`，由 `headerJson + body bytes` 共同构成

### 6.4 帧级安全校验建议

为防止长度字段异常、流错位、越界申请内存、误把错误字节当作下一帧继续读取，接收侧建议按以下顺序校验：

1. 先校验 `magic`
2. 再校验 `headerLength`、`bodyLength` 是否在允许范围内
3. 再按长度读取 `headerJson` 与 `body bytes`
4. 校验 `headerCrc32`
5. 解析 `headerJson` 后继续做字段级校验
6. 对 `chunk_data` 额外校验 `payload.size == bodyLength`
7. 对文件传输额外校验 `offset + size <= totalSize`
8. 在文件传输层按 chunk 校验 `chunkChecksum`
9. 在文件结束后按整文件校验 `sha256`

如果任一步失败：

- 当前命令失败
- 当前 socket 直接关闭
- 临时文件立即删除
- Phone 走统一重连流程

### 6.5 推荐默认值与常量

| 项目 | 推荐值 | 说明 |
| - | - | - |
| `frameMagic` | `0x524B4C31` | 固定帧魔数 |
| `chunkSizeBytes` | `16384` bytes | 固定 chunk 大小，最终一块可小于该值 |
| `maxHeaderBytes` | `8192` | 防止异常头部撑爆内存 |
| `maxImageSizeBytes` | `10485760` | MVP 先按 10 MB 控制 |
| `helloAckTimeoutMs` | `5000` | 建链握手超时 |
| `pingIntervalMs` | `5000` | 空闲心跳间隔 |
| `pongTimeoutMs` | `5000` | 单次 pong 等待超时 |
| `idleTimeoutMs` | `15000` | 判定链路失活的总超时 |

## 7. 通用消息头

以下 schema 示例统一使用 Kotlin `enum class`、`data class`、`typealias` 表达，便于直接映射到 Android 端实现。

### 7.1 通用 Header schema

```kotlin
object LocalProtocolConstants {
    const val PROTOCOL_VERSION = "1.0"
    const val PROTOCOL_NAME = "rokid-local-link"
    const val FRAME_MAGIC = 0x524B4C31
    const val CHUNK_SIZE_BYTES = 16 * 1024
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

enum class LocalRuntimeState {
    READY,
    BUSY,
    ERROR,
}

enum class LinkRole {
    PHONE,
    GLASSES,
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

说明：

- Kotlin 示例中的 enum 使用类型安全命名
- 实际 wire value 建议通过 serializer 或 mapper 映射成协议字符串，例如 `DISPLAY_TEXT -> display_text`

### 7.2 Header 通用校验规则

| 字段 | 规则 |
| - | - |
| `version` | 当前固定为 `1.0` |
| `type` | 必须属于已定义本地消息类型 |
| `requestId` | 命令相关消息必填；握手与保活消息不填 |
| `transferId` | 仅图片 chunk 相关消息必填 |
| `timestamp` | 必须是正整数毫秒时间戳 |
| `payload` | 必须是 object，不允许为 `null` |

### 7.3 `requestId` 必填消息

- `command`
- `command_ack`
- `command_status`
- `command_result`
- `command_error`
- `chunk_start`
- `chunk_data`
- `chunk_end`

## 8. 握手与保活消息

## 8.1 `hello`

用途：Phone 在 socket 建立后声明协议版本、角色与支持动作。

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

说明：

- `deviceId` 沿用 Phone 在 Relay 侧的设备标识，便于日志串联
- `supportedActions` 代表 Phone 愿意桥接的动作能力
- 心跳间隔、chunk 大小、最大头长度使用文档固定常量，不在握手里协商

## 8.2 `hello_ack`

用途：Glasses 确认是否接受当前连接与协议版本。

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
- `accepted = false` 时，`error` 必填，随后建议主动断开连接
- Phone 只有收到 `accepted = true` 的 `hello_ack` 后，才能进入 `READY`

## 8.3 `ping`

用途：Phone 在空闲时检测链路是否仍然健康。

```kotlin
data class PingPayload(
    val seq: Long,
    val nonce: String,
)

typealias PingMessage = LocalFrameHeader<PingPayload>
```

## 8.4 `pong`

用途：Glasses 对 `ping` 的立即响应。

```kotlin
data class PongPayload(
    val seq: Long,
    val nonce: String,
)

typealias PongMessage = LocalFrameHeader<PongPayload>
```

约束：

- `pong.payload.seq` 必须与对应 `ping` 一致
- `pong.payload.nonce` 必须原样回显
- 任何一方收发任意 frame，均应刷新本地 `lastSeenAt`

## 9. 命令消息

## 9.1 `command`

用途：Phone 向 Glasses 下发业务命令。

该消息是 discriminated union，依赖 `payload.action`。

### 9.1.1 `display_text`

```kotlin
data class DisplayTextCommandPayload(
    val action: LocalAction = LocalAction.DISPLAY_TEXT,
    val timeoutMs: Long,
    val params: DisplayTextParams,
)

data class DisplayTextParams(
    val text: String,
    val durationMs: Long,
    val priority: String? = null, // normal | high
)

typealias LocalDisplayTextCommandMessage = LocalFrameHeader<DisplayTextCommandPayload>
```

### 9.1.2 `capture_photo`

```kotlin
data class CapturePhotoCommandPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val timeoutMs: Long,
    val params: CapturePhotoParams,
    val transfer: CaptureTransfer,
)

data class CapturePhotoParams(
    val quality: String? = null, // low | medium | high
)

data class CaptureTransfer(
    val transferId: String,
    val mediaType: String = LocalProtocolConstants.IMAGE_MIME_TYPE_JPEG,
    val maxBytes: Long,
)

typealias LocalCapturePhotoCommandMessage = LocalFrameHeader<CapturePhotoCommandPayload>
```

共同字段说明：

| 字段 | 必填 | 说明 |
| - | - | - |
| `requestId` | 是 | 本次命令唯一标识 |
| `payload.action` | 是 | 业务动作 |
| `payload.timeoutMs` | 是 | Glasses 本地执行超时预算 |
| `payload.params` | 是 | 动作参数 |
| `payload.transfer` | `capture_photo` 时必填 | 图片回传约定 |

补充约束：

- `payload.timeoutMs` 是本地执行预算，建议覆盖“命令执行 + 图片传回 Phone”阶段
- Phone 如果该命令来自 Relay，应为后续上传 Relay 预留一部分时间，不应把总超时全部透传给 Glasses
- chunk 大小固定使用 `chunkSizeBytes = 16384`，不在命令里重复协商

## 9.2 `command_ack`

用途：Glasses 确认已接收并接受执行命令。

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
- 如果 Glasses 不能接受命令，应直接发送 `command_error`
- `capture_photo` 一般返回 `runtimeState = BUSY`

## 9.3 `command_status`

用途：Glasses 上报本地执行阶段状态。

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

说明：

- 本地协议只保留真正属于 Glasses 执行态的状态
- `forwarding_to_glasses`、`waiting_glasses_ack` 不由 Glasses 上报，而由 Phone 自己维护
- `uploading_image`、`image_uploaded` 属于 Phone -> Relay 阶段，也不由 Glasses 上报

## 9.4 `command_result`

用途：Glasses 上报命令本地成功完成。

### 9.4.1 `display_text`

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

### 9.4.2 `capture_photo`

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

- 本地 `capture_photo` 结果中不要求带 `imageId`
- `imageId` 是 Relay 资源标识，由 Phone 在桥接到 Relay 时维护
- 对 Glasses 来说，`capture_photo` 成功意味着“图片已成功传给 Phone”，不代表“Phone 已上传到 Relay”

## 9.5 `command_error`

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

错误码建议优先复用：

- `PROTOCOL_*`
- `BLUETOOTH_*`
- `CAMERA_*`
- `DISPLAY_*`
- `COMMAND_*`

必要时再补充少量本地链路专用错误码，例如：

- `PROTOCOL_FRAME_TOO_LARGE`
- `PROTOCOL_HEADER_INVALID`
- `IMAGE_TRANSFER_INCOMPLETE`
- `IMAGE_CHECKSUM_MISMATCH`
- `IMAGE_STORAGE_WRITE_FAILED`

## 10. 图片分块消息

`chunk_*` 只用于 `capture_photo` 的图片回传。

## 10.1 `chunk_start`

用途：Glasses 声明即将开始发送图片数据。

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
- `payload.totalSize` 必须大于 `0`
- 每个 chunk 除最后一块外都必须等于 `chunkSizeBytes = 16384`

## 10.2 `chunk_data`

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

- `body bytes` 为当前 chunk 的原始二进制内容
- `bodyLength` 必须等于 `payload.size`
- `payload.index` 从 `0` 开始单调递增
- `payload.offset` 必须与已累计写入长度一致
- `payload.chunkChecksum` 用于 chunk 级校验，和整文件 `sha256` 分层处理

## 10.3 `chunk_end`

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

Phone 接收侧要求：

- 将 `chunk_data.body` 顺序写入临时文件
- 每个 chunk 写入前先校验 `chunkChecksum`
- 接收完成后校验总长度
- 若 `sha256` 存在，则应校验内容哈希
- 任一校验失败，整次命令失败
- 写文件前必须再次检查 `offset`、`size`、累计长度，避免越界写入或错误覆盖

## 11. 时序草案

## 11.1 首次连接与握手

```text
Phone -> Glasses: connect RFCOMM socket
Phone -> Glasses: hello
Glasses -> Phone: hello_ack(accepted=true)
Phone: local runtimeState = READY
```

失败分支：

- 若 `hello` 超时未收到 `hello_ack`，Phone 关闭 socket 并重连
- 若 `hello_ack.accepted = false`，Phone 记录错误并断链

## 11.2 `display_text` 主成功链路

```text
Phone -> Relay: command_status(forwarding_to_glasses)
Phone -> Glasses: command(display_text)
Phone -> Relay: command_status(waiting_glasses_ack)
Glasses -> Phone: command_ack
Phone -> Relay: command_ack
Glasses -> Phone: command_status(executing)
Phone -> Relay: command_status(executing)
Glasses -> Phone: command_status(displaying)
Phone -> Relay: command_status(displaying)
Glasses -> Phone: command_result(display_text)
Phone -> Relay: command_result(display_text)
```

## 11.3 `capture_photo` 主成功链路

```text
Phone -> Relay: command_status(forwarding_to_glasses)
Phone -> Glasses: command(capture_photo)
Phone -> Relay: command_status(waiting_glasses_ack)
Glasses -> Phone: command_ack
Phone -> Relay: command_ack
Glasses -> Phone: command_status(capturing)
Phone -> Relay: command_status(capturing)
Glasses -> Phone: chunk_start
Glasses -> Phone: chunk_data * N
Glasses -> Phone: chunk_end
Glasses -> Phone: command_result(capture_photo)
Phone: 完成临时文件落盘并做最终校验
Phone -> Relay: command_status(image_captured)
Phone -> Relay: command_status(uploading_image)
Phone -> Relay: HTTP PUT image
Relay -> Phone: upload success
Phone -> Relay: command_status(image_uploaded)
Phone -> Relay: command_result(capture_photo)
```

关键说明：

- `chunk_start -> chunk_end` 完成且文件校验通过，Phone 才应向 Relay 发 `image_captured`
- Glasses 的本地 `command_result(capture_photo)` 可以先到达 Phone，但 Phone 不应立刻转发为 Relay `command_result`
- Phone 必须等图片上传 Relay 成功后，再用已缓存的本地结果收口为 Relay `command_result`
- 如果上传 Relay 失败，Phone 应立即删除本地临时图片文件，不做保留或延迟重试

为什么把 `command_result(capture_photo)` 放在 `chunk_end` 之后：

- 这样 `command_result` 的语义更稳定，表示“Glasses 已完成拍照，并且图片已经完整交付给 Phone”
- 如果先发 `command_result`，再传文件，Phone 会先看到成功，再看到传输失败，语义容易打架
- 先传文件再给结果，Phone 可以在收到结果前完成长度与校验判断，错误收口更简单
- 对 MVP 来说，这样最利于实现，不需要再引入“结果已成功但附件尚未送达”的中间态

## 12. Phone 到 Relay 的映射建议

为保持 Relay 侧状态机不变，Phone 需要把本地链路事件映射成 Relay 协议事件。

| 本地事件 | Relay 侧输出 |
| - | - |
| Phone 开始向 socket 写 `command` | `command_status(forwarding_to_glasses)` |
| `command` frame 已完整写出 | `command_status(waiting_glasses_ack)` |
| 收到本地 `command_ack` | Relay `command_ack` |
| 收到本地 `command_status(executing)` | Relay `command_status(executing)` |
| 收到本地 `command_status(displaying)` | Relay `command_status(displaying)` |
| 收到本地 `command_status(capturing)` | Relay `command_status(capturing)` |
| 本地图片 chunk 全部收完且校验成功 | Relay `command_status(image_captured)` |
| 开始上传图片到 Relay | Relay `command_status(uploading_image)` |
| 图片上传成功 | Relay `command_status(image_uploaded)` |
| 本地 `display_text` 成功 | Relay `command_result(display_text)` |
| 本地 `capture_photo` 成功且上传已完成 | Relay `command_result(capture_photo)` |
| 本地 `command_error` | Relay `command_error` |
| 蓝牙链路中断且存在活动命令 | Relay `command_error(code = BLUETOOTH_DISCONNECTED)` |

## 13. 保活、失活判定与重连

本地 `ping / pong` 虽然走同一套 frame 编码，但职责上属于协议会话层，不属于底层 RFCOMM 连接层。

建议分工：

- 蓝牙连接层负责 socket 建立、字节流读写、断链与重连
- 协议会话层负责 `hello / hello_ack / ping / pong / lastSeenAt / idle timer`
- 这样后续如果 `ping / pong` 需要携带额外诊断字段，也无需污染底层连接实现

### 13.1 活跃流量定义

以下任意事件都视为链路活跃：

- 收到任意 frame
- 发出任意 frame
- 处于 `chunk_data` 连续传输阶段

因此，只有在链路空闲时才需要显式发送 `ping`。

### 13.2 推荐策略

| 项目 | 推荐值 | 说明 |
| - | - | - |
| 空闲发 `ping` 间隔 | `5000 ms` | 5 秒没有任何流量后由 Phone 发送 |
| `pong` 等待超时 | `5000 ms` | 单次 `ping` 等待 `pong` 的时间 |
| 链路失活阈值 | `15000 ms` | 连续 15 秒未收到 Glasses 任意 frame |
| 最大连续丢失 `pong` 次数 | `3` | 超过后判定链路失活 |

### 13.3 失活后处理

当满足以下任一条件时，Phone 认为链路失活：

- 15 秒未收到 Glasses 任意 frame
- 连续 3 次 `ping` 未收到匹配 `pong`
- socket 读写抛出异常

处理建议：

1. Phone 主动关闭当前 socket
2. 本地状态切回 `DISCONNECTED`
3. 若有活动命令，向 Relay 发送 `command_error(code = BLUETOOTH_DISCONNECTED)`
4. 按退避策略重连

### 13.4 重连退避建议

```text
1s -> 2s -> 5s -> 10s -> 10s ...
```

MVP 建议：

- 重连成功后重新执行 `hello / hello_ack`
- 不做旧命令恢复
- 不做图片断点续传
- 链路中断时正在执行的命令直接失败收口

## 14. 本地状态机建议

### 14.1 Phone 侧蓝牙连接状态

```text
SOCKET_DISCONNECTED
  -> SOCKET_CONNECTING
  -> SOCKET_CONNECTED
  -> SOCKET_STALE
  -> SOCKET_ERROR
```

### 14.2 Phone 侧协议会话状态

```text
SESSION_IDLE
  -> HANDSHAKING
  -> SESSION_READY
  -> EXECUTING_EXCLUSIVE
  -> SESSION_ERROR
```

说明：

- 蓝牙连接保活与协议会话管理拆成两层，不混成一个状态机
- `BUSY` 只用于“链路健康，但正在执行独占任务”
- 如果链路不健康、保活失败、会话错乱，不应显示为 `BUSY`，而应归入 `ERROR`

### 14.3 映射到 Phone `runtimeState`

| 连接状态 | 会话状态 | 映射结果 |
| - | - | - |
| `SOCKET_DISCONNECTED` | 任意 | `DISCONNECTED` |
| `SOCKET_CONNECTING` | 任意 | `CONNECTING` |
| `SOCKET_CONNECTED` | `HANDSHAKING` | `CONNECTING` |
| `SOCKET_CONNECTED` | `SESSION_READY` | `READY` |
| `SOCKET_CONNECTED` | `EXECUTING_EXCLUSIVE` | `BUSY` |
| `SOCKET_STALE` | 任意 | `ERROR` |
| `SOCKET_ERROR` | 任意 | `ERROR` |
| `SOCKET_CONNECTED` | `SESSION_ERROR` | `ERROR` |

### 14.4 Glasses 侧建议状态

```text
LISTENING
  -> PAIR_CHECK
  -> HANDSHAKING
  -> SESSION_READY
  -> EXECUTING_EXCLUSIVE
  -> SESSION_ERROR
```

如果发生不可恢复异常，可进入 `SESSION_ERROR`，等待断链后重新接受连接。

## 15. 错误处理建议

### 15.1 优先复用 Relay 侧错误码命名

为避免 Android 与 Relay 命名漂移，本地协议建议优先复用已有错误码前缀：

- `PROTOCOL_*`
- `COMMAND_*`
- `BLUETOOTH_*`
- `CAMERA_*`
- `DISPLAY_*`
- `INTERNAL_*`

### 15.2 错误分级处理

| 错误类型 | 处理建议 |
| - | - |
| 协议错误 | 直接断链 |
| 当前命令执行错误 | 发送 `command_error`，链路可继续 |
| 图片传输校验失败 | 当前命令失败，链路可继续或按实现重建 |
| socket 读写异常 | 断链并触发重连 |

### 15.3 常见失败场景

| 场景 | 建议错误码 |
| - | - |
| 版本不兼容 | `PROTOCOL_UNSUPPORTED_VERSION` |
| 非法消息类型 | `PROTOCOL_INVALID_MESSAGE_TYPE` |
| frame 头长度非法 | `PROTOCOL_HEADER_INVALID` |
| body 长度超限 | `PROTOCOL_FRAME_TOO_LARGE` |
| Glasses 相机不可用 | `CAMERA_UNAVAILABLE` |
| Glasses 拍照失败 | `CAMERA_CAPTURE_FAILED` |
| Glasses 文本显示失败 | `DISPLAY_FAILED` |
| 图片长度不匹配 | `IMAGE_TRANSFER_INCOMPLETE` |
| 图片哈希校验失败 | `IMAGE_CHECKSUM_MISMATCH` |
| Phone 写本地临时文件失败 | `IMAGE_STORAGE_WRITE_FAILED` |
| 上传 Relay 失败后临时文件删除 | 不额外定义新错误码，沿用上传失败原因码 |
| 蓝牙链路中断 | `BLUETOOTH_DISCONNECTED` |

## 16. MVP 落地建议

### 16.1 当前只冻结职责边界

模块具体拆分暂不在本文档冻结，后续单独讨论后再补模块设计文档。

当前先只固定这些边界：

- 蓝牙连接层与协议会话层分离
- `ping / pong` 归协议会话层管理
- 文件收发与命令生命周期分离
- Relay 上传桥接与本地文件接收分离

### 16.2 实现顺序建议

1. 先实现 `hello / hello_ack / ping / pong`
2. 再实现 `display_text` 的完整链路
3. 最后实现 `capture_photo + chunk_*`

这样能先验证：

- RFCOMM 长连接是否稳定
- 本地命令流是否可靠
- 图片分块回传是否满足 MVP 需求

## 17. 当前待确认问题

本草稿已经能支持 MVP 开发，但仍有几个可以继续讨论的点：

1. `chunkChecksum` 使用哪种算法最合适，是否直接固定为 CRC32
2. `hello_ack` 是否需要带更多设备信息，例如相机能力、屏幕规格

## 18. 下一步

如果本草稿方向确认，建议继续补两份文档：

1. `Phone -> Glasses` 完整 schema 常量表
2. `capture_photo` 的详细时序图与异常分支表
