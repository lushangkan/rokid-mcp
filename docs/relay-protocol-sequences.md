# Relay Protocol Sequences

## 1. 目标

本文档定义 `display_text` 与 `capture_photo` 在 MVP 阶段的详细时序表、失败分支与 MCP 轮询观察视角。

本文档用于补充以下文档：

- `docs/relay-protocol-constants.md`
- `docs/relay-protocol-schemas.md`

本文档关注点是消息流转与状态推进，不展开具体代码实现。

## 2. 通用前提与约束

在以下时序成立前，默认满足这些前置条件：

- Phone 与 Relay 已建立 `WS /ws/device` 活跃连接
- Phone 已发送 `hello`，Relay 已返回 `hello_ack`
- 当前 `deviceId` 已通过认证
- MCP 通过 HTTP 调用 Relay，不直接参与 WebSocket 通信
- MCP 通过 `GET /api/v1/commands/:requestId` 轮询命令状态
- `capture_photo` 的图片上传只允许 Phone 使用 `PUT /api/v1/images/:imageId?uploadToken=...`
- `capture_photo` 的图片读取统一走 `GET /api/v1/images/:imageId`

补充约束：

- Relay -> Phone 控制消息当前只保留 `hello_ack` 与 `command`
- `display_text` 不是独占命令，但 MVP 仍建议按单命令串行执行
- `capture_photo` 是独占命令，Phone 执行期间 `runtimeState` 应进入 `BUSY`
- 成功终态只允许通过 `command_result` 表达
- 失败终态只允许通过 `command_error` 表达
- 图片上传完成不等于命令完成，`capture_photo` 仍需单独发送 `command_result`

## 3. `display_text` 详细时序表

### 3.1 主成功链路

| 步骤 | 发起方 -> 接收方 | 通道 | 消息 / 接口 | 关键字段 | Relay 内部状态变化 | 说明 |
| - | - | - | - | - | - | - |
| 1 | MCP -> Relay | HTTP | `POST /api/v1/commands` | `deviceId`, `action=display_text`, `payload.text`, `payload.durationMs` | 创建 `commandRecord`, `status=CREATED` | Relay 校验设备存在、在线、已初始化、当前允许执行显示命令 |
| 2 | Relay -> MCP | HTTP | 提交成功响应 | `requestId`, `statusUrl`, `status=CREATED` 或 `DISPATCHED_TO_PHONE` | 无或推进到 `DISPATCHED_TO_PHONE` | MCP 收到 `requestId` 后即可开始轮询 |
| 3 | Relay -> Phone | WS | `type=command` | `requestId`, `payload.action=display_text`, `payload.params.text`, `payload.params.durationMs`, `payload.timeoutMs` | `status=DISPATCHED_TO_PHONE` | Relay 通过控制面 WebSocket 下发显示命令 |
| 4 | Phone -> Relay | WS | `type=command_ack` | `requestId`, `payload.action=display_text`, `payload.acceptedAt`, `payload.runtimeState=READY` | `status=ACKNOWLEDGED_BY_PHONE` | Phone 确认已接收命令；若不能接受，应改发 `command_error` |
| 5 | Phone -> Relay | WS | `type=command_status` | `payload.status=forwarding_to_glasses` | `status=RUNNING` | Phone 开始通过蓝牙向眼镜转发命令 |
| 6 | Phone -> Relay | WS | `type=command_status` | `payload.status=waiting_glasses_ack` | 保持 `RUNNING` | Phone 已向眼镜发出命令，正在等待眼镜确认 |
| 7 | Phone -> Relay | WS | `type=command_status` | `payload.status=executing` | 保持 `RUNNING` | 眼镜端已开始处理显示命令 |
| 8 | Phone -> Relay | WS | `type=command_status` | `payload.status=displaying` | 保持 `RUNNING` | 眼镜端正在显示文本 |
| 9 | Phone -> Relay | WS | `type=command_result` | `payload.action=display_text`, `payload.result.displayed=true`, `payload.result.durationMs`, `payload.completedAt` | `status=COMPLETED` | 显示命令执行完成 |
| 10 | MCP -> Relay | HTTP | `GET /api/v1/commands/:requestId` | `requestId` | 无 | MCP 轮询到 `status=COMPLETED`，拿到最终结果 |

### 3.2 状态推进摘要

#### Relay 命令状态推进

```text
CREATED
  -> DISPATCHED_TO_PHONE
  -> ACKNOWLEDGED_BY_PHONE
  -> RUNNING
  -> COMPLETED
```

#### Phone 运行状态推进

```text
READY
  -> READY
```

说明：

- `display_text` 在 MVP 中不强制要求切换到 `BUSY`
- 如果后续要支持高优先级显示或显示队列，可以再扩展更细的本地执行状态

### 3.3 MCP 轮询观察视角

| 轮询阶段 | MCP 常见观察结果 | 说明 |
| - | - | - |
| 提交后立即轮询 | `CREATED` 或 `DISPATCHED_TO_PHONE` | Relay 已创建命令，可能还未收到 Phone 确认 |
| 中间轮询 | `ACKNOWLEDGED_BY_PHONE` 或 `RUNNING` | 命令已进入 Phone 执行流程 |
| 最终轮询 | `COMPLETED` | `result.action=display_text`，`result.displayed=true` |

### 3.4 失败与超时分支表

| 场景 | 触发点 | 对外可见消息 | Relay 最终状态 | 建议错误码 |
| - | - | - | - | - |
| 设备离线 | 步骤 1 前校验 | HTTP 错误响应 | 不创建命令或直接拒绝 | `DEVICE_OFFLINE` |
| 设备未初始化 | 步骤 1 前校验 | HTTP 错误响应 | 不创建命令或直接拒绝 | `DEVICE_NOT_INITIALIZED` |
| 设备状态冲突 | 步骤 1 前校验 | HTTP 错误响应 | 不创建命令或直接拒绝 | `DEVICE_STATE_CONFLICT` |
| 下发后未收到 `command_ack` | 步骤 3 后超时 | 无或后续超时状态响应 | `TIMEOUT` | `COMMAND_ACK_TIMEOUT` |
| Phone 蓝牙转发失败 | 步骤 5 | `type=command_error` | `FAILED` | `BLUETOOTH_SEND_FAILED` |
| 眼镜显示失败 | 步骤 7 或 8 | `type=command_error` | `FAILED` | `DISPLAY_FAILED` |
| 执行阶段整体超时 | 步骤 5 到 8 之间 | 无或 `type=command_error` | `TIMEOUT` | `COMMAND_EXECUTION_TIMEOUT` |

## 4. `capture_photo` 详细时序表

### 4.1 主成功链路

| 步骤 | 发起方 -> 接收方 | 通道 | 消息 / 接口 | 关键字段 | Relay 内部状态变化 | 说明 |
| - | - | - | - | - | - | - |
| 1 | MCP -> Relay | HTTP | `POST /api/v1/commands` | `deviceId`, `action=capture_photo`, `payload.quality` | 创建 `commandRecord`, `status=CREATED` | Relay 校验设备在线、已初始化、且没有冲突的独占任务 |
| 2 | Relay 内部 | 内部调用 | 预分配图片资源 | 生成 `imageId`, `uploadToken`, `image.url`, `uploadUrl`, `expiresAt` | 创建 `imageRecord`, `status=RESERVED` | `image-manager` 负责预留图片资源与上传令牌 |
| 3 | Relay -> MCP | HTTP | 提交成功响应 | `requestId`, `statusUrl`, `image.imageId`, `image.url`, `image.uploadStatus=RESERVED` | 保持 `CREATED` 或推进到 `DISPATCHED_TO_PHONE` | MCP 提前拿到最终图片资源地址，但不会拿到 `uploadUrl` |
| 4 | Relay -> Phone | WS | `type=command` | `requestId`, `payload.action=capture_photo`, `payload.params.quality`, `payload.image.imageId`, `payload.image.uploadUrl`, `payload.image.method=PUT`, `payload.image.contentType=image/jpeg`, `payload.image.expiresAt`, `payload.image.maxSizeBytes` | `status=DISPATCHED_TO_PHONE` | Relay 下发拍照命令与图片上传信息 |
| 5 | Phone -> Relay | WS | `type=command_ack` | `requestId`, `payload.action=capture_photo`, `payload.acceptedAt`, `payload.runtimeState=BUSY` | `status=ACKNOWLEDGED_BY_PHONE` | Phone 接受独占命令后进入 `BUSY` |
| 6 | Phone -> Relay | WS | `type=command_status` | `payload.status=forwarding_to_glasses` | `status=RUNNING` | Phone 正在通过蓝牙向眼镜转发拍照命令 |
| 7 | Phone -> Relay | WS | `type=command_status` | `payload.status=waiting_glasses_ack` | 保持 `RUNNING` | Phone 等待眼镜确认 |
| 8 | Phone -> Relay | WS | `type=command_status` | `payload.status=capturing` | 保持 `RUNNING` | 眼镜端正在拍照 |
| 9 | Phone -> Relay | WS | `type=command_status` | `payload.status=image_captured`, `payload.image.imageId` | `status=WAITING_IMAGE_UPLOAD` | 图片已拍好，等待 HTTP 上传 |
| 10 | Phone -> Relay | WS | `type=command_status` | `payload.status=uploading_image`, `payload.image.imageId`, `payload.image.uploadStartedAt` | `status=IMAGE_UPLOADING` | Phone 准备开始上传图片 |
| 11 | Phone -> Relay | HTTP | `PUT /api/v1/images/:imageId?uploadToken=...` | Header: `X-Device-Id`, `X-Request-Id`, `Content-Type=image/jpeg`; Body: raw bytes | `imageRecord.status=UPLOADING` | Relay 校验 `uploadToken`、设备、请求、内容类型与大小 |
| 12 | Relay -> Phone | HTTP | 上传成功响应 | `image.status=UPLOADED`, `image.url`, `image.size`, `image.sha256`, `uploadedAt` | `imageRecord.status=UPLOADED` | 图片文件写入完成，图片资源可读 |
| 13 | Phone -> Relay | WS | `type=command_status` | `payload.status=image_uploaded`, `payload.image.imageId`, `payload.image.uploadedAt` | `commandRecord.status=RUNNING` | 图片上传完成，但命令尚未正式收口 |
| 14 | Phone -> Relay | WS | `type=command_result` | `payload.action=capture_photo`, `payload.result.imageId`, `payload.result.mimeType`, `payload.result.size`, `payload.result.width`, `payload.result.height`, `payload.result.sha256`, `payload.completedAt` | `commandRecord.status=COMPLETED` | 拍照命令成功完成 |
| 15 | MCP -> Relay | HTTP | `GET /api/v1/commands/:requestId` | `requestId` | 无 | MCP 轮询到 `status=COMPLETED`，拿到图片元数据 |
| 16 | MCP -> Relay | HTTP | `GET /api/v1/images/:imageId` | `imageId` | 无 | MCP 或 AI 读取图片二进制内容 |

### 4.2 状态推进摘要

#### Relay 命令状态推进

```text
CREATED
  -> DISPATCHED_TO_PHONE
  -> ACKNOWLEDGED_BY_PHONE
  -> RUNNING
  -> WAITING_IMAGE_UPLOAD
  -> IMAGE_UPLOADING
  -> RUNNING
  -> COMPLETED
```

#### Relay 图片状态推进

```text
RESERVED
  -> UPLOADING
  -> UPLOADED
```

#### Phone 运行状态推进

```text
READY
  -> BUSY
  -> READY
```

说明：

- `IMAGE_UPLOADING -> RUNNING` 表示图片已经上传成功，但命令仍等待最终 `command_result`
- 图片上传成功与命令成功完成不是同一事件，必须分开建模

### 4.3 MCP 轮询观察视角

| 轮询阶段 | MCP 常见观察结果 | 说明 |
| - | - | - |
| 提交后立即轮询 | `CREATED` 或 `DISPATCHED_TO_PHONE` | Relay 已接受命令，且通常已返回预分配的 `imageId` |
| Phone 已接受命令 | `ACKNOWLEDGED_BY_PHONE` | Phone 已接单并进入拍照流程 |
| 执行中 | `RUNNING` | Phone / Glasses 正在执行拍照 |
| 等待上传 | `WAITING_IMAGE_UPLOAD` | 图片已拍好，但尚未开始上传或上传尚未建立 |
| 上传中 | `IMAGE_UPLOADING` | 图片正在通过 HTTP 上传到 Relay |
| 最终完成 | `COMPLETED` | `result.action=capture_photo`，并包含图片元数据与资源地址 |

### 4.4 失败与超时分支表

| 场景 | 触发点 | 对外可见消息 | 命令最终状态 | 图片最终状态 | 建议错误码 |
| - | - | - | - | - | - |
| 设备忙 | 步骤 1 前校验 | HTTP 错误响应 | 不创建命令或直接拒绝 | 无 | `DEVICE_BUSY` |
| 设备离线 | 步骤 1 前校验 | HTTP 错误响应 | 不创建命令或直接拒绝 | 无 | `DEVICE_OFFLINE` |
| 下发后未收到 `command_ack` | 步骤 4 后超时 | 无或后续超时状态响应 | `TIMEOUT` | `RESERVED` 或后续 `EXPIRED` | `COMMAND_ACK_TIMEOUT` |
| Phone 蓝牙转发失败 | 步骤 6 或 7 | `type=command_error` | `FAILED` | `RESERVED` | `BLUETOOTH_SEND_FAILED` |
| 相机不可用 | 步骤 8 | `type=command_error` | `FAILED` | `RESERVED` | `CAMERA_UNAVAILABLE` |
| 拍照失败 | 步骤 8 或 9 | `type=command_error` | `FAILED` | `RESERVED` | `CAMERA_CAPTURE_FAILED` |
| 上传令牌非法 | 步骤 11 | HTTP 错误响应，Phone 随后发 `command_error` | `FAILED` | `FAILED` 或保持 `RESERVED` 后过期 | `AUTH_UPLOAD_TOKEN_INVALID` / `AUTH_UPLOAD_TOKEN_MISMATCH` |
| 图片过大 | 步骤 11 | HTTP 错误响应，Phone 随后发 `command_error` | `FAILED` | `FAILED` | `IMAGE_TOO_LARGE` |
| 存储写入失败 | 步骤 11 或 12 | HTTP 错误响应，Phone 随后发 `command_error` | `FAILED` | `FAILED` | `UPLOAD_STORAGE_FAILED` |
| 上传连接中断 | 步骤 11 | HTTP 错误响应或连接中断，Phone 随后发 `command_error` | `FAILED` 或 `TIMEOUT` | `FAILED` | `UPLOAD_STREAM_BROKEN` / `UPLOAD_TIMEOUT` |
| 图片已上传但未收到最终 `command_result` | 步骤 13 或 14 后超时 | 无或超时状态响应 | `TIMEOUT` | `UPLOADED` | `COMMAND_EXECUTION_TIMEOUT` |

## 5. 两条链路的对比摘要

| 维度 | `display_text` | `capture_photo` |
| - | - | - |
| 是否独占命令 | 否 | 是 |
| 是否预分配图片资源 | 否 | 是 |
| 是否涉及 HTTP 上传 | 否 | 是 |
| 是否会进入 `WAITING_IMAGE_UPLOAD` | 否 | 是 |
| 是否会进入 `IMAGE_UPLOADING` | 否 | 是 |
| 最终结果核心字段 | `displayed`, `durationMs` | `imageId`, `mimeType`, `size`, `width`, `height` |
| Phone 典型运行状态变化 | `READY -> READY` | `READY -> BUSY -> READY` |

## 6. 状态推进与消息对应表

### 6.1 `display_text`

| 消息 | 命令状态变化 | 备注 |
| - | - | - |
| `POST /api/v1/commands` | `CREATED` | MCP 提交命令 |
| Relay `command` | `DISPATCHED_TO_PHONE` | Relay 已出站发送 |
| Phone `command_ack` | `ACKNOWLEDGED_BY_PHONE` | Phone 已接收命令 |
| Phone `command_status` | `RUNNING` | 执行中阶段开始 |
| Phone `command_result` | `COMPLETED` | 成功终态 |
| Phone `command_error` | `FAILED` | 失败终态 |

### 6.2 `capture_photo`

| 消息 / 事件 | 命令状态变化 | 图片状态变化 | 备注 |
| - | - | - | - |
| `POST /api/v1/commands` | `CREATED` | `RESERVED` | Relay 预创建图片资源 |
| Relay `command` | `DISPATCHED_TO_PHONE` | 无 | Relay 已向 Phone 下发命令 |
| Phone `command_ack` | `ACKNOWLEDGED_BY_PHONE` | 无 | Phone 已接收命令 |
| Phone `command_status(capturing)` | `RUNNING` | 无 | 拍照执行中 |
| Phone `command_status(image_captured)` | `WAITING_IMAGE_UPLOAD` | 无 | 图片已拍好，待上传 |
| Phone `command_status(uploading_image)` | `IMAGE_UPLOADING` | `UPLOADING` | Phone 开始上传 |
| HTTP `PUT /api/v1/images/:imageId` 成功 | 无 | `UPLOADED` | 图片资源上传完成 |
| Phone `command_status(image_uploaded)` | `RUNNING` | `UPLOADED` | 图片已上传，命令待收口 |
| Phone `command_result` | `COMPLETED` | `UPLOADED` | 成功终态 |
| Phone `command_error` | `FAILED` | 保持当前或失败 | 失败终态 |

## 7. 对实现的直接要求

为了让以上时序在代码里可稳定落地，Relay 侧至少需要满足以下要求：

- `command-service` 负责命令状态推进与超时管理
- `image-manager` 负责图片资源预分配、上传校验、状态推进与清理
- `device-session-manager` 负责 Phone 会话、心跳、在线状态与 `deviceId` 映射
- 命令状态与图片状态必须分开维护，不允许用一个字段混合表达
- `capture_photo` 的 HTTP 上传成功后，不得自动把命令直接置为 `COMPLETED`
- `command_result` 与 `command_error` 必须作为命令最终收口事件处理

## 8. 下一步

在本文档确认后，下一份建议补充的设计文档是：

1. `image-manager` 模块设计文档
2. `command-service` 模块设计文档
3. `device-session-manager` 模块设计文档
