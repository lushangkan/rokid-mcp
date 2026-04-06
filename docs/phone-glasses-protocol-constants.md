# Phone-Glasses Protocol Constants
## 1. 目标

本文档定义 `apps/phone-android/` 与 `apps/glasses-android/` 在 MVP 阶段共用的本地链路协议常量表、状态机表与错误码表。

当前版本只覆盖以下范围：

- Phone <-> Glasses: Bluetooth Classic RFCOMM 本地协议
- Phone -> Glasses: 业务命令下发
- Glasses -> Phone: 命令回执、状态、结果、错误
- Glasses -> Phone: 图片分块回传
- Phone / Glasses: 协议会话保活与错误收口所需常量

## 2. 命名约束

为避免 Android 两端命名漂移，固定以下约定：

- 本地协议名称使用 `rokid-local-link`
- 本地协议版本使用 `1.0`
- 外层消息字段使用 `version`、`type`、`requestId`、`transferId`、`timestamp`、`payload`
- 命令类型统一为 `command`
- 成功终态统一为 `command_result`
- 失败终态统一为 `command_error`
- 图片分块使用 `chunk_start`、`chunk_data`、`chunk_end`
- 业务动作统一使用 `action`
- 执行阶段状态统一使用 `status`

## 3. 基础协议常量表

| 常量名 | 值 | 说明 |
| - | - | - |
| `LOCAL_PROTOCOL_NAME` | `rokid-local-link` | 本地链路协议名 |
| `LOCAL_PROTOCOL_VERSION` | `1.0` | 当前协议版本 |
| `FRAME_MAGIC` | `0x524B4C31` | 帧魔数，表示 `RKL1` |
| `FRAME_HEADER_MAX_BYTES` | `8192` | header JSON 最大字节数 |
| `CHUNK_SIZE_BYTES` | `16384` | 固定图片 chunk 大小，最后一块可小于该值 |
| `MAX_IMAGE_SIZE_BYTES` | `10485760` | MVP 图片最大体积，10 MB |
| `HELLO_ACK_TIMEOUT_MS` | `5000` | 建链握手超时 |
| `PING_INTERVAL_MS` | `5000` | 空闲心跳间隔 |
| `PONG_TIMEOUT_MS` | `5000` | pong 等待超时 |
| `IDLE_TIMEOUT_MS` | `15000` | 会话失活阈值 |
| `PING_MAX_MISSES` | `3` | 最大连续丢失 pong 次数 |
| `IMAGE_MIME_TYPE_JPEG` | `image/jpeg` | 当前唯一支持的图片 MIME |
| `CHUNK_CHECKSUM_ALGO` | `CRC32` | 单 chunk 校验算法 |
| `FILE_CHECKSUM_ALGO` | `SHA-256` | 整文件校验算法 |

## 4. Kotlin 类型约定

MVP 阶段默认 Phone / Glasses 协议实现一起升级，不额外追求前向兼容。

因此建议采用以下 Kotlin 约定：

- 闭集协议值使用 `enum class`
- 固定协议常量使用 `const val`
- 开放文本字段继续使用 `String`
- DTO 与快照使用 `data class`
- 离散事件使用 `sealed interface`

适合用 `enum class` 的字段包括：

- `type`
- `action`
- `runtimeState`
- `status`
- `role`

## 5. 消息类型常量表

| `type` 常量 | 方向 | 用途 | MVP 是否必须 |
| - | - | - | - |
| `hello` | Phone -> Glasses | 建链后声明身份、协议版本、支持动作 | 是 |
| `hello_ack` | Glasses -> Phone | 确认或拒绝当前会话 | 是 |
| `ping` | Phone -> Glasses | 空闲保活 | 是 |
| `pong` | Glasses -> Phone | `ping` 应答 | 是 |
| `command` | Phone -> Glasses | 下发业务命令 | 是 |
| `command_ack` | Glasses -> Phone | 接受命令 | 是 |
| `command_status` | Glasses -> Phone | 上报执行中状态 | 是 |
| `command_result` | Glasses -> Phone | 命令成功完成 | 是 |
| `command_error` | Glasses -> Phone | 命令失败完成 | 是 |
| `chunk_start` | Glasses -> Phone | 图片传输开始 | 是，`capture_photo` 必须 |
| `chunk_data` | Glasses -> Phone | 图片数据块 | 是，`capture_photo` 必须 |
| `chunk_end` | Glasses -> Phone | 图片传输结束 | 是，`capture_photo` 必须 |

## 6. 业务动作常量表

| `action` 常量 | 用途 | 是否独占命令 | 是否产生图片 |
| - | - | - | - |
| `display_text` | 眼镜显示文本 | 否 | 否 |
| `capture_photo` | 眼镜拍照并回传图片 | 是 | 是 |

## 7. 命令执行阶段状态常量表

以下状态仅用于 `type = command_status`。

| `status` 常量 | 适用命令 | 说明 |
| - | - | - |
| `executing` | 全部 | Glasses 已进入命令执行逻辑 |
| `displaying` | `display_text` | 眼镜正在显示文本 |
| `capturing` | `capture_photo` | 眼镜正在拍照 |

补充说明：

- `forwarding_to_glasses` 与 `waiting_glasses_ack` 属于 Phone 桥接态，不由 Glasses 上报
- `uploading_image` 与 `image_uploaded` 属于 Phone -> Relay 阶段，也不由 Glasses 上报

## 8. 角色常量表

| `role` 常量 | 说明 |
| - | - |
| `PHONE` | 本地链路客户端，负责连接与桥接 |
| `GLASSES` | 本地链路服务端，负责执行命令 |

## 9. 本地运行状态常量表

### 9.1 协议运行状态 `runtimeState`

| 常量 | 说明 |
| - | - |
| `READY` | 链路健康，可接单 |
| `BUSY` | 链路健康，但正在执行独占任务 |
| `ERROR` | 链路异常或会话异常 |

### 9.2 Phone 蓝牙连接状态

| 常量 | 说明 |
| - | - |
| `SOCKET_DISCONNECTED` | 蓝牙未连接 |
| `SOCKET_CONNECTING` | 正在建立 RFCOMM 连接 |
| `SOCKET_CONNECTED` | socket 已建立 |
| `SOCKET_STALE` | 保活失败，连接失活待关闭 |
| `SOCKET_ERROR` | 连接或读写异常 |

### 9.3 Phone 协议会话状态

| 常量 | 说明 |
| - | - |
| `SESSION_IDLE` | 尚未开始协议握手 |
| `HANDSHAKING` | 正在执行 `hello / hello_ack` |
| `SESSION_READY` | 会话已建立 |
| `EXECUTING_EXCLUSIVE` | 正在执行独占命令 |
| `SESSION_ERROR` | 会话错误 |

### 9.4 Glasses 协议会话状态

| 常量 | 说明 |
| - | - |
| `LISTENING` | 等待接入 |
| `PAIR_CHECK` | 校验远端设备是否已 pair |
| `HANDSHAKING` | 正在执行 `hello / hello_ack` |
| `SESSION_READY` | 会话已建立 |
| `EXECUTING_EXCLUSIVE` | 正在执行独占命令 |
| `SESSION_ERROR` | 会话错误 |

## 10. Phone `runtimeState` 映射表

| 蓝牙连接状态 | 协议会话状态 | `runtimeState` |
| - | - | - |
| `SOCKET_DISCONNECTED` | 任意 | `DISCONNECTED` |
| `SOCKET_CONNECTING` | 任意 | `CONNECTING` |
| `SOCKET_CONNECTED` | `HANDSHAKING` | `CONNECTING` |
| `SOCKET_CONNECTED` | `SESSION_READY` | `READY` |
| `SOCKET_CONNECTED` | `EXECUTING_EXCLUSIVE` | `BUSY` |
| `SOCKET_STALE` | 任意 | `ERROR` |
| `SOCKET_ERROR` | 任意 | `ERROR` |
| `SOCKET_CONNECTED` | `SESSION_ERROR` | `ERROR` |

约束：

- `BUSY` 仅表示链路健康但在执行独占任务
- 链路异常、保活失败、会话错乱，不得映射为 `BUSY`

## 11. 图片传输常量与约束

| 项目 | 约束 |
| - | - |
| `transferId` | 单次图片传输唯一标识 |
| `chunkSizeBytes` | 固定为 `16384` |
| `chunkChecksum` | 每个 chunk 单独计算，算法固定 `CRC32` |
| `sha256` | 整文件校验；在 `chunk_end` 后验证 |
| `offset` | 必须与累计写入长度一致 |
| `size` | 必须等于当前 `bodyLength` |

## 12. 错误码命名规范

错误码统一使用大写蛇形命名，并优先与 Relay 侧前缀保持一致：

- `PROTOCOL_*`
- `COMMAND_*`
- `BLUETOOTH_*`
- `CAMERA_*`
- `DISPLAY_*`
- `IMAGE_*`
- `UPLOAD_*`
- `INTERNAL_*`

## 13. 本地链路错误码表

### 13.1 协议层错误

| 错误码 | 含义 | 是否关闭会话 |
| - | - | - |
| `PROTOCOL_UNSUPPORTED_VERSION` | 协议版本不支持 | 是 |
| `PROTOCOL_INVALID_MESSAGE_TYPE` | 消息类型非法 | 是 |
| `PROTOCOL_INVALID_PAYLOAD` | payload 结构非法 | 是 |
| `PROTOCOL_HEADER_INVALID` | header JSON / header CRC 非法 | 是 |
| `PROTOCOL_FRAME_TOO_LARGE` | 长度超限 | 是 |
| `PROTOCOL_REQUEST_ID_REQUIRED` | 命令消息缺少 `requestId` | 是 |
| `PROTOCOL_TRANSFER_ID_REQUIRED` | chunk 消息缺少 `transferId` | 是 |

### 13.2 蓝牙与链路错误

| 错误码 | 含义 | 是否关闭会话 |
| - | - | - |
| `BLUETOOTH_CONNECT_FAILED` | RFCOMM 连接失败 | 是 |
| `BLUETOOTH_DISCONNECTED` | 链路断开 | 是 |
| `BLUETOOTH_READ_FAILED` | 读取失败 | 是 |
| `BLUETOOTH_SEND_FAILED` | 写入失败 | 是 |
| `BLUETOOTH_HELLO_TIMEOUT` | `hello_ack` 超时 | 是 |
| `BLUETOOTH_PONG_TIMEOUT` | `pong` 超时 | 是 |

### 13.3 命令执行错误

| 错误码 | 含义 | 是否关闭会话 |
| - | - | - |
| `COMMAND_BUSY` | 当前存在独占命令 | 否 |
| `COMMAND_TIMEOUT` | 本地命令执行超时 | 否 |
| `COMMAND_SEQUENCE_INVALID` | 命令事件顺序非法 | 否 |
| `DISPLAY_FAILED` | 文本显示失败 | 否 |
| `CAMERA_UNAVAILABLE` | 相机不可用 | 否 |
| `CAMERA_CAPTURE_FAILED` | 拍照失败 | 否 |

### 13.4 文件传输错误

| 错误码 | 含义 | 是否关闭会话 |
| - | - | - |
| `IMAGE_TRANSFER_INCOMPLETE` | 图片数据不完整 | 否 |
| `IMAGE_CHECKSUM_MISMATCH` | chunk 或整文件校验失败 | 否 |
| `IMAGE_TOO_LARGE` | 图片超限 | 否 |
| `IMAGE_STORAGE_WRITE_FAILED` | 临时文件写入失败 | 否 |
| `UPLOAD_FAILED` | 上传 Relay 失败 | 否 |

## 14. 错误处理总原则

MVP 不做重试，任意 command 相关错误都立即终止当前命令。

冻结以下规则：

1. 各底层模块先做自己的局部 cleanup
2. 然后向上抛出 typed error event 或异常
3. 最终命令失败只能由单一 owner 收口
4. Phone 端最终 owner 是 `RelayCommandBridge`
5. Glasses 端最终 owner 是 `CommandDispatcher`
6. 上传 Relay 失败后，本地临时文件必须立即删除

## 15. 后续自动化建议

当协议常量与错误码正式代码化后，建议增加自动脚本 / CI 检查：

- 任一模块的 error code 变动时，自动检测其他模块是否同步
- 若 `docs/`、Android 端、Relay 端常量漂移，则直接 fail CI

## 16. 下一步

在本常量表冻结后，建议配套维护以下文档：

1. `docs/phone-glasses-protocol-schemas.md`
2. `docs/phone-glasses-protocol-sequences.md`
3. `docs/phone-glasses-module-design.md`
