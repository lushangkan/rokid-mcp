# Rokid 端到端协议总览

## 1. 目标

本文档基于 `docs/` 下已有协议文档，整理出一份面向实现的端到端协议总览，覆盖三段链路：

- `MCP <-> Relay`
- `Relay <-> Phone`
- `Phone <-> Glasses`

约束如下：

- `MCP <-> Relay` 使用原生 TypeScript `enum`、`class`、`type` 表达
- `Relay <-> Phone` 使用原生 TypeScript `enum`、`class`、`type` 表达
- `Phone <-> Glasses` 使用 Kotlin / LocalLink 形式表达
- 字段名、状态名、错误码优先复用现有文档，不重新命名

配套文档：

- `message-index.md`：消息编号表
- `state-machines.md`：状态机总表

本文不是替代原始文档，而是对以下资料的统一收敛：

- `docs/mcp-interface-schemas.md`
- `docs/mcp-interface-sequences.md`
- `docs/relay-protocol-constants.md`
- `docs/relay-protocol-schemas.md`
- `docs/relay-protocol-sequences.md`
- `docs/phone-glasses-protocol-constants.md`
- `docs/phone-glasses-protocol-schemas.md`
- `docs/phone-glasses-protocol-sequences.md`

## 2. 总体架构

```text
AI Client
  <-> MCP Tools
  <-> Relay HTTP API
  <-> Relay Session / Routing
  <-> Phone WebSocket + HTTP Upload
  <-> Bluetooth Classic RFCOMM LocalLink
  <-> Glasses Executor
```

职责边界：

- MCP 只消费 Relay HTTP DTO，不直接参与 WebSocket
- Relay 负责设备会话、命令路由、图片资源预分配与读取
- Phone 是桥接层，既消费 Relay 命令，也负责把本地执行结果和图片上传回 Relay
- Glasses 只关心本地 LocalLink 协议，不理解 Relay 或 MCP

## 3. 统一概念

全链路共享两个业务动作：

- `display_text`
- `capture_photo`

全链路共享三类结束语义：

- 成功只用 `command_result`
- 失败只用 `command_error`
- 执行中的阶段变化只用 `command_status`

标识符约定：

| 字段 | 说明 |
| - | - |
| `deviceId` | 手机设备标识 |
| `requestId` | 一次命令请求标识，贯穿三段链路 |
| `sessionId` | Relay 分配的 WebSocket 会话标识 |
| `imageId` | Relay 图片资源标识，只存在于 Relay 侧语义 |
| `transferId` | Phone 与 Glasses 本地图片传输标识 |

关键语义差异：

| 概念 | Relay 侧 | LocalLink 侧 |
| - | - | - |
| 图片资源标识 | `imageId` | 不使用 `imageId` |
| 图片传输标识 | 不强调 | 使用 `transferId` |
| 图片上传完成 | `image_uploaded` | 不存在该状态 |
| 本地图片完整交付 | 通过 Phone 上桥后的 `image_captured` / `command_result` 折叠表达 | `chunk_end` + `command_result(capture_photo)` |

## 4. MCP <-> Relay

### 4.1 传输与边界

- 传输方式：HTTP
- 编码：`application/json`
- 图片读取例外：`GET /api/v1/images/:imageId` 返回 `image/jpeg` 二进制
- 鉴权：`Authorization: Bearer <token>`
- MCP 统一使用“提交命令 + 轮询状态”的调用模式

### 4.2 原生 TypeScript 基础类型

说明：以下定义使用原生 TypeScript 类型表达协议结构。像 `deviceId`、`requestId`、`imageId` 的正则约束，仍然需要在运行时单独校验。

```ts
type ProtocolVersion = '1.0'

type DeviceId = string
type RequestId = string
type SessionId = string
type ImageId = string

enum Action {
  DisplayText = 'display_text',
  CapturePhoto = 'capture_photo',
}

enum CommandRecordStatus {
  Created = 'CREATED',
  DispatchedToPhone = 'DISPATCHED_TO_PHONE',
  AcknowledgedByPhone = 'ACKNOWLEDGED_BY_PHONE',
  Running = 'RUNNING',
  WaitingImageUpload = 'WAITING_IMAGE_UPLOAD',
  ImageUploading = 'IMAGE_UPLOADING',
  Completed = 'COMPLETED',
  Failed = 'FAILED',
  Timeout = 'TIMEOUT',
}

enum DeviceSessionState {
  Offline = 'OFFLINE',
  Online = 'ONLINE',
  Stale = 'STALE',
  Closed = 'CLOSED',
}

enum SetupState {
  Uninitialized = 'UNINITIALIZED',
  Initialized = 'INITIALIZED',
}

enum RuntimeState {
  Disconnected = 'DISCONNECTED',
  Connecting = 'CONNECTING',
  Ready = 'READY',
  Busy = 'BUSY',
  Error = 'ERROR',
}

enum UplinkState {
  Offline = 'OFFLINE',
  Connecting = 'CONNECTING',
  Online = 'ONLINE',
  Error = 'ERROR',
}

type Capability = Action

class ErrorObject {
  code!: string
  message!: string
  retryable!: boolean
  details?: Record<string, unknown>
}

class ErrorResponse {
  ok!: false
  error!: ErrorObject
  timestamp!: number
}
```

### 4.3 关键 DTO

```ts
enum CaptureQuality {
  Low = 'low',
  Medium = 'medium',
  High = 'high',
}

class GetDeviceStatusResponse {
  ok!: true
  device!: {
    deviceId: DeviceId
    connected: boolean
    sessionState: DeviceSessionState
    setupState: SetupState
    runtimeState: RuntimeState
    uplinkState: UplinkState
    capabilities: Capability[]
    activeCommandRequestId?: RequestId | null
    lastErrorCode?: string | null
    lastErrorMessage?: string | null
    lastSeenAt?: number | null
    sessionId?: SessionId | null
  }
  timestamp!: number
}

class SubmitDisplayTextCommandRequest {
  deviceId!: DeviceId
  action!: Action.DisplayText
  payload!: {
    text: string
    durationMs: number
  }
}

class SubmitCapturePhotoCommandRequest {
  deviceId!: DeviceId
  action!: Action.CapturePhoto
  payload!: {
    quality?: CaptureQuality
  }
}

class RelayImageRecord {
  imageId!: ImageId
  status!: 'RESERVED' | 'UPLOADING' | 'UPLOADED' | 'FAILED' | 'EXPIRED' | 'DELETED'
  url!: string
  mimeType?: string
  size?: number
  width?: number
  height?: number
  sha256?: string
  expiresAt?: number
}

class GetCommandStatusResponse {
  ok!: true
  command!: {
    requestId: RequestId
    deviceId: DeviceId
    action: Action
    status: CommandRecordStatus
    createdAt: number
    updatedAt: number
    acknowledgedAt?: number | null
    completedAt?: number | null
    image?: RelayImageRecord | null
    result?: unknown
    error?: ErrorObject | null
  }
  timestamp!: number
}
```

### 4.4 接口清单

| 接口 | 用途 |
| - | - |
| `GET /api/v1/devices/:deviceId/status` | 获取设备当前状态 |
| `POST /api/v1/commands` | 提交 `display_text` / `capture_photo` |
| `GET /api/v1/commands/:requestId` | 轮询命令状态与终态 |
| `GET /api/v1/images/:imageId` | 读取图片二进制 |

### 4.5 `capture_photo` 的 MCP 侧强约束

1. 必须先轮询到 `command.status = COMPLETED`
2. 必须确认 `command.result.action = capture_photo`
3. 必须确认 `command.image.status = UPLOADED`
4. 必须确认 `command.result.imageId = command.image.imageId`
5. 然后才允许下载图片
6. 下载后必须做 `Content-Type`、字节长度、base64 转换、可选 `sha256` 校验
7. 不允许把 `url` 直接作为 tool 最终结果返回给 AI

### 4.6 MCP 工具到 Relay 接口映射

| MCP tool | Relay 接口 | 说明 |
| - | - | - |
| `rokid.get_device_status` | `GET /api/v1/devices/:deviceId/status` | 直接透传状态结构 |
| `rokid.display_text` | `POST /api/v1/commands` + `GET /api/v1/commands/:requestId` | 提交后轮询直到终态 |
| `rokid.capture_photo` | `POST /api/v1/commands` + `GET /api/v1/commands/:requestId` + `GET /api/v1/images/:imageId` | 成功后下载并转 base64 |

## 5. Relay <-> Phone

### 5.1 传输与边界

- 控制面：WebSocket UTF-8 JSON text frame
- 数据面：图片不走 WebSocket，改走 HTTP `PUT /api/v1/images/:imageId?uploadToken=...`
- Relay -> Phone 只下发 `hello_ack` 与 `command`
- Phone -> Relay 负责 `hello`、`heartbeat`、`phone_state_update`、`command_ack`、`command_status`、`command_result`、`command_error`

### 5.2 原生 TypeScript 基础类型

```ts
enum WsMessageType {
  Hello = 'hello',
  HelloAck = 'hello_ack',
  Heartbeat = 'heartbeat',
  PhoneStateUpdate = 'phone_state_update',
  Command = 'command',
  CommandAck = 'command_ack',
  CommandStatus = 'command_status',
  CommandResult = 'command_result',
  CommandError = 'command_error',
}

type WsEnvelope<TType extends WsMessageType, TPayload> = {
  version: ProtocolVersion
  type: TType
  deviceId: DeviceId
  requestId?: RequestId
  sessionId?: SessionId
  timestamp: number
  payload: TPayload
}

enum RelayCommandProgressStatus {
  ForwardingToGlasses = 'forwarding_to_glasses',
  WaitingGlassesAck = 'waiting_glasses_ack',
  Executing = 'executing',
  Displaying = 'displaying',
  Capturing = 'capturing',
  ImageCaptured = 'image_captured',
  UploadingImage = 'uploading_image',
  ImageUploaded = 'image_uploaded',
}
```

Envelope 通用规则：

- `version` 必须是 `1.0`
- `requestId` 只在命令相关消息中必填
- `sessionId` 在 `hello_ack` 之后建议回传，并且必须与当前会话一致
- `payload` 必须是对象，不能是 `null` 或数组

### 5.3 关键消息

```ts
class HelloPayload {
  authToken!: string
  appVersion!: string
  appBuild?: string
  phoneInfo!: {
    brand?: string
    model?: string
    androidVersion?: string
    sdkInt?: number
  }
  setupState!: SetupState
  runtimeState!: RuntimeState
  uplinkState!: UplinkState.Connecting | UplinkState.Online
  capabilities!: Capability[]
}

type HelloMessage = WsEnvelope<WsMessageType.Hello, HelloPayload>

class HelloAckPayload {
  sessionId!: SessionId
  serverTime!: number
  heartbeatIntervalMs!: number
  heartbeatTimeoutMs!: number
}

type HelloAckMessage = WsEnvelope<WsMessageType.HelloAck, HelloAckPayload>

class CommandStatusPayload {
  action!: Action
  status!: RelayCommandProgressStatus
  statusAt!: number
  detailCode?: string
  detailMessage?: string
  image?: {
    imageId: ImageId
    uploadStartedAt?: number
    uploadedAt?: number
  }
}

type CommandStatusMessage = WsEnvelope<WsMessageType.CommandStatus, CommandStatusPayload> & {
  requestId: RequestId
}
```

### 5.4 阶段附加规则

| 状态 | 约束 |
| - | - |
| `displaying` | `action` 必须是 `display_text` |
| `capturing` | `action` 必须是 `capture_photo` |
| `image_captured` | 必须带 `image.imageId` |
| `uploading_image` | 必须带 `image.imageId` |
| `image_uploaded` | 必须带 `image.imageId` |

### 5.5 Phone -> Relay 图片上传

接口：

- `PUT /api/v1/images/:imageId?uploadToken=...`

请求要求：

- `Content-Type: image/jpeg`
- `X-Device-Id`
- `X-Request-Id`
- Body 为原始 JPEG bytes，不使用 base64，不使用 multipart

成功响应：

```ts
class PutImageResponse {
  ok!: true
  image!: {
    imageId: ImageId
    status: 'UPLOADED'
    url: string
    mimeType: 'image/jpeg'
    size: number
    sha256?: string
    uploadedAt: number
  }
  timestamp!: number
}
```

服务端校验重点：

- `uploadToken` 必须与 `imageId`、`deviceId`、`requestId` 匹配
- 内容类型必须是 `image/jpeg`
- 文件大小必须大于 0 且不超过 `maxSizeBytes`
- 同一 `imageId` 不允许并发重复上传

## 6. Phone <-> Glasses

### 6.1 传输与边界

- 传输方式：Bluetooth Classic RFCOMM Socket
- 由于 RFCOMM 是字节流，必须自定义分帧
- 控制消息与图片数据复用同一条 socket
- 图片 chunk 直接传原始二进制，不做 base64
- 本地协议名固定为 `rokid-local-link`

### 6.2 LocalLink 分帧格式

```text
[magic: uint32][headerLength: uint32][bodyLength: uint32][headerCrc32: uint32][headerJson UTF-8][body bytes]
```

接收侧校验顺序：

1. 校验 `magic`
2. 校验 `headerLength <= FRAME_HEADER_MAX_BYTES`
3. 校验 `bodyLength` 是否在允许范围内
4. 读取 `headerJson` 与 `body bytes`
5. 校验 `headerCrc32`
6. 解析 `headerJson`
7. 对 `chunk_data` 校验 `payload.size == bodyLength`
8. 校验 `chunkChecksum`
9. 在 `chunk_end` 后校验整文件 `sha256`

### 6.3 Kotlin 基础类型

```kotlin
object LocalLinkConstants {
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
    val version: String = LocalLinkConstants.PROTOCOL_VERSION,
    val type: LocalMessageType,
    val requestId: String? = null,
    val transferId: String? = null,
    val timestamp: Long,
    val payload: T,
)
```

Header 规则：

- `requestId` 在命令和 chunk 相关消息中必填
- `transferId` 只在 `chunk_*` 消息中必填
- `timestamp` 必须是正整数毫秒时间戳

### 6.4 握手、命令与分块

```kotlin
data class HelloPayload(
    val protocolName: String = LocalLinkConstants.PROTOCOL_NAME,
    val role: LinkRole = LinkRole.PHONE,
    val deviceId: String,
    val appVersion: String,
    val appBuild: String? = null,
    val supportedActions: List<LocalAction>,
)

typealias HelloMessage = LocalFrameHeader<HelloPayload>

data class HelloAckPayload(
    val accepted: Boolean,
    val role: LinkRole = LinkRole.GLASSES,
    val runtimeState: LocalRuntimeState? = null,
    val error: HelloError? = null,
)

data class HelloError(
    val code: String,
    val message: String,
)

typealias HelloAckMessage = LocalFrameHeader<HelloAckPayload>

data class CaptureTransfer(
    val transferId: String,
    val mediaType: String = LocalLinkConstants.IMAGE_MIME_TYPE_JPEG,
    val maxBytes: Long,
)

data class ChunkDataPayload(
    val action: LocalAction = LocalAction.CAPTURE_PHOTO,
    val index: Int,
    val offset: Long,
    val size: Int,
    val chunkChecksum: String,
)

typealias ChunkDataMessage = LocalFrameHeader<ChunkDataPayload>
```

语义约束：

- `hello_ack.accepted = false` 时，`error` 必填且眼镜应主动断链
- `DISPLAYING` 只适用于 `DISPLAY_TEXT`
- `CAPTURING` 只适用于 `CAPTURE_PHOTO`
- LocalLink 侧不使用 `imageId`，图片资源绑定由 Phone 在上桥时补齐为 Relay 的 `imageId`
- `chunk_data` 的 `body bytes` 就是原始图片块
- `payload.index` 从 `0` 开始单调递增
- `payload.offset` 必须与累计写入长度一致

## 7. 动作映射

### 7.1 `display_text`

| 链路 | 入站动作 | 中间状态 | 成功结果 |
| - | - | - | - |
| `MCP -> Relay` | `POST /api/v1/commands(action=display_text)` | 轮询 `CREATED` / `RUNNING` | `GetCommandStatusResponse.command.result.action = display_text` |
| `Relay -> Phone` | `command(action=display_text)` | `forwarding_to_glasses` / `waiting_glasses_ack` / `executing` / `displaying` | `command_result(action=display_text)` |
| `Phone -> Glasses` | `COMMAND(DISPLAY_TEXT)` | `EXECUTING` / `DISPLAYING` | `COMMAND_RESULT(DISPLAY_TEXT)` |

### 7.2 `capture_photo`

| 链路 | 入站动作 | 中间状态 | 成功结果 |
| - | - | - | - |
| `MCP -> Relay` | `POST /api/v1/commands(action=capture_photo)` | 轮询到 `WAITING_IMAGE_UPLOAD` / `IMAGE_UPLOADING` / `COMPLETED` | 下载 `GET /api/v1/images/:imageId` 后转 base64 |
| `Relay -> Phone` | `command(action=capture_photo, image.uploadUrl, image.imageId)` | `capturing` / `image_captured` / `uploading_image` / `image_uploaded` | `command_result(action=capture_photo)` |
| `Phone -> Glasses` | `COMMAND(CAPTURE_PHOTO, transfer.transferId)` | `CAPTURING` + `CHUNK_*` | `COMMAND_RESULT(CAPTURE_PHOTO)` |

关键差异：

- Relay 侧的 `imageId` 是资源概念
- LocalLink 侧的 `transferId` 是传输概念
- Phone 负责把 LocalLink 收到的图片与 Relay 预分配的 `imageId` 绑定起来

## 8. 错误映射

### 8.1 上游优先透传原则

- Relay 已有错误码时，MCP 优先透传
- Phone / Glasses 本地错误应尽量映射到已冻结的 Relay 错误码
- 只有 MCP 本地适配错误，才新增 `MCP_*` 错误码

### 8.2 典型错误收敛

| 场景 | Glasses / LocalLink | Relay | MCP |
| - | - | - | - |
| 蓝牙断开 | `BLUETOOTH_DISCONNECTED` | `BLUETOOTH_DISCONNECTED` | 透传 |
| 显示失败 | `DISPLAY_FAILED` | `DISPLAY_FAILED` | 透传 |
| 相机不可用 | `CAMERA_UNAVAILABLE` | `CAMERA_UNAVAILABLE` | 透传 |
| 图片校验失败 | `IMAGE_CHECKSUM_MISMATCH` | 通常通过 `command_error` 收口 | 透传 Relay 终态错误 |
| 图片上传失败 | `UPLOAD_FAILED` 或本地上传错误 | `UPLOAD_*` | 透传 |
| MCP 下载图片失败 | 不涉及 | 可能是 `IMAGE_NOT_READY` / `IMAGE_EXPIRED` | `MCP_IMAGE_DOWNLOAD_FAILED` 或透传 Relay 错误 |
| MCP base64 / sha256 校验失败 | 不涉及 | 不涉及 | `MCP_IMAGE_PARSE_FAILED` |

### 8.3 命令失败收口 owner

| 端 | 最终 owner |
| - | - |
| MCP | tool 层自身 |
| Phone | `RelayCommandBridge` |
| Glasses | `CommandDispatcher` |

其他模块只做局部 cleanup，不直接做最终命令失败收口。

## 9. 端到端主链路摘要

### 9.1 `display_text`

```text
AI -> MCP: display_text
MCP -> Relay: POST /commands
Relay -> Phone: WS command(display_text)
Phone -> Relay: command_ack
Phone -> Glasses: LocalLink command(display_text)
Glasses -> Phone: command_ack / command_status(executing, displaying) / command_result(display_text)
Phone -> Relay: command_status(...) / command_result(display_text)
MCP -> Relay: GET /commands/:requestId until COMPLETED
MCP -> AI: success
```

### 9.2 `capture_photo`

```text
AI -> MCP: capture_photo
MCP -> Relay: POST /commands
Relay 预分配 imageId / uploadUrl
Relay -> Phone: WS command(capture_photo)
Phone -> Relay: command_ack(BUSY)
Phone -> Glasses: LocalLink command(capture_photo, transferId)
Glasses -> Phone: command_status(capturing) + chunk_start + chunk_data*N + chunk_end + command_result(capture_photo)
Phone: 组装图片并校验
Phone -> Relay: command_status(image_captured)
Phone -> Relay: command_status(uploading_image)
Phone -> Relay: HTTP PUT image bytes
Phone -> Relay: command_status(image_uploaded)
Phone -> Relay: command_result(capture_photo)
MCP -> Relay: GET /commands/:requestId until COMPLETED
MCP -> Relay: GET /images/:imageId
MCP: 校验图片并转 base64
MCP -> AI: success
```

## 10. 实现建议

- `packages/protocol/` 适合沉淀共享 `enum`、DTO `class` 与公共 `type`
- `apps/relay-server/` 直接消费 `Relay <-> Phone` 和 `MCP <-> Relay` 的原生 TypeScript 类型定义
- `apps/phone-android/` 同时实现 TypeScript 协议映射层与 Kotlin LocalLink codec
- `apps/glasses-android/` 只需要实现 Kotlin LocalLink 常量、frame codec、命令执行器

如果后续继续细化，建议把这份文档作为总览入口，原始文档继续保留为：

- 常量表
- 完整 schema
- 时序表
- 模块设计
