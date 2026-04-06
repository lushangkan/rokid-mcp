# Relay Server Module Design

## 1. 目标

本文档定义 `apps/relay-server/` 在 MVP 阶段的核心模块设计，重点覆盖以下三个模块：

- `device-session-manager`
- `command-service`
- `image-manager`

本文档关注模块职责、数据模型、状态推进、接口边界、定时任务与模块间协作关系，作为后续 Elysia 实现的直接设计依据。

## 2. 依赖文档

本设计建立在以下协议文档之上：

- `docs/relay-protocol-constants.md`
- `docs/relay-protocol-schemas.md`
- `docs/relay-protocol-sequences.md`

如果本设计与协议文档冲突，以协议文档中的字段命名、状态常量与消息类型为准；如需调整协议，应先修改协议文档，再回改本设计。

## 3. 模块划分原则

本次模块设计遵循以下原则：

- 命令状态、设备会话状态、图片状态三类状态分开维护
- WebSocket 控制面与 HTTP 图片上传面分开处理
- 每个模块自行维护自己的 cleanup job，不设一个全局大管家式清理器
- 每个模块暴露清晰的 service API，不直接跨模块写对方内部存储
- 所有状态推进必须通过模块方法完成，不允许路由层直接改内部记录
- 入站消息先做协议校验，再交给对应模块处理

## 4. 模块关系总览

```text
                 +-----------------------+
                 |   Elysia Routes       |
                 | ws + http adapters    |
                 +-----------+-----------+
                             |
          +------------------+------------------+
          |                                     |
          v                                     v
  +----------------------+            +----------------------+
  | device-session-      |            | command-service      |
  | manager              |<---------->| request-store        |
  | session + heartbeat  |            | timeouts + lifecycle |
  +----------+-----------+            +----------+-----------+
             |                                     |
             |                                     v
             |                           +----------------------+
             +-------------------------->| image-manager        |
                                         | reservation + upload |
                                         +----------------------+
```

职责边界：

- `device-session-manager` 只关心 Phone 是否在线、会话是否合法、当前上报状态是什么
- `command-service` 只关心一个命令从创建到完成如何推进
- `image-manager` 只关心图片资源从预分配到上传、过期、删除如何推进

## 5. 顶层目录建议

建议 `apps/relay-server/src/` 按如下方式组织：

```text
src/
  app.ts
  index.ts
  config/
    env.ts
  lib/
    logger.ts
    ids.ts
    clock.ts
    errors.ts
  modules/
    device/
      device-session-manager.ts
      device-session-store.ts
      device-state-store.ts
    command/
      command-service.ts
      command-store.ts
      command-timeout-manager.ts
    image/
      image-manager.ts
      image-store.ts
      image-file-store.ts
      image-token-service.ts
  routes/
    ws-device.ts
    http-commands.ts
    http-devices.ts
    http-images.ts
```

## 6. 公共类型与基础能力

为了让三个模块保持低耦合，建议先抽出以下基础能力：

- `logger`: 统一日志接口
- `clock`: 统一获取当前毫秒时间，便于测试
- `ids`: 统一生成 `requestId`、`sessionId`、`imageId`
- `errors`: 统一创建业务错误对象
- `env`: 统一读取配置项，例如超时、最大图片大小、数据目录

建议配置项最少包括：

| 配置项 | 说明 |
| - | - |
| `RELAY_PORT` | 服务端监听端口 |
| `RELAY_HOST` | 服务端绑定地址 |
| `BASE_PUBLIC_URL` | 生成图片访问地址与 `statusUrl` 的基础地址 |
| `DEVICE_AUTH_SECRET` | 手机认证的共享密钥或 token 校验基础配置 |
| `IMAGE_DATA_DIR` | 图片文件保存目录 |
| `IMAGE_MAX_SIZE_BYTES` | 图片最大体积 |
| `IMAGE_UPLOAD_TTL_MS` | 图片上传窗口过期时间 |
| `COMMAND_ACK_TIMEOUT_MS` | 命令等待 `command_ack` 的超时 |
| `COMMAND_EXECUTION_TIMEOUT_MS` | 命令总执行超时 |
| `SESSION_HEARTBEAT_TIMEOUT_MS` | 会话心跳超时阈值 |
| `COMPLETED_COMMAND_RETENTION_MS` | 已完成命令保留时间 |
| `IMAGE_RETENTION_MS` | 已上传图片保留时间 |

## 7. `device-session-manager` 设计

## 7.1 模块目标

`device-session-manager` 负责维护 Phone 与 Relay 的 WebSocket 会话与设备在线视图。

它需要解决的问题：

- 哪个 `deviceId` 当前在线
- 当前在线设备对应哪个 `sessionId`
- 最近一次心跳与状态上报是什么
- Relay 如何把 `command` 路由给正确的 Phone WS 连接
- 会话断开、心跳超时后如何清理

## 7.2 模块职责

| 职责 | 说明 |
| - | - |
| 会话注册 | 处理 `hello` 成功后的 session 建立 |
| 会话更新 | 处理 `heartbeat` 与 `phone_state_update` |
| 路由能力 | 根据 `deviceId` 找到活跃 WS 连接 |
| 在线判定 | 提供设备在线 / 离线 / stale 判断 |
| 状态缓存 | 保存 `setupState`、`runtimeState`、`uplinkState`、`lastError` |
| 会话清理 | 清理心跳超时、连接关闭的 session |

不负责：

- 不推进命令生命周期
- 不处理图片上传
- 不生成 `requestId` 或 `imageId`

## 7.3 核心数据模型

### 7.3.1 `DeviceSessionRecord`

```ts
type DeviceSessionRecord = {
  sessionId: string
  deviceId: string
  socketRef: unknown
  sessionState: "OFFLINE" | "ONLINE" | "STALE" | "CLOSED"
  appVersion: string
  capabilities: Array<"display_text" | "capture_photo">
  connectedAt: number
  authenticatedAt: number
  lastSeenAt: number
  lastHeartbeatAt?: number
  lastHelloAt: number
  closedAt?: number
}
```

### 7.3.2 `DeviceRuntimeSnapshot`

```ts
type DeviceRuntimeSnapshot = {
  deviceId: string
  setupState: "UNINITIALIZED" | "INITIALIZED"
  runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR"
  uplinkState: "OFFLINE" | "CONNECTING" | "ONLINE" | "ERROR"
  activeCommandRequestId?: string | null
  lastErrorCode?: string | null
  lastErrorMessage?: string | null
  lastUpdatedAt: number
}
```

### 7.3.3 存储建议

MVP 可先用进程内 `Map`：

- `sessionsBySessionId: Map<string, DeviceSessionRecord>`
- `sessionIdByDeviceId: Map<string, string>`
- `runtimeSnapshotByDeviceId: Map<string, DeviceRuntimeSnapshot>`

## 7.4 对外 API 设计

### 7.4.1 会话注册与更新

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `registerHello(message, socketRef)` | `hello` 消息与当前 socket | `DeviceSessionRecord` | 校验认证并创建或替换 session |
| `confirmHello(sessionId)` | `sessionId` | `hello_ack payload` | 生成 `hello_ack` 需要的数据 |
| `markHeartbeat(deviceId, sessionId, heartbeat)` | 心跳消息 | 更新后的 session | 刷新心跳时间 |
| `applyPhoneStateUpdate(deviceId, update)` | `phone_state_update` | `DeviceRuntimeSnapshot` | 更新运行状态快照 |
| `closeSession(sessionId, reason)` | `sessionId`, reason | `void` | 关闭并清理会话 |

### 7.4.2 路由与查询

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `getActiveSessionByDeviceId(deviceId)` | `deviceId` | `DeviceSessionRecord | null` | 查找当前活跃设备会话 |
| `requireActiveSession(deviceId)` | `deviceId` | `DeviceSessionRecord` | 若离线则抛 `DEVICE_OFFLINE` |
| `getDeviceStatus(deviceId)` | `deviceId` | 聚合状态对象 | 供 `GET /devices/:deviceId/status` 使用 |
| `sendToDevice(deviceId, message)` | `deviceId`, WS 消息对象 | `void` | 通过 socket 发送消息 |
| `isDeviceAvailableForCommand(deviceId, action)` | `deviceId`, `action` | `boolean` 或抛错 | 用于命令前置校验 |

## 7.5 状态推进规则

### 7.5.1 `hello`

- 校验 `deviceId`
- 校验 `authToken`
- 若该 `deviceId` 已有旧会话：
  - 旧会话标记为 `CLOSED`
  - 关闭旧 socket
  - 新会话替换旧映射
- 新建 `sessionId`
- 新会话状态置为 `ONLINE`
- 建立对应的 `DeviceRuntimeSnapshot`

### 7.5.2 `heartbeat`

- 校验 `sessionId` 与 `deviceId` 是否匹配
- 刷新 `lastSeenAt`、`lastHeartbeatAt`
- 若会话此前是 `STALE`，恢复为 `ONLINE`
- 可顺便刷新 `activeCommandRequestId`

### 7.5.3 `phone_state_update`

- 更新 `setupState`
- 更新 `runtimeState`
- 更新 `uplinkState`
- 更新 `lastErrorCode` / `lastErrorMessage`
- 更新时间戳

## 7.6 cleanup job

该模块独立维护自己的心跳清理 job，例如每 `10s` 扫描一次。

处理规则：

- 若 `now - lastSeenAt > SESSION_HEARTBEAT_TIMEOUT_MS`
  - 将 `sessionState` 置为 `STALE`
- 若超过更长的关闭阈值，例如 `SESSION_HEARTBEAT_TIMEOUT_MS * 2`
  - 主动关闭 socket
  - 将 `sessionState` 置为 `CLOSED`
  - 从 `sessionIdByDeviceId` 移除映射
  - 将对应 `runtimeSnapshot.uplinkState` 置为 `OFFLINE`

建议对外方法：

- `startCleanupJob()`
- `stopCleanupJob()`

## 7.7 与其他模块的交互

- `command-service` 在创建命令前调用 `requireActiveSession(deviceId)`
- `command-service` 调 `sendToDevice(deviceId, commandMessage)` 下发命令
- `command-service` 可通过 `getDeviceStatus(deviceId)` 获取当前设备运行状态
- `image-manager` 一般不直接依赖它，但上传接口会校验 `X-Device-Id`，必要时可查询设备是否仍在线

## 8. `command-service` 设计

## 8.1 模块目标

`command-service` 负责维护所有命令的生命周期。

它需要解决的问题：

- MCP 发来的命令如何创建 `requestId`
- 命令如何下发到正确的 Phone
- 如何处理 `command_ack`、`command_status`、`command_result`、`command_error`
- 如何实现 `display_text` 与 `capture_photo` 不同的中间状态推进
- 如何处理超时、查询与结果归档

## 8.2 模块职责

| 职责 | 说明 |
| - | - |
| 命令创建 | 接收 MCP 请求，创建 `commandRecord` |
| 命令下发 | 通过 `device-session-manager` 向 Phone 发送 `command` |
| 状态推进 | 处理 `command_ack` / `command_status` / `command_result` / `command_error` |
| 超时管理 | 维护 ack 超时与执行超时 |
| 查询响应 | 提供 `GET /commands/:requestId` 所需数据 |
| 结果收口 | 汇总命令结果与错误信息 |

不负责：

- 不保存图片二进制文件
- 不维护 WebSocket 连接
- 不直接校验上传 token

## 8.3 核心数据模型

### 8.3.1 `CommandRecord`

```ts
type CommandRecord = {
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
  requestPayload: Record<string, unknown>
  imageId?: string
  createdAt: number
  updatedAt: number
  dispatchedAt?: number
  acknowledgedAt?: number
  completedAt?: number
  timeoutAt?: number
  result?: Record<string, unknown> | null
  error?: {
    code: string
    message: string
    retryable: boolean
    details?: Record<string, unknown>
  } | null
}
```

### 8.3.2 索引建议

- `commandsByRequestId: Map<string, CommandRecord>`
- `activeCommandByDeviceId: Map<string, string>`

其中：

- `activeCommandByDeviceId` 只跟踪非终态命令
- 对 `capture_photo`，MVP 应确保同一时间只有一个活跃独占命令

## 8.4 对外 API 设计

### 8.4.1 命令创建与下发

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `submitCommand(input)` | MCP HTTP DTO | `CommandRecord` | 创建命令并触发下发 |
| `buildCommandMessage(record)` | `CommandRecord` | WS `command` 消息 | 生成下发给 Phone 的消息 |
| `dispatchCommand(requestId)` | `requestId` | 更新后的 `CommandRecord` | 发送命令并推进状态 |

### 8.4.2 命令回执与状态推进

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `handleCommandAck(message)` | `command_ack` | `CommandRecord` | 推进到 `ACKNOWLEDGED_BY_PHONE` |
| `handleCommandStatus(message)` | `command_status` | `CommandRecord` | 推进到 `RUNNING` 或上传相关中间态 |
| `handleCommandResult(message)` | `command_result` | `CommandRecord` | 推进到 `COMPLETED` |
| `handleCommandError(message)` | `command_error` | `CommandRecord` | 推进到 `FAILED` |
| `markCommandTimeout(requestId, code)` | `requestId`, code | `CommandRecord` | 超时终态收口 |

### 8.4.3 查询与聚合

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `getCommand(requestId)` | `requestId` | `CommandRecord | null` | 查询原始命令记录 |
| `getCommandStatusResponse(requestId)` | `requestId` | HTTP DTO | 生成 `GET /commands/:requestId` 响应 |
| `isDeviceBusy(deviceId)` | `deviceId` | `boolean` | 校验是否已有独占命令 |
| `bindImageToCommand(requestId, imageId)` | `requestId`, `imageId` | `void` | 将图片资源绑定到拍照命令 |

## 8.5 核心流程设计

### 8.5.1 `submitCommand`

处理顺序建议：

1. 校验 `deviceId`、`action`、`payload`
2. 通过 `device-session-manager` 校验设备在线且可接单
3. 若 `action = capture_photo`
   - 校验设备是否已有活跃独占命令
   - 调用 `image-manager.reserveImageForCommand()` 预分配图片资源
4. 生成 `requestId`
5. 创建 `CommandRecord(status = CREATED)`
6. 如为拍照命令，写入 `imageId`
7. 调用 `dispatchCommand(requestId)`
8. 返回 HTTP DTO 给 MCP

### 8.5.2 `dispatchCommand`

处理顺序建议：

1. 读取 `CommandRecord`
2. 构建 WS `command` 消息
3. 调用 `device-session-manager.sendToDevice()`
4. 成功后将状态置为 `DISPATCHED_TO_PHONE`
5. 写入 `dispatchedAt`
6. 注册 ack 超时计时器

### 8.5.3 `handleCommandStatus`

该方法必须按 `action + payload.status` 联合判断。

处理规则：

- 任一合法 `command_status` 首次进入时可将命令推进到 `RUNNING`
- `capture_photo + image_captured`
  - 推进到 `WAITING_IMAGE_UPLOAD`
- `capture_photo + uploading_image`
  - 推进到 `IMAGE_UPLOADING`
  - 通知 `image-manager.markUploading(imageId)`
- `capture_photo + image_uploaded`
  - 命令回到 `RUNNING`
  - 但图片状态必须已是 `UPLOADED`

### 8.5.4 `handleCommandResult`

处理规则：

- 校验 `requestId` 存在
- 校验命令未处于终态
- 校验 `payload.action` 与记录一致
- 若是 `capture_photo`
  - 校验 `result.imageId` 与绑定的 `imageId` 一致
  - 校验 `image-manager` 中该图片状态已为 `UPLOADED`
- 将命令推进到 `COMPLETED`
- 写入 `result`、`completedAt`、`updatedAt`
- 从 `activeCommandByDeviceId` 移除

### 8.5.5 `handleCommandError`

处理规则：

- 校验命令未终态
- 将命令推进到 `FAILED`
- 写入标准化 `error`
- 如该命令绑定图片且图片仍是 `RESERVED` 或 `UPLOADING`
  - 调用 `image-manager.failImage(imageId, reason)`
- 从 `activeCommandByDeviceId` 移除

## 8.6 超时管理

建议将超时逻辑单独拆为 `command-timeout-manager`，但仍归 `command-service` 模块负责。

### 8.6.1 超时类型

| 类型 | 适用阶段 | 建议错误码 |
| - | - | - |
| ack 超时 | `DISPATCHED_TO_PHONE` 后迟迟未收到 `command_ack` | `COMMAND_ACK_TIMEOUT` |
| 执行超时 | 已收到 ack，但迟迟未收到 `command_result` / `command_error` | `COMMAND_EXECUTION_TIMEOUT` |

### 8.6.2 cleanup job

该模块独立维护自己的命令清理 job，例如每 `5s` 扫描一次。

处理内容：

- 将超时的非终态命令推进到 `TIMEOUT`
- 清理已完成且超过保留窗口的命令
- 清理 `activeCommandByDeviceId` 中残留的脏映射

建议对外方法：

- `startCleanupJob()`
- `stopCleanupJob()`

## 8.7 与其他模块的交互

- 依赖 `device-session-manager` 进行下发与在线校验
- 依赖 `image-manager` 进行拍照命令的图片预分配、上传状态校验与失败回收
- 向 `device-session-manager` 反向更新 `activeCommandRequestId` 可作为增强项，但最好通过明确 API 完成，不直接写 store

## 9. `image-manager` 设计

## 9.1 模块目标

`image-manager` 负责管理拍照图片资源的完整生命周期。

它需要解决的问题：

- 拍照命令提交时如何预分配 `imageId`
- 如何生成上传地址与上传 token
- 如何校验 Phone 的 HTTP 上传是否合法
- 如何落盘保存图片文件与元数据
- 如何提供图片读取能力
- 如何处理未上传过期、上传失败、清理删除

## 9.2 模块职责

| 职责 | 说明 |
| - | - |
| 图片预分配 | 在拍照命令创建时生成 `imageId` |
| 上传 token 生成 | 生成一次性 `uploadToken` |
| 上传校验 | 校验 token、设备、请求、类型、大小 |
| 文件保存 | 将原始 JPEG 写入存储目录 |
| 图片查询 | 为 `GET /images/:imageId` 提供元数据与文件定位 |
| 图片状态推进 | 维护 `RESERVED` -> `UPLOADING` -> `UPLOADED` 等状态 |
| 图片清理 | 处理过期未上传、失败上传、已过保留期图片 |

不负责：

- 不决定命令何时 `COMPLETED`
- 不直接维护 WebSocket 会话
- 不对 MCP 业务动作做校验

## 9.3 核心数据模型

### 9.3.1 `ImageRecord`

```ts
type ImageRecord = {
  imageId: string
  requestId: string
  deviceId: string
  status: "RESERVED" | "UPLOADING" | "UPLOADED" | "FAILED" | "EXPIRED" | "DELETED"
  mimeType?: "image/jpeg"
  size?: number
  width?: number
  height?: number
  sha256?: string
  filePath?: string
  publicUrl: string
  uploadTokenHash: string
  uploadTokenExpiresAt: number
  uploadStartedAt?: number
  uploadedAt?: number
  failedAt?: number
  deletedAt?: number
  createdAt: number
  updatedAt: number
}
```

### 9.3.2 存储建议

MVP 可先使用：

- `imagesById: Map<string, ImageRecord>`
- 磁盘目录：`apps/relay-server/data/images/`

文件命名建议：

```text
data/images/{imageId}.jpg
```

如需元数据旁路调试，可选写一个 sidecar：

```text
data/images/{imageId}.json
```

## 9.4 对外 API 设计

### 9.4.1 预分配与查询

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `reserveImageForCommand(input)` | `requestId`, `deviceId` | `ImageReservation` | 创建 `imageId`、`uploadToken`、`uploadUrl`、`publicUrl` |
| `getImage(imageId)` | `imageId` | `ImageRecord | null` | 查询图片记录 |
| `requireReadableImage(imageId)` | `imageId` | `ImageRecord` | 若未上传完成则抛错 |
| `getImageDownloadResponse(imageId)` | `imageId` | 文件流或 Elysia response | 提供下载响应 |

### 9.4.2 上传处理

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `beginUpload(input)` | `imageId`, `uploadToken`, headers | `ImageRecord` | 校验并推进到 `UPLOADING` |
| `completeUpload(input)` | 文件信息与校验信息 | `ImageRecord` | 写盘成功后推进到 `UPLOADED` |
| `failImage(imageId, reason)` | `imageId`, reason | `ImageRecord` | 将图片推进到 `FAILED` |
| `markUploading(imageId)` | `imageId` | `ImageRecord` | 响应 `command_status(uploading_image)` |

### 9.4.3 生命周期维护

| 方法 | 输入 | 输出 | 说明 |
| - | - | - | - |
| `expireReservedImages(now)` | `now` | `number` | 批量过期未上传图片 |
| `deleteImage(imageId, reason)` | `imageId`, reason | `void` | 删除文件与记录 |
| `cleanupExpiredAndDeleted(now)` | `now` | `number` | 执行过期清理 |

## 9.5 关键对象定义

### 9.5.1 `ImageReservation`

```ts
type ImageReservation = {
  imageId: string
  publicUrl: string
  uploadUrl: string
  uploadToken: string
  expiresAt: number
  maxSizeBytes: number
  contentType: "image/jpeg"
}
```

### 9.5.2 上传 token 设计

MVP 建议：

- 对外只下发明文 `uploadToken`
- 内部只存储 `uploadTokenHash`
- token 一次性使用
- token 与以下字段强绑定：
  - `imageId`
  - `requestId`
  - `deviceId`
- token 到期后不得续期复用

## 9.6 上传流程设计

### 9.6.1 `reserveImageForCommand`

处理顺序建议：

1. 生成 `imageId`
2. 生成随机 `uploadToken`
3. 计算 `uploadTokenHash`
4. 计算 `publicUrl = /api/v1/images/:imageId`
5. 计算 `uploadUrl = /api/v1/images/:imageId?uploadToken=...`
6. 创建 `ImageRecord(status = RESERVED)`
7. 返回 `ImageReservation`

### 9.6.2 `beginUpload`

处理顺序建议：

1. 根据 `imageId` 查找记录
2. 校验记录存在且状态允许上传
3. 校验 `uploadToken`
4. 校验 `X-Device-Id` 与 `X-Request-Id`
5. 校验 `Content-Type = image/jpeg`
6. 校验 `Content-Length` 不超限制
7. 将状态推进到 `UPLOADING`
8. 写入 `uploadStartedAt`

### 9.6.3 `completeUpload`

处理顺序建议：

1. 将请求体流写入临时文件
2. 统计 `size`
3. 可选计算 `sha256`
4. 校验最终体积不超上限
5. 临时文件原子移动到最终路径
6. 更新 `ImageRecord`
   - `status = UPLOADED`
   - `mimeType = image/jpeg`
   - `size`
   - `sha256`
   - `filePath`
   - `uploadedAt`
7. 使 `uploadToken` 失效，不可再次使用

### 9.6.4 上传失败处理

若以下任一情况发生，应推进到 `FAILED`：

- 流写入失败
- 内容为空
- 大小超限
- 校验和不一致
- 临时文件移动失败

同时应尽量清理残留临时文件。

## 9.7 cleanup job

该模块独立维护自己的图片清理 job，例如每 `30s` 扫描一次。

处理规则：

- `RESERVED` 且 `uploadTokenExpiresAt < now`
  - 推进到 `EXPIRED`
- `FAILED` / `EXPIRED` 且超过保留窗口
  - 删除物理文件
  - 推进到 `DELETED`
- `UPLOADED` 且超过图片保留窗口
  - 删除物理文件
  - 推进到 `DELETED`

建议对外方法：

- `startCleanupJob()`
- `stopCleanupJob()`

## 9.8 与其他模块的交互

- `command-service.submitCommand()` 在拍照命令创建阶段调用 `reserveImageForCommand()`
- `command-service.handleCommandResult()` 在拍照命令成功收口前校验图片必须已 `UPLOADED`
- `command-service.handleCommandError()` 可在必要时通知 `failImage()`
- 路由层的 `PUT /api/v1/images/:imageId` 只做协议解析，核心逻辑全部委托给 `image-manager`

## 10. 路由层与模块的调用关系

## 10.1 WebSocket 路由

### `hello`

```text
ws-device route
  -> validate envelope
  -> device-session-manager.registerHello()
  -> device-session-manager.confirmHello()
  -> send hello_ack
```

### `heartbeat`

```text
ws-device route
  -> validate envelope
  -> device-session-manager.markHeartbeat()
```

### `phone_state_update`

```text
ws-device route
  -> validate envelope
  -> device-session-manager.applyPhoneStateUpdate()
```

### `command_ack`

```text
ws-device route
  -> validate envelope
  -> command-service.handleCommandAck()
```

### `command_status`

```text
ws-device route
  -> validate envelope
  -> command-service.handleCommandStatus()
```

### `command_result`

```text
ws-device route
  -> validate envelope
  -> command-service.handleCommandResult()
```

### `command_error`

```text
ws-device route
  -> validate envelope
  -> command-service.handleCommandError()
```

## 10.2 HTTP 路由

### `POST /api/v1/commands`

```text
http-commands route
  -> validate request body
  -> command-service.submitCommand()
  -> return command response DTO
```

### `GET /api/v1/commands/:requestId`

```text
http-commands route
  -> command-service.getCommandStatusResponse()
  -> return status DTO
```

### `GET /api/v1/devices/:deviceId/status`

```text
http-devices route
  -> device-session-manager.getDeviceStatus()
  -> return device status DTO
```

### `PUT /api/v1/images/:imageId`

```text
http-images route
  -> parse imageId + uploadToken + headers
  -> image-manager.beginUpload()
  -> stream body to file-store
  -> image-manager.completeUpload()
  -> return upload response DTO
```

### `GET /api/v1/images/:imageId`

```text
http-images route
  -> image-manager.requireReadableImage()
  -> stream image file
```

## 11. 模块间约束与不变量

为避免后续实现时出现隐性耦合，建议直接冻结以下不变量：

1. 一个 `requestId` 只对应一个 `CommandRecord`
2. 一个拍照命令最多绑定一个 `imageId`
3. 一个 `imageId` 只允许一次成功上传
4. `capture_photo` 的 `command_result.imageId` 必须等于预分配 `imageId`
5. `imageRecord.status = UPLOADED` 不代表 `commandRecord.status = COMPLETED`
6. 非终态命令必须可通过 `requestId` 查询到
7. `device-session-manager` 不得直接改 `commandRecord`
8. `image-manager` 不得直接改 `commandRecord.status`
9. 路由层不得直接写三个模块的底层 store
10. 每个模块的 cleanup job 只能清理自己拥有的数据

## 12. MVP 实现优先级

建议按以下顺序实现：

### 阶段 1：`device-session-manager`

- `hello`
- `hello_ack`
- `heartbeat`
- `phone_state_update`
- 设备在线状态查询

目标：先让 Relay 知道哪个设备在线、能否下发命令。

### 阶段 2：`command-service`

- `POST /api/v1/commands`
- `command` 下发
- `command_ack`
- `command_result`
- `command_error`
- `GET /api/v1/commands/:requestId`

目标：先打通 `display_text` 最小命令闭环。

### 阶段 3：`image-manager`

- 图片预分配
- `PUT /api/v1/images/:imageId`
- `GET /api/v1/images/:imageId`
- 与 `capture_photo` 对接

目标：完成拍照上传主链路。

### 阶段 4：cleanup jobs 与健壮性

- 心跳超时清理
- 命令超时清理
- 图片过期与保留期清理

目标：让 Relay 在长时间运行下仍可自我清理。

## 13. 测试建议

每个模块最少应有以下测试：

### `device-session-manager`

- 新 `hello` 建立 session
- 同一 `deviceId` 重连替换旧 session
- `heartbeat` 刷新 `lastSeenAt`
- 心跳超时后进入 `STALE` / `CLOSED`

### `command-service`

- `display_text` 从 `CREATED` 到 `COMPLETED`
- `capture_photo` 从 `CREATED` 到 `WAITING_IMAGE_UPLOAD` 再到 `COMPLETED`
- 非法状态顺序拒绝，例如未 ack 先 result
- 超时命令推进到 `TIMEOUT`

### `image-manager`

- 预分配生成 `imageId` 与 `uploadUrl`
- 非法 token 上传被拒绝
- 成功上传推进到 `UPLOADED`
- 已上传图片不可重复上传
- 过期图片被清理到 `DELETED`

## 14. 下一步

当本模块设计确认后，下一步最适合进入实现准备：

1. 在 `packages/protocol/` 中落地常量与 schema
2. 在 `apps/relay-server/` 中初始化 Elysia + 模块骨架
3. 先实现 `device-session-manager` 与 `display_text` 主链路
