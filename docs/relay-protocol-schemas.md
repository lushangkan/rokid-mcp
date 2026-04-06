# Relay Protocol Schemas

## 1. 目标

本文档在 `docs/relay-protocol-constants.md` 的基础上，进一步定义 Relay 协议中每一种 WS / HTTP 消息的完整 schema。

本文档覆盖以下范围：

- Phone -> Relay: WebSocket 控制消息 schema
- Relay -> Phone: WebSocket 控制消息 schema
- Relay HTTP 控制请求与响应 schema
- Phone -> Relay: HTTP 图片上传 schema
- 通用错误响应 schema

本文档的目标是让以下三端可以直接据此实现 DTO、校验器与协议适配层：

- `apps/phone-android/`
- `apps/relay-server/`
- `packages/mcp-server/`

## 2. 依赖文档

本文件依赖以下常量表与命名约束：

- `docs/relay-protocol-constants.md`

如果本文件与常量表发生冲突，以常量表中的命名为准；如果出现字段级冲突，应优先修订本文件。

## 3. 通用约定

### 3.1 传输约定

- WebSocket 控制消息统一使用 UTF-8 JSON text frame
- 图片内容不通过 WebSocket 传输
- 图片内容统一通过 HTTP `PUT /api/v1/images/:imageId` 传输原始二进制
- 除图片下载接口外，HTTP 控制接口统一使用 `application/json`

### 3.2 JSON 编码约定

- 所有 JSON 字段使用 camelCase
- 未声明可空的字段，不允许传 `null`
- 未声明可选的字段，必须存在
- 客户端不得发送未定义字段；服务端可选择拒绝或忽略，但 MVP 建议直接拒绝并返回协议错误

### 3.3 标识符约定

为方便三端统一校验，建议先约束以下格式：

| 字段 | 类型 | 建议格式 | 说明 |
| - | - | - | - |
| `deviceId` | `string` | `^[a-zA-Z0-9._-]{3,64}$` | 手机设备标识 |
| `requestId` | `string` | `^req_[a-zA-Z0-9_-]{6,128}$` | 命令请求标识 |
| `sessionId` | `string` | `^ses_[a-zA-Z0-9_-]{6,128}$` | Relay 分配的 WS 会话标识 |
| `imageId` | `string` | `^img_[a-zA-Z0-9_-]{6,128}$` | 图片资源标识 |
| `uploadToken` | `string` | 非空字符串，建议长度 >= 24 | 图片上传令牌 |

### 3.4 时间戳约定

| 字段类型 | 格式 | 说明 |
| - | - | - |
| `timestamp` | Unix 毫秒时间戳，`number` | WS Envelope 的统一时间字段 |
| `createdAt` / `updatedAt` / `expiresAt` / `completedAt` 等 | Unix 毫秒时间戳，`number` | JSON 业务字段统一使用毫秒时间戳 |

### 3.5 枚举依赖

以下枚举值不在本文件重复发明，统一引用常量表：

- `type`
- `action`
- `payload.status` for `command_status`
- `setupState`
- `runtimeState`
- `uplinkState`
- `deviceSessionState`
- `commandRecord.status`
- `imageRecord.status`
- `error.code`

## 4. WebSocket 总体 schema

### 4.1 通用 Envelope schema

所有 WebSocket 消息共享同一个外层结构：

```ts
type WsEnvelope<TPayload> = {
  version: "1.0"
  type:
    | "hello"
    | "hello_ack"
    | "heartbeat"
    | "phone_state_update"
    | "command"
    | "command_ack"
    | "command_status"
    | "command_result"
    | "command_error"
  deviceId: string
  requestId?: string
  sessionId?: string
  timestamp: number
  payload: TPayload
}
```

### 4.2 Envelope 通用校验规则

| 字段 | 规则 |
| - | - |
| `version` | 必须等于 `1.0` |
| `type` | 必须属于已定义消息类型 |
| `deviceId` | 必须存在，且与当前已认证会话绑定的 `deviceId` 一致 |
| `requestId` | 仅命令相关消息必填；非命令类消息不得乱填其他命令的 `requestId` |
| `sessionId` | 在 `hello_ack` 后，手机发回的消息建议带上；服务端应校验是否与当前会话一致。对 `hello_ack` 本身，以 `payload.sessionId` 为权威来源 |
| `timestamp` | 必须是正整数毫秒时间戳 |
| `payload` | 必须为 object，不允许为数组、字符串、数字或 `null` |

### 4.3 `requestId` 必填规则

以下消息必须携带 `requestId`：

- `command`
- `command_ack`
- `command_status`
- `command_result`
- `command_error`

以下消息不应携带 `requestId`，除非未来明确扩展：

- `hello`
- `hello_ack`
- `heartbeat`
- `phone_state_update`

## 5. WebSocket 消息 schema

## 5.1 Phone -> Relay

### 5.1.1 `hello`

用途：手机第一次建立 WS 连接后声明身份、能力与当前状态。

Envelope：

```ts
type HelloMessage = WsEnvelope<{
  authToken: string
  appVersion: string
  appBuild?: string
  phoneInfo: {
    brand?: string
    model?: string
    androidVersion?: string
    sdkInt?: number
  }
  setupState: "UNINITIALIZED" | "INITIALIZED"
  runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR"
  uplinkState: "CONNECTING" | "ONLINE"
  capabilities: Array<"display_text" | "capture_photo">
  targetGlasses?: {
    bluetoothName?: string
    bluetoothAddress?: string
  }
  relayConfig?: {
    baseUrl?: string
  }
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.authToken` | 是 | 手机认证凭证 |
| `payload.appVersion` | 是 | App 版本号，例如 `0.1.0` |
| `payload.appBuild` | 否 | 构建号 |
| `payload.phoneInfo` | 是 | 手机环境信息 |
| `payload.setupState` | 是 | 初始化状态 |
| `payload.runtimeState` | 是 | 运行状态 |
| `payload.uplinkState` | 是 | 对当前 WS 来说，首次 `hello` 时只能是 `CONNECTING` 或 `ONLINE` |
| `payload.capabilities` | 是 | 当前支持的命令能力列表，不能为空数组 |
| `payload.targetGlasses` | 否 | 当前绑定眼镜的蓝牙信息 |
| `payload.relayConfig` | 否 | 本地记录的 Relay 配置，供日志或调试 |

示例：

```json
{
  "version": "1.0",
  "type": "hello",
  "deviceId": "rokid_glasses_01",
  "timestamp": 1710000000000,
  "payload": {
    "authToken": "dev_token_xxx",
    "appVersion": "0.1.0",
    "appBuild": "12",
    "phoneInfo": {
      "brand": "Google",
      "model": "Pixel 8",
      "androidVersion": "14",
      "sdkInt": 34
    },
    "setupState": "INITIALIZED",
    "runtimeState": "READY",
    "uplinkState": "CONNECTING",
    "capabilities": ["display_text", "capture_photo"],
    "targetGlasses": {
      "bluetoothName": "Rokid Glasses",
      "bluetoothAddress": "00:11:22:33:44:55"
    },
    "relayConfig": {
      "baseUrl": "https://relay.example.com"
    }
  }
}
```

### 5.1.2 `heartbeat`

用途：手机定期发送心跳，维持在线会话。

Envelope：

```ts
type HeartbeatMessage = WsEnvelope<{
  seq: number
  runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR"
  uplinkState: "ONLINE" | "ERROR"
  pendingCommandCount: number
  activeCommandRequestId?: string
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.seq` | 是 | 心跳序号，单会话内递增 |
| `payload.runtimeState` | 是 | 当前运行状态 |
| `payload.uplinkState` | 是 | 心跳期间应为 `ONLINE` 或 `ERROR` |
| `payload.pendingCommandCount` | 是 | 当前手机端待处理命令数，MVP 通常是 `0` 或 `1` |
| `payload.activeCommandRequestId` | 否 | 当前正在执行的请求 ID |

### 5.1.3 `phone_state_update`

用途：手机状态机变化时主动上报，而不等下一个 heartbeat。

Envelope：

```ts
type PhoneStateUpdateMessage = WsEnvelope<{
  setupState: "UNINITIALIZED" | "INITIALIZED"
  runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR"
  uplinkState: "OFFLINE" | "CONNECTING" | "ONLINE" | "ERROR"
  lastErrorCode?: string | null
  lastErrorMessage?: string | null
  activeCommandRequestId?: string | null
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.setupState` | 是 | 当前初始化状态 |
| `payload.runtimeState` | 是 | 当前运行状态 |
| `payload.uplinkState` | 是 | 当前上行网络状态 |
| `payload.lastErrorCode` | 否 | 最近一次错误码 |
| `payload.lastErrorMessage` | 否 | 最近一次错误描述 |
| `payload.activeCommandRequestId` | 否 | 当前执行中的命令 ID |

### 5.1.4 `command_ack`

用途：手机确认已接收并接受 Relay 下发的命令。

Envelope：

```ts
type CommandAckMessage = WsEnvelope<{
  action: "display_text" | "capture_photo"
  acceptedAt: number
  runtimeState: "READY" | "BUSY"
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `requestId` | 是 | 对应命令请求 ID |
| `payload.action` | 是 | 对应的命令动作 |
| `payload.acceptedAt` | 是 | 手机接受命令的时间戳 |
| `payload.runtimeState` | 是 | 接受后本地状态；`capture_photo` 一般进入 `BUSY` |

约束：

- `command_ack` 只在命令被接受时发送
- 如果手机无法接受命令，应直接发送 `command_error`
- 同一 `requestId` 最多允许一个有效 `command_ack`

### 5.1.5 `command_status`

用途：手机上报命令执行中的阶段状态。

Envelope：

```ts
type CommandStatusMessage = WsEnvelope<{
  action: "display_text" | "capture_photo"
  status:
    | "forwarding_to_glasses"
    | "waiting_glasses_ack"
    | "executing"
    | "displaying"
    | "capturing"
    | "image_captured"
    | "uploading_image"
    | "image_uploaded"
  statusAt: number
  detailCode?: string
  detailMessage?: string
  image?: {
    imageId: string
    uploadStartedAt?: number
    uploadedAt?: number
  }
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.action` | 是 | 对应命令动作 |
| `payload.status` | 是 | 当前阶段状态 |
| `payload.statusAt` | 是 | 阶段切换时间 |
| `payload.detailCode` | 否 | 附加细节码，可用于本地蓝牙或相机中间态 |
| `payload.detailMessage` | 否 | 附加说明 |
| `payload.image` | 否 | 仅 `capture_photo` 相关阶段可出现；状态上报只回传图片进度信息，不回传 `uploadUrl` |

阶段附加规则：

| `payload.status` | 附加要求 |
| - | - |
| `forwarding_to_glasses` | 不要求 `image` |
| `waiting_glasses_ack` | 不要求 `image` |
| `executing` | 不要求 `image` |
| `displaying` | `action` 必须为 `display_text` |
| `capturing` | `action` 必须为 `capture_photo` |
| `image_captured` | `action` 必须为 `capture_photo`，且 `image.imageId` 必填 |
| `uploading_image` | `action` 必须为 `capture_photo`，且 `image.imageId` 必填 |
| `image_uploaded` | `action` 必须为 `capture_photo`，且 `image.imageId` 必填 |

### 5.1.6 `command_result`

用途：手机上报命令成功完成。

该消息是 discriminated union，依赖 `payload.action`。

#### A. `display_text` 成功结果

```ts
type DisplayTextCommandResultMessage = WsEnvelope<{
  action: "display_text"
  completedAt: number
  result: {
    displayed: true
    durationMs: number
  }
}>
```

#### B. `capture_photo` 成功结果

```ts
type CapturePhotoCommandResultMessage = WsEnvelope<{
  action: "capture_photo"
  completedAt: number
  result: {
    imageId: string
    mimeType: string
    size: number
    width: number
    height: number
    sha256?: string
  }
}>
```

共同字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `requestId` | 是 | 对应命令请求 ID |
| `payload.action` | 是 | 对应命令动作 |
| `payload.completedAt` | 是 | 完成时间 |
| `payload.result` | 是 | 结果对象 |

补充约束：

- `display_text` 的结果中不允许携带 `imageId`
- `capture_photo` 的结果中必须携带 `imageId`
- `capture_photo` 的 `imageId` 必须与命令下发时分配的 `image.imageId` 一致

### 5.1.7 `command_error`

用途：手机上报命令失败完成。

Envelope：

```ts
type CommandErrorMessage = WsEnvelope<{
  action: "display_text" | "capture_photo"
  failedAt: number
  error: {
    code: string
    message: string
    retryable: boolean
    details?: Record<string, unknown>
  }
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.action` | 是 | 对应命令动作 |
| `payload.failedAt` | 是 | 失败时间 |
| `payload.error.code` | 是 | 错误码，必须来自常量表 |
| `payload.error.message` | 是 | 可读错误说明 |
| `payload.error.retryable` | 是 | 是否建议上层重试 |
| `payload.error.details` | 否 | 附加调试上下文 |

## 5.2 Relay -> Phone

### 5.2.1 `hello_ack`

用途：Relay 认证通过后确认手机上线，并下发会话参数。

Envelope：

```ts
type HelloAckMessage = WsEnvelope<{
  sessionId: string
  serverTime: number
  heartbeatIntervalMs: number
  heartbeatTimeoutMs: number
  sessionTtlMs?: number
  limits: {
    maxPendingCommands: number
    maxImageUploadSizeBytes: number
    acceptedImageContentTypes: string[]
  }
}>
```

字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `payload.sessionId` | 是 | Relay 分配的会话 ID |
| `payload.serverTime` | 是 | 服务端当前时间 |
| `payload.heartbeatIntervalMs` | 是 | 手机建议发送 heartbeat 的间隔 |
| `payload.heartbeatTimeoutMs` | 是 | 服务端判定心跳超时的阈值 |
| `payload.sessionTtlMs` | 否 | 会话建议最长存活时间 |
| `payload.limits` | 是 | 服务器侧限制参数 |

补充说明：

- `hello_ack` 的会话标识以 `payload.sessionId` 为唯一权威字段
- envelope 顶层 `sessionId` 在 `hello_ack` 中可省略；后续消息若回传 `sessionId`，应与 `payload.sessionId` 对应的当前会话一致

### 5.2.2 `command`

用途：Relay 下发业务命令。

该消息是 discriminated union，依赖 `payload.action`。

#### A. `display_text` 命令

```ts
type DisplayTextCommandMessage = WsEnvelope<{
  action: "display_text"
  timeoutMs: number
  params: {
    text: string
    durationMs: number
    priority?: "normal" | "high"
  }
}>
```

#### B. `capture_photo` 命令

```ts
type CapturePhotoCommandMessage = WsEnvelope<{
  action: "capture_photo"
  timeoutMs: number
  params: {
    quality?: "low" | "medium" | "high"
  }
  image: {
    imageId: string
    uploadUrl: string
    method: "PUT"
    contentType: "image/jpeg"
    expiresAt: number
    maxSizeBytes: number
  }
}>
```

共同字段规则：

| 字段 | 必填 | 说明 |
| - | - | - |
| `requestId` | 是 | 命令请求 ID |
| `payload.action` | 是 | 业务动作 |
| `payload.timeoutMs` | 是 | 命令总超时，手机可用于本地超时控制 |
| `payload.params` | 是 | 动作参数 |
| `payload.image` | `capture_photo` 时必填 | 图片上传指令 |

## 6. HTTP 通用 schema

### 6.1 HTTP 请求头约定

#### 6.1.1 Relay 控制接口

| Header | 必填 | 说明 |
| - | - | - |
| `Authorization: Bearer <token>` | 是 | Relay 控制接口统一启用 bearer 认证 |
| `Content-Type: application/json` | `POST` 时必填 | JSON 请求体 |
| `Accept: application/json` | 建议 | 期望 JSON 响应 |

#### 6.1.2 Phone -> Relay 图片上传接口

| Header | 必填 | 说明 |
| - | - | - |
| `Content-Type` | 是 | 当前只接受 `image/jpeg` |
| `Content-Length` | 强烈建议 | 上传大小 |
| `X-Device-Id` | 是 | 上传图片对应的 `deviceId` |
| `X-Request-Id` | 是 | 上传图片对应的 `requestId` |
| `X-Upload-Checksum-Sha256` | 否 | 上传内容的 sha256，用于完整性校验 |

### 6.2 HTTP 通用错误响应 schema

除图片二进制下载外，所有错误响应统一返回 JSON：

```ts
type ErrorResponse = {
  ok: false
  error: {
    code: string
    message: string
    retryable: boolean
    details?: Record<string, unknown>
  }
  timestamp: number
}
```

示例：

```json
{
  "ok": false,
  "error": {
    "code": "DEVICE_BUSY",
    "message": "device is busy with another exclusive command",
    "retryable": true,
    "details": {
      "deviceId": "rokid_glasses_01"
    }
  },
  "timestamp": 1710000000000
}
```

## 7. Relay HTTP 控制 schema

### 7.1 `GET /api/v1/devices/:deviceId/status`

用途：获取设备当前状态。

响应 schema：

```ts
type GetDeviceStatusResponse = {
  ok: true
  device: {
    deviceId: string
    connected: boolean
    sessionState: "OFFLINE" | "ONLINE" | "STALE" | "CLOSED"
    setupState: "UNINITIALIZED" | "INITIALIZED"
    runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR"
    uplinkState: "OFFLINE" | "CONNECTING" | "ONLINE" | "ERROR"
    capabilities: Array<"display_text" | "capture_photo">
    activeCommandRequestId?: string | null
    lastErrorCode?: string | null
    lastErrorMessage?: string | null
    lastSeenAt?: number | null
    sessionId?: string | null
  }
  timestamp: number
}
```

示例：

```json
{
  "ok": true,
  "device": {
    "deviceId": "rokid_glasses_01",
    "connected": true,
    "sessionState": "ONLINE",
    "setupState": "INITIALIZED",
    "runtimeState": "READY",
    "uplinkState": "ONLINE",
    "capabilities": ["display_text", "capture_photo"],
    "activeCommandRequestId": null,
    "lastErrorCode": null,
    "lastErrorMessage": null,
    "lastSeenAt": 1710000000000,
    "sessionId": "ses_001"
  },
  "timestamp": 1710000000100
}
```

### 7.2 `POST /api/v1/commands`

用途：由调用方提交业务命令。

请求 schema 是 discriminated union，依赖 `action`。

#### A. `display_text` 请求

```ts
type SubmitDisplayTextCommandRequest = {
  deviceId: string
  action: "display_text"
  payload: {
    text: string
    durationMs: number
  }
}
```

#### B. `capture_photo` 请求

```ts
type SubmitCapturePhotoCommandRequest = {
  deviceId: string
  action: "capture_photo"
  payload: {
    quality?: "low" | "medium" | "high"
  }
}
```

校验规则：

| 字段 | 规则 |
| - | - |
| `deviceId` | 必填，必须对应已知设备 |
| `action` | 必填，必须是支持的业务动作 |
| `payload` | 必填，结构随动作变化 |
| `payload.text` | `display_text` 时必填，非空字符串 |
| `payload.durationMs` | `display_text` 时必填，建议 `1` 到 `60000` |
| `payload.quality` | `capture_photo` 时可选，默认由 Relay 或手机侧补 `medium` |

成功响应 schema：

#### A. `display_text` 提交成功

```ts
type SubmitDisplayTextCommandResponse = {
  ok: true
  requestId: string
  deviceId: string
  action: "display_text"
  status: "CREATED" | "DISPATCHED_TO_PHONE"
  createdAt: number
  statusUrl: string
}
```

#### B. `capture_photo` 提交成功

```ts
type SubmitCapturePhotoCommandResponse = {
  ok: true
  requestId: string
  deviceId: string
  action: "capture_photo"
  status: "CREATED" | "DISPATCHED_TO_PHONE"
  createdAt: number
  statusUrl: string
  image: {
    imageId: string
    url: string
    uploadStatus: "RESERVED"
    expiresAt: number
  }
}
```

说明：

- `capture_photo` 的响应里返回的是最终图片资源地址 `url`
- 手机真正使用的上传地址 `uploadUrl` 只通过 WS `command` 下发，不通过 MCP 暴露

### 7.3 `GET /api/v1/commands/:requestId`

用途：查询命令生命周期状态与最终结果。

统一响应 schema：

```ts
type GetCommandStatusResponse = {
  ok: true
  command: {
    requestId: string
    deviceId: string
    action: "display_text" | "capture_photo"
    status:
      | "CREATED"
      | "DISPATCHED_TO_PHONE"
      | "ACKNOWLEDGED_BY_PHONE"
      | "RUNNING"
      | "WAITING_IMAGE_UPLOAD"
      | "IMAGE_UPLOADING"
      | "COMPLETED"
      | "FAILED"
      | "TIMEOUT"
    createdAt: number
    updatedAt: number
    acknowledgedAt?: number | null
    completedAt?: number | null
    image?: {
      imageId: string
      status: "RESERVED" | "UPLOADING" | "UPLOADED" | "FAILED" | "EXPIRED" | "DELETED"
      url: string
      mimeType?: string
      size?: number
      width?: number
      height?: number
      sha256?: string
      expiresAt?: number
    } | null
    result?:
      | {
          action: "display_text"
          displayed: true
          durationMs: number
        }
      | {
          action: "capture_photo"
          imageId: string
          url: string
          mimeType: string
          size: number
          width: number
          height: number
          sha256?: string
        }
      | null
    error?: {
      code: string
      message: string
      retryable: boolean
      details?: Record<string, unknown>
    } | null
  }
  timestamp: number
}
```

状态到字段约束：

| `command.status` | `result` | `error` | `image` |
| - | - | - | - |
| `CREATED` | `null` | `null` | `capture_photo` 可为 `RESERVED` |
| `DISPATCHED_TO_PHONE` | `null` | `null` | `capture_photo` 可为 `RESERVED` |
| `ACKNOWLEDGED_BY_PHONE` | `null` | `null` | `capture_photo` 可为 `RESERVED` |
| `RUNNING` | `null` | `null` | `capture_photo` 可能存在 |
| `WAITING_IMAGE_UPLOAD` | `null` | `null` | `capture_photo` 必须存在 |
| `IMAGE_UPLOADING` | `null` | `null` | `capture_photo` 必须存在 |
| `COMPLETED` | 非空 | `null` | `capture_photo` 必须为 `UPLOADED` |
| `FAILED` | `null` | 非空 | `capture_photo` 可存在 |
| `TIMEOUT` | `null` | 非空 | `capture_photo` 可存在 |

## 8. Phone -> Relay 图片上传 HTTP schema

### 8.1 `PUT /api/v1/images/:imageId?uploadToken=...`

用途：手机把图片二进制上传到 Relay。

#### URL 参数

| 参数 | 必填 | 类型 | 说明 |
| - | - | - | - |
| `imageId` | 是 | `string` | 图片资源 ID |
| `uploadToken` | 是 | `string` | 一次性上传令牌 |

#### Header schema

| Header | 必填 | 类型 | 说明 |
| - | - | - | - |
| `Content-Type` | 是 | `string` | 当前只允许 `image/jpeg` |
| `Content-Length` | 强烈建议 | `number` | 图片大小 |
| `X-Device-Id` | 是 | `string` | 上传图片对应的设备 ID |
| `X-Request-Id` | 是 | `string` | 上传图片对应的命令请求 ID |
| `X-Upload-Checksum-Sha256` | 否 | `string` | 原始内容 sha256 |

#### Body schema

| 部分 | 类型 | 说明 |
| - | - | - |
| Body | raw bytes | 原始 JPEG 二进制，不使用 base64，不使用 multipart |

#### 服务端校验规则

| 项目 | 规则 |
| - | - |
| `uploadToken` | 必须存在、未过期、未使用、且与 `imageId`、`deviceId`、`requestId` 匹配 |
| `Content-Type` | 必须为 `image/jpeg` |
| `Content-Length` | 如果存在，必须大于 0 且不超过 `maxSizeBytes` |
| Body | 不得为空 |
| 同一 `imageId` | 不允许并发重复上传 |

成功响应 schema：

```ts
type PutImageResponse = {
  ok: true
  image: {
    imageId: string
    status: "UPLOADED"
    url: string
    mimeType: "image/jpeg"
    size: number
    sha256?: string
    uploadedAt: number
  }
  timestamp: number
}
```

示例：

```json
{
  "ok": true,
  "image": {
    "imageId": "img_001",
    "status": "UPLOADED",
    "url": "/api/v1/images/img_001",
    "mimeType": "image/jpeg",
    "size": 2483921,
    "sha256": "c5a7f6f0...",
    "uploadedAt": 1710000005000
  },
  "timestamp": 1710000005100
}
```

## 9. Relay 图片读取 HTTP schema

### 9.1 `GET /api/v1/images/:imageId`

用途：读取图片资源。

该接口成功时不返回 JSON，而直接返回二进制内容。

#### 请求头

| Header | 必填 | 说明 |
| - | - | - |
| `Authorization: Bearer <token>` | 是 | 图片读取接口与其他 Relay HTTP 控制接口保持一致，统一使用 bearer 认证 |
| `Accept` | 否 | 可为 `image/jpeg`、`*/*`，或按调用方默认协商 |

#### 成功响应

| 项目 | 值 |
| - | - |
| Status | `200 OK` |
| Content-Type | `image/jpeg` |
| Body | 图片原始二进制 |

#### 建议响应头

| Header | 说明 |
| - | - |
| `Content-Type` | 固定 `image/jpeg` |
| `Content-Length` | 图片大小 |
| `Cache-Control` | MVP 建议 `private, max-age=300` 或更保守策略 |
| `ETag` | 可选，便于缓存 |
| `X-Image-Id` | 返回图片 ID，便于调试 |

#### 失败时

失败时返回 `application/json`，结构使用通用 `ErrorResponse`。

常见错误：

- `IMAGE_NOT_FOUND`
- `IMAGE_NOT_READY`
- `IMAGE_EXPIRED`
- `AUTH_FORBIDDEN_IMAGE_ACCESS`

## 10. 消息与状态对应关系

为避免实现时状态混乱，补充一张消息到状态变更的映射表。

### 10.1 WS 消息与 Relay 内部状态映射

| 消息 | 触发状态变化 |
| - | - |
| `hello` | 建立或刷新 `deviceSession`，进入 `ONLINE` 候选状态 |
| `hello_ack` | Relay 向手机确认会话，分配 `sessionId` |
| `heartbeat` | 刷新 `lastSeenAt`，保持 `deviceSessionState = ONLINE` |
| `phone_state_update` | 更新设备的 `setupState` / `runtimeState` / `uplinkState` |
| `command` | 创建或推进 `commandRecord` 到 `DISPATCHED_TO_PHONE` |
| `command_ack` | `commandRecord.status -> ACKNOWLEDGED_BY_PHONE` |
| `command_status` | `commandRecord.status -> RUNNING` 或拍照上传相关中间态 |
| `command_result` | `commandRecord.status -> COMPLETED` |
| `command_error` | `commandRecord.status -> FAILED` |

### 10.2 `capture_photo` 关键 schema 串联

`capture_photo` 一次完整请求涉及下列消息：

1. 调用方 `POST /api/v1/commands`
2. Relay -> Phone `command(action = capture_photo)`
3. Phone -> Relay `command_ack`
4. Phone -> Relay `command_status(status = capturing)`
5. Phone -> Relay `command_status(status = image_captured)`
6. Phone -> Relay `PUT /api/v1/images/:imageId?uploadToken=...`
7. Phone -> Relay `command_status(status = uploading_image)`
8. Phone -> Relay `command_status(status = image_uploaded)`
9. Phone -> Relay `command_result(action = capture_photo)`
10. 调用方 `GET /api/v1/commands/:requestId`
11. 调用方 `GET /api/v1/images/:imageId`

### 10.3 `display_text` 关键 schema 串联

`display_text` 一次完整请求涉及下列消息：

1. 调用方 `POST /api/v1/commands`
2. Relay -> Phone `command(action = display_text)`
3. Phone -> Relay `command_ack`
4. Phone -> Relay `command_status(status = displaying)`
5. Phone -> Relay `command_result(action = display_text)`
6. 调用方 `GET /api/v1/commands/:requestId`

## 11. 建议的类型落地方式

为了让这份 schema 可直接落地到代码，建议仓库内按以下方式组织：

- `packages/protocol/`
  - 存放 WS message schema、HTTP DTO schema、枚举定义与统一 TS 类型出口
- `apps/relay-server/`
  - 使用 Elysia `t` 或 `zod` 对 HTTP / WS 入站消息做 runtime validation
- `apps/phone-android/`
  - 使用 Kotlin data class + sealed interface 对应 schema
- `packages/mcp-server/`
  - 只消费 HTTP DTO，不直接关心手机侧内部字段

## 12. 下一步

当本 schema 文档确认后，下一步建议继续写两份文档：

1. `capture_photo` 与 `display_text` 的详细时序表
2. Relay 侧 `image-manager`、`command-service`、`device-session-manager` 的模块设计文档
