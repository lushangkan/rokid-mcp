# Relay Protocol Constants

## 1. 目标

本文档定义 `apps/relay-server/`、`apps/phone-android/`、`packages/mcp-server/` 在 MVP 阶段共用的通信常量表、状态机表与错误码表。

当前版本只覆盖以下范围：

- Phone -> Relay: WebSocket 控制面协议
- Phone -> Relay: HTTP 图片上传协议
- MCP -> Relay: HTTP 请求协议
- Relay 内部命令状态与图片状态定义

本文档优先锁定命名和常量，不展开实现代码细节。

## 2. 命名约束

为避免后续 Android、Relay、MCP 三端命名漂移，先固定以下约定：

- WebSocket Envelope 使用 `version` 字段，不使用 `v`
- WebSocket Envelope 使用 `type` 表示消息类型
- 业务命令统一使用 `action`
- 手机确认已接收命令的消息类型固定为 `command_ack`
- 命令执行中的状态变化消息类型固定为 `command_status`
- 命令执行成功消息类型固定为 `command_result`
- 命令执行失败消息类型固定为 `command_error`
- 图片内容不通过 WebSocket 传输，统一通过 HTTP `PUT` 上传原始二进制
- 图片资源统一使用 `/api/v1/images/:imageId` 作为资源路径

## 3. 基础协议常量表

| 常量名 | 值 | 说明 |
| - | - | - |
| `PROTOCOL_NAME` | `rokid-relay-protocol` | Relay 控制协议名称 |
| `PROTOCOL_VERSION` | `1.0` | 当前协议版本 |
| `API_VERSION` | `v1` | HTTP API 版本 |
| `WS_PATH_DEVICE` | `/ws/device` | 手机连接 Relay 的 WebSocket 路径 |
| `HTTP_PATH_COMMANDS` | `/api/v1/commands` | 命令提交入口 |
| `HTTP_PATH_COMMAND_BY_ID` | `/api/v1/commands/:requestId` | 命令状态查询 |
| `HTTP_PATH_DEVICE_STATUS` | `/api/v1/devices/:deviceId/status` | 设备状态查询 |
| `HTTP_PATH_IMAGE_BY_ID` | `/api/v1/images/:imageId` | 图片资源统一路径 |

## 4. WebSocket Envelope 字段表

所有 WS 消息统一使用以下 Envelope 结构：

```json
{
  "version": "1.0",
  "type": "command_ack",
  "deviceId": "rokid_glasses_01",
  "requestId": "req_125",
  "sessionId": "ses_001",
  "timestamp": 1710000000000,
  "payload": {}
}
```

| 字段名 | 类型 | 必填 | 说明 |
| - | - | - | - |
| `version` | `string` | 是 | 固定为 `1.0` |
| `type` | `string` | 是 | 消息类型 |
| `deviceId` | `string` | 是 | 手机设备唯一标识 |
| `requestId` | `string` | 否 | 与命令相关的消息必填 |
| `sessionId` | `string` | 否 | `hello_ack` 后可带回，后续手机可选择回传 |
| `timestamp` | `number` | 是 | Unix 毫秒时间戳 |
| `payload` | `object` | 是 | 具体消息体 |

## 5. WebSocket 消息类型常量表

### 5.1 Relay -> Phone

| `type` 常量 | 方向 | 用途 | MVP 是否必须 |
| - | - | - | - |
| `hello_ack` | Relay -> Phone | 确认手机已认证上线，并返回 `sessionId`、心跳参数 | 是 |
| `command` | Relay -> Phone | 下发业务命令，如 `display_text`、`capture_photo` | 是 |

### 5.2 Phone -> Relay

| `type` 常量 | 方向 | 用途 | MVP 是否必须 |
| - | - | - | - |
| `hello` | Phone -> Relay | 手机上线认证、上报能力与当前状态 | 是 |
| `heartbeat` | Phone -> Relay | 心跳保活 | 是 |
| `phone_state_update` | Phone -> Relay | 手机状态机变化上报 | 是 |
| `command_ack` | Phone -> Relay | 手机已接收并接受命令 | 是 |
| `command_status` | Phone -> Relay | 命令执行中的阶段状态变化 | 是 |
| `command_result` | Phone -> Relay | 命令成功完成 | 是 |
| `command_error` | Phone -> Relay | 命令失败完成 | 是 |

## 6. MCP -> Relay 动作类型表

这里区分两类动作：业务命令动作，与 HTTP API 操作动作。

### 6.1 业务命令动作 `action`

以下值用于 `POST /api/v1/commands` 请求体中的 `action` 字段，也会用于 Relay -> Phone 的 `command.payload.action`。

| `action` 常量 | 用途 | 是否独占命令 | 是否产生图片 |
| - | - | - | - |
| `display_text` | 在眼镜端显示文本 | 否 | 否 |
| `capture_photo` | 触发眼镜端拍照并上传图片 | 是 | 是 |

### 6.2 MCP 的 HTTP API 操作类型

| 操作名 | 方法 | 路径 | 用途 |
| - | - | - | - |
| `get_device_status` | `GET` | `/api/v1/devices/:deviceId/status` | 查询设备状态 |
| `submit_command` | `POST` | `/api/v1/commands` | 提交业务命令 |
| `get_command_status` | `GET` | `/api/v1/commands/:requestId` | 查询命令执行状态 |
| `get_image` | `GET` | `/api/v1/images/:imageId` | 读取图片资源 |

## 7. Relay -> Phone 命令动作表

| `action` 常量 | 说明 | 参数要求 |
| - | - | - |
| `display_text` | 显示文本 | `text` 必填，`durationMs` 必填 |
| `capture_photo` | 拍照并上传 | `quality` 可选，`image` 上传信息必填 |

## 8. Phone -> Relay 执行状态动作表

以下状态值仅用于 `type = command_status` 的 `payload.status` 字段，不表示最终成功或失败。

| `payload.status` 常量 | 适用命令 | 说明 |
| - | - | - |
| `forwarding_to_glasses` | 全部 | 手机正在通过蓝牙转发命令到眼镜 |
| `waiting_glasses_ack` | 全部 | 手机已转发，正在等待眼镜确认 |
| `executing` | 全部 | 眼镜端已经开始执行命令 |
| `displaying` | `display_text` | 眼镜端正在显示文本 |
| `capturing` | `capture_photo` | 眼镜端正在执行拍照 |
| `image_captured` | `capture_photo` | 图片已拍好，准备上传 |
| `uploading_image` | `capture_photo` | 手机正在向 Relay 上传图片 |
| `image_uploaded` | `capture_photo` | 图片已经上传成功，等待最终收口 |

约束：

- `command_status` 只表示执行中的阶段状态
- 成功终态只允许使用 `command_result`
- 失败终态只允许使用 `command_error`

## 9. 手机状态常量表

### 9.1 初始化状态 `setupState`

| 常量 | 说明 |
| - | - |
| `UNINITIALIZED` | 未完成必要初始化，如权限、目标设备、Relay 配置未就绪 |
| `INITIALIZED` | 已完成必要初始化，可进入运行态 |

### 9.2 运行状态 `runtimeState`

| 常量 | 说明 |
| - | - |
| `DISCONNECTED` | 已初始化，但未建立蓝牙链路 |
| `CONNECTING` | 正在建立蓝牙链路 |
| `READY` | 蓝牙链路与眼镜握手均已完成，可接收命令 |
| `BUSY` | 正在执行独占任务，例如拍照 |
| `ERROR` | 当前出现异常，需要用户干预或内部恢复 |

### 9.3 上行网络状态 `uplinkState`

| 常量 | 说明 |
| - | - |
| `OFFLINE` | 未连接 Relay |
| `CONNECTING` | 正在连接 Relay |
| `ONLINE` | 已连接并认证成功 |
| `ERROR` | 上行链路异常 |

### 9.4 Relay 侧设备会话状态 `deviceSessionState`

| 常量 | 说明 |
| - | - |
| `OFFLINE` | 没有活动 WebSocket 会话 |
| `ONLINE` | 有活动会话且心跳正常 |
| `STALE` | 心跳超时宽限期内，待清理 |
| `CLOSED` | 会话已关闭 |

## 10. 命令状态常量表

以下状态由 Relay 内部 `commandRecord.status` 维护，和 WS 消息类型 `command_status` 不是同一概念。

| 常量 | 说明 | 是否终态 |
| - | - | - |
| `CREATED` | Relay 已创建命令记录，尚未下发给手机 | 否 |
| `DISPATCHED_TO_PHONE` | Relay 已通过 WebSocket 下发命令 | 否 |
| `ACKNOWLEDGED_BY_PHONE` | 已收到 `command_ack` | 否 |
| `RUNNING` | 已收到至少一个 `command_status`，命令处于执行中 | 否 |
| `WAITING_IMAGE_UPLOAD` | 仅拍照命令；图片已拍好，等待 HTTP 上传 | 否 |
| `IMAGE_UPLOADING` | 仅拍照命令；图片上传中 | 否 |
| `COMPLETED` | 已收到 `command_result`，命令成功完成 | 是 |
| `FAILED` | 已收到 `command_error`，命令失败完成 | 是 |
| `TIMEOUT` | 命令超时未完成 | 是 |

## 11. 图片状态常量表

以下状态由 `image-manager` 维护。

| 常量 | 说明 | 是否终态 |
| - | - | - |
| `RESERVED` | 图片资源已预分配，尚未开始上传 | 否 |
| `UPLOADING` | 手机正在上传图片 | 否 |
| `UPLOADED` | 图片已上传成功，可读取 | 是 |
| `FAILED` | 图片上传失败 | 是 |
| `EXPIRED` | 上传窗口过期，资源失效 | 是 |
| `DELETED` | 图片已被清理任务删除 | 是 |

## 12. 状态机表

### 12.1 手机初始化状态机

| 当前状态 | 触发事件 | 下一个状态 | 说明 |
| - | - | - | - |
| `UNINITIALIZED` | 蓝牙权限已授予、目标眼镜已配置、Relay 地址已配置 | `INITIALIZED` | 满足最小初始化条件 |
| `INITIALIZED` | 任一初始化前置条件失效 | `UNINITIALIZED` | 例如权限被撤销、目标设备被清空 |

### 12.2 手机运行状态机

| 当前状态 | 触发事件 | 下一个状态 | 说明 |
| - | - | - | - |
| `DISCONNECTED` | 开始连接蓝牙 | `CONNECTING` | 发起蓝牙连接 |
| `CONNECTING` | 蓝牙连接成功且眼镜握手完成 | `READY` | 可以接受命令 |
| `CONNECTING` | 蓝牙连接失败 | `DISCONNECTED` | 回到断开态 |
| `READY` | 接收独占命令并开始执行 | `BUSY` | 例如 `capture_photo` |
| `BUSY` | 命令成功完成 | `READY` | 退出忙碌态 |
| `BUSY` | 命令失败但链路仍健康 | `READY` | 可恢复失败返回就绪态 |
| `READY` | 蓝牙断开 | `DISCONNECTED` | 本地链路断开 |
| `BUSY` | 蓝牙断开 | `DISCONNECTED` | 任务中断 |
| `ANY` | 本地不可恢复异常 | `ERROR` | 例如相机资源异常、内部状态损坏 |
| `ERROR` | 错误恢复成功 | `DISCONNECTED` 或 `READY` | 取决于蓝牙是否仍在线 |

### 12.3 命令状态机

| 当前状态 | 触发事件 | 下一个状态 | 备注 |
| - | - | - | - |
| `CREATED` | Relay 成功发出 WS `command` | `DISPATCHED_TO_PHONE` | 命令已出站 |
| `DISPATCHED_TO_PHONE` | 收到 `command_ack` | `ACKNOWLEDGED_BY_PHONE` | 手机已接收 |
| `ACKNOWLEDGED_BY_PHONE` | 收到任意 `command_status` | `RUNNING` | 进入执行态 |
| `RUNNING` | 拍照命令收到 `image_captured` | `WAITING_IMAGE_UPLOAD` | 仅 `capture_photo` |
| `WAITING_IMAGE_UPLOAD` | HTTP 上传开始 | `IMAGE_UPLOADING` | 仅 `capture_photo` |
| `IMAGE_UPLOADING` | 上传成功，但尚未收到最终结果 | `RUNNING` | 等待 `command_result` |
| `RUNNING` | 收到 `command_result` | `COMPLETED` | 成功终态 |
| `CREATED` / `DISPATCHED_TO_PHONE` / `ACKNOWLEDGED_BY_PHONE` / `RUNNING` / `WAITING_IMAGE_UPLOAD` / `IMAGE_UPLOADING` | 收到 `command_error` | `FAILED` | 失败终态 |
| 任意非终态 | 超时 | `TIMEOUT` | 超时终态 |

### 12.4 图片状态机

| 当前状态 | 触发事件 | 下一个状态 | 备注 |
| - | - | - | - |
| `RESERVED` | 手机开始 `PUT` 上传 | `UPLOADING` | 进入上传态 |
| `UPLOADING` | 上传成功并通过文件校验 | `UPLOADED` | 可读取 |
| `UPLOADING` | 上传失败 | `FAILED` | 失败终态 |
| `RESERVED` | 上传窗口过期 | `EXPIRED` | 未上传超时 |
| `UPLOADING` | 上传超时或连接中断未恢复 | `FAILED` | 失败终态 |
| `UPLOADED` / `FAILED` / `EXPIRED` | 清理任务删除文件与记录 | `DELETED` | 清理终态 |

## 13. 错误码命名规范

错误码统一使用大写蛇形命名，并按命名空间分类：

- `PROTOCOL_*`
- `AUTH_*`
- `DEVICE_*`
- `SESSION_*`
- `COMMAND_*`
- `BLUETOOTH_*`
- `CAMERA_*`
- `DISPLAY_*`
- `IMAGE_*`
- `UPLOAD_*`
- `INTERNAL_*`

## 14. 错误码常量表

### 14.1 协议层错误 `PROTOCOL_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `PROTOCOL_UNSUPPORTED_VERSION` | WS / HTTP | `version` 不受支持 | `400` | 否 |
| `PROTOCOL_INVALID_MESSAGE_TYPE` | WS | `type` 非法或未知 | `400` | 否 |
| `PROTOCOL_INVALID_PAYLOAD` | WS / HTTP | `payload` 结构不合法 | `400` | 否 |
| `PROTOCOL_REQUEST_ID_REQUIRED` | WS | 命令相关消息缺少 `requestId` | `400` | 否 |
| `PROTOCOL_DEVICE_ID_REQUIRED` | WS / HTTP | 缺少 `deviceId` | `400` | 否 |
| `PROTOCOL_TIMESTAMP_INVALID` | WS | `timestamp` 格式非法 | `400` | 否 |
| `PROTOCOL_ACTION_INVALID` | WS / HTTP | `action` 非法或不支持 | `400` | 否 |
| `PROTOCOL_STATE_INVALID` | WS | 状态字段非法 | `400` | 否 |
| `PROTOCOL_DUPLICATE_REQUEST_ID` | WS / HTTP | `requestId` 重复冲突 | `409` | 否 |

### 14.2 认证与权限错误 `AUTH_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `AUTH_DEVICE_TOKEN_INVALID` | WS | 手机认证 token 非法 | `401` | 否 |
| `AUTH_DEVICE_TOKEN_EXPIRED` | WS | 手机认证 token 已过期 | `401` | 视重新认证而定 |
| `AUTH_UPLOAD_TOKEN_INVALID` | HTTP PUT image | 上传 token 非法 | `401` | 否 |
| `AUTH_UPLOAD_TOKEN_EXPIRED` | HTTP PUT image | 上传 token 过期 | `401` | 否 |
| `AUTH_UPLOAD_TOKEN_MISMATCH` | HTTP PUT image | token 与 `imageId`、`deviceId`、`requestId` 不匹配 | `403` | 否 |
| `AUTH_FORBIDDEN_IMAGE_ACCESS` | HTTP GET image | 无权读取图片 | `403` | 否 |

### 14.3 设备与会话错误 `DEVICE_*` / `SESSION_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `DEVICE_NOT_FOUND` | HTTP | 设备不存在 | `404` | 否 |
| `DEVICE_OFFLINE` | HTTP / WS | 设备当前不在线 | `409` | 是 |
| `DEVICE_NOT_INITIALIZED` | HTTP / WS | 手机未完成初始化 | `409` | 否，需要用户处理 |
| `DEVICE_BUSY` | HTTP / WS | 设备忙，拒绝新的独占任务 | `409` | 是 |
| `DEVICE_STATE_CONFLICT` | HTTP / WS | 当前设备状态不允许该动作 | `409` | 是 |
| `SESSION_NOT_FOUND` | WS / HTTP | 会话不存在 | `404` | 是 |
| `SESSION_STALE` | WS / HTTP | 会话心跳过期 | `409` | 是 |
| `SESSION_ALREADY_CLOSED` | WS / HTTP | 会话已关闭 | `409` | 是 |

### 14.4 命令错误 `COMMAND_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `COMMAND_NOT_FOUND` | HTTP | `requestId` 不存在 | `404` | 否 |
| `COMMAND_ALREADY_FINISHED` | WS / HTTP | 命令已结束，不接受重复结果 | `409` | 否 |
| `COMMAND_ACK_TIMEOUT` | HTTP / WS | 下发后长时间未收到 `command_ack` | `504` | 是 |
| `COMMAND_EXECUTION_TIMEOUT` | HTTP / WS | 执行阶段超时 | `504` | 是 |
| `COMMAND_RESULT_INVALID` | WS | `command_result` 内容不合法 | `422` | 否 |
| `COMMAND_ERROR_INVALID` | WS | `command_error` 内容不合法 | `422` | 否 |
| `COMMAND_STATUS_INVALID` | WS | `command_status` 状态不合法 | `422` | 否 |
| `COMMAND_SEQUENCE_INVALID` | WS | 命令状态上报顺序不合法 | `409` | 否 |

### 14.5 蓝牙与终端执行错误 `BLUETOOTH_*` / `CAMERA_*` / `DISPLAY_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `BLUETOOTH_DISCONNECTED` | Phone / Relay | 蓝牙链路断开 | `409` | 是 |
| `BLUETOOTH_CONNECT_FAILED` | Phone / Relay | 蓝牙连接失败 | `502` | 是 |
| `BLUETOOTH_SEND_FAILED` | Phone / Relay | 手机向眼镜发命令失败 | `502` | 是 |
| `BLUETOOTH_READ_FAILED` | Phone / Relay | 手机从眼镜读取结果失败 | `502` | 是 |
| `CAMERA_UNAVAILABLE` | Phone / Glasses | 相机不可用 | `409` | 是 |
| `CAMERA_CAPTURE_FAILED` | Phone / Glasses | 拍照失败 | `500` | 是 |
| `CAMERA_PERMISSION_DENIED` | Phone / Glasses | 相机权限不足 | `403` | 否 |
| `DISPLAY_FAILED` | Phone / Glasses | 文本显示失败 | `500` | 是 |
| `DISPLAY_INVALID_TEXT` | Phone / Glasses | 文本内容非法 | `400` | 否 |

### 14.6 图片与上传错误 `IMAGE_*` / `UPLOAD_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `IMAGE_NOT_FOUND` | HTTP | 图片资源不存在 | `404` | 否 |
| `IMAGE_NOT_READY` | HTTP | 图片尚未上传完成 | `409` | 是 |
| `IMAGE_ALREADY_UPLOADED` | HTTP PUT | 图片已上传完成，不允许再次上传 | `409` | 否 |
| `IMAGE_EXPIRED` | HTTP | 图片资源已过期 | `410` | 否 |
| `IMAGE_CONTENT_TYPE_INVALID` | HTTP PUT | 上传 MIME 类型非法 | `400` | 否 |
| `IMAGE_TOO_LARGE` | HTTP PUT | 图片超出最大限制 | `413` | 否 |
| `IMAGE_EMPTY_BODY` | HTTP PUT | 上传体为空 | `400` | 否 |
| `UPLOAD_IN_PROGRESS` | HTTP PUT | 当前图片已在上传中 | `409` | 是 |
| `UPLOAD_TIMEOUT` | HTTP PUT / Relay | 上传超时 | `504` | 是 |
| `UPLOAD_STREAM_BROKEN` | HTTP PUT | 上传连接中断 | `502` | 是 |
| `UPLOAD_STORAGE_FAILED` | HTTP PUT / Relay | 文件写入存储失败 | `500` | 是 |
| `UPLOAD_CHECKSUM_MISMATCH` | HTTP PUT / Relay | 文件校验失败 | `422` | 是 |

### 14.7 服务端内部错误 `INTERNAL_*`

| 错误码 | 适用层 | 含义 | 建议 HTTP 状态码 | 是否可重试 |
| - | - | - | - | - |
| `INTERNAL_STORE_ERROR` | Relay | 内部状态存储失败 | `500` | 是 |
| `INTERNAL_SERIALIZATION_ERROR` | Relay | 序列化或反序列化异常 | `500` | 是 |
| `INTERNAL_UNEXPECTED_STATE` | Relay | 状态机进入非法状态 | `500` | 否 |
| `INTERNAL_UNKNOWN_ERROR` | 全局 | 未分类未知错误 | `500` | 视情况而定 |

## 15. WebSocket 关闭码建议映射

| 关闭码 | 常量建议 | 说明 |
| - | - | - |
| `1000` | `WS_CLOSE_NORMAL` | 正常关闭 |
| `1002` | `WS_CLOSE_PROTOCOL_ERROR` | 协议字段非法 |
| `1008` | `WS_CLOSE_POLICY_VIOLATION` | 认证失败或权限问题 |
| `1011` | `WS_CLOSE_INTERNAL_ERROR` | 服务端内部错误 |
| `1013` | `WS_CLOSE_TRY_AGAIN_LATER` | 服务暂时不可用 |

## 16. 命令 Payload 字段常量表

### 16.1 `display_text` command payload

| 字段 | 类型 | 必填 | 说明 |
| - | - | - | - |
| `action` | `string` | 是 | 固定 `display_text` |
| `params.text` | `string` | 是 | 要显示的文本 |
| `params.durationMs` | `number` | 是 | 文本显示持续时间 |
| `params.priority` | `string` | 否 | 预留字段，MVP 可不启用 |

### 16.2 `capture_photo` command payload

| 字段 | 类型 | 必填 | 说明 |
| - | - | - | - |
| `action` | `string` | 是 | 固定 `capture_photo` |
| `params.quality` | `string` | 否 | `low` / `medium` / `high` |
| `image.imageId` | `string` | 是 | Relay 预分配图片 ID |
| `image.uploadUrl` | `string` | 是 | 手机上传地址 |
| `image.method` | `string` | 是 | 固定 `PUT` |
| `image.contentType` | `string` | 是 | 建议 `image/jpeg` |
| `image.expiresAt` | `number` | 是 | 上传地址过期时间 |
| `image.maxSizeBytes` | `number` | 是 | 最大允许图片体积 |

## 17. `phone_state_update` Payload 字段表

| 字段 | 类型 | 必填 | 说明 |
| - | - | - | - |
| `setupState` | `string` | 是 | `UNINITIALIZED` / `INITIALIZED` |
| `runtimeState` | `string` | 是 | `DISCONNECTED` / `CONNECTING` / `READY` / `BUSY` / `ERROR` |
| `uplinkState` | `string` | 是 | `OFFLINE` / `CONNECTING` / `ONLINE` / `ERROR` |
| `lastErrorCode` | `string \| null` | 否 | 最近一次错误码 |
| `lastErrorMessage` | `string \| null` | 否 | 最近一次错误说明 |
| `activeCommandRequestId` | `string \| null` | 否 | 当前执行中的命令 ID |

## 18. 统一语义约束

为确保三端协议实现保持一致，补充以下约束：

- `type` 只表达消息类别，不承载业务动作含义
- `payload.action` 只允许出现在 `type = command` 中
- `payload.status` 只允许出现在 `type = command_status` 中
- 成功终态永远只通过 `command_result` 表达
- 失败终态永远只通过 `command_error` 表达
- 图片上传永远使用 HTTP `PUT /api/v1/images/:imageId`
- 图片读取永远使用 HTTP `GET /api/v1/images/:imageId`

## 19. 下一步

在本常量表冻结后，下一份文档应继续细化以下内容：

1. 每一种 WS / HTTP 消息的完整 JSON schema
2. `capture_photo` 与 `display_text` 的完整时序表
3. `image-manager` 的 token 校验规则与过期策略
4. Relay 侧 Elysia 路由与模块拆分
