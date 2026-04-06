# Rokid 协议消息编号表

## 1. 目标

本文档为 `docs/protocol/overview.md` 提供稳定的消息编号，便于在讨论实现、联调日志和错误排查时直接引用。

编号规则：

- `MR-*`：`MCP <-> Relay`
- `RP-*`：`Relay <-> Phone`
- `PG-*`：`Phone <-> Glasses`

## 2. MCP <-> Relay

| 编号 | 方向 | 通道 | 消息 / 接口 | 用途 | 动作 |
| - | - | - | - | - | - |
| `MR-01` | MCP -> Relay | HTTP | `GET /api/v1/devices/:deviceId/status` | 查询设备状态 | 通用 |
| `MR-02` | Relay -> MCP | HTTP JSON | `GetDeviceStatusResponse` | 返回设备状态快照 | 通用 |
| `MR-03` | MCP -> Relay | HTTP | `POST /api/v1/commands` | 提交 `display_text` 命令 | `display_text` |
| `MR-04` | Relay -> MCP | HTTP JSON | `SubmitDisplayTextCommandResponse` | 返回显示命令受理结果 | `display_text` |
| `MR-05` | MCP -> Relay | HTTP | `POST /api/v1/commands` | 提交 `capture_photo` 命令 | `capture_photo` |
| `MR-06` | Relay -> MCP | HTTP JSON | `SubmitCapturePhotoCommandResponse` | 返回拍照命令受理结果和预分配图片资源 | `capture_photo` |
| `MR-07` | MCP -> Relay | HTTP | `GET /api/v1/commands/:requestId` | 轮询命令状态 | 通用 |
| `MR-08` | Relay -> MCP | HTTP JSON | `GetCommandStatusResponse` | 返回命令生命周期状态和终态 | 通用 |
| `MR-09` | MCP -> Relay | HTTP | `GET /api/v1/images/:imageId` | 下载图片二进制 | `capture_photo` |
| `MR-10` | Relay -> MCP | HTTP Binary | `image/jpeg` body | 返回图片内容 | `capture_photo` |
| `MR-11` | Relay -> MCP | HTTP JSON | `ErrorResponse` | 返回通用错误 | 通用 |

## 3. Relay <-> Phone

### 3.1 会话与保活

| 编号 | 方向 | 通道 | 消息 | 用途 | 动作 |
| - | - | - | - | - | - |
| `RP-01` | Phone -> Relay | WS | `hello` | 手机上线、认证、上报能力与状态 | 通用 |
| `RP-02` | Relay -> Phone | WS | `hello_ack` | Relay 确认会话并下发心跳参数 | 通用 |
| `RP-03` | Phone -> Relay | WS | `heartbeat` | 心跳保活 | 通用 |
| `RP-04` | Phone -> Relay | WS | `phone_state_update` | 主动上报状态变化 | 通用 |

### 3.2 `display_text`

| 编号 | 方向 | 通道 | 消息 | 用途 |
| - | - | - | - | - |
| `RP-10` | Relay -> Phone | WS | `command(action=display_text)` | 下发显示命令 |
| `RP-11` | Phone -> Relay | WS | `command_ack(action=display_text)` | 确认已接收命令 |
| `RP-12` | Phone -> Relay | WS | `command_status(status=forwarding_to_glasses)` | 开始向眼镜转发 |
| `RP-13` | Phone -> Relay | WS | `command_status(status=waiting_glasses_ack)` | 等待眼镜确认 |
| `RP-14` | Phone -> Relay | WS | `command_status(status=executing)` | 眼镜端进入执行逻辑 |
| `RP-15` | Phone -> Relay | WS | `command_status(status=displaying)` | 眼镜端正在显示文本 |
| `RP-16` | Phone -> Relay | WS | `command_result(action=display_text)` | 命令成功完成 |
| `RP-17` | Phone -> Relay | WS | `command_error(action=display_text)` | 命令失败收口 |

### 3.3 `capture_photo`

| 编号 | 方向 | 通道 | 消息 | 用途 |
| - | - | - | - | - |
| `RP-20` | Relay -> Phone | WS | `command(action=capture_photo)` | 下发拍照命令与上传信息 |
| `RP-21` | Phone -> Relay | WS | `command_ack(action=capture_photo)` | 确认接单并进入 `BUSY` |
| `RP-22` | Phone -> Relay | WS | `command_status(status=forwarding_to_glasses)` | 开始向眼镜转发 |
| `RP-23` | Phone -> Relay | WS | `command_status(status=waiting_glasses_ack)` | 等待眼镜确认 |
| `RP-24` | Phone -> Relay | WS | `command_status(status=capturing)` | 眼镜正在拍照 |
| `RP-25` | Phone -> Relay | WS | `command_status(status=image_captured)` | 图片已拍好，等待上传 |
| `RP-26` | Phone -> Relay | WS | `command_status(status=uploading_image)` | Phone 开始上传图片 |
| `RP-27` | Phone -> Relay | HTTP | `PUT /api/v1/images/:imageId?uploadToken=...` | 上传图片二进制 |
| `RP-28` | Relay -> Phone | HTTP JSON | `PutImageResponse` | 确认图片上传成功 |
| `RP-29` | Phone -> Relay | WS | `command_status(status=image_uploaded)` | 图片已上传，命令待最终收口 |
| `RP-30` | Phone -> Relay | WS | `command_result(action=capture_photo)` | 命令成功完成 |
| `RP-31` | Phone -> Relay | WS | `command_error(action=capture_photo)` | 命令失败收口 |

## 4. Phone <-> Glasses

### 4.1 握手与保活

| 编号 | 方向 | 通道 | 消息 | 用途 | 动作 |
| - | - | - | - | - | - |
| `PG-01` | Phone -> Glasses | RFCOMM LocalLink | `hello` | 声明身份、协议版本、支持动作 | 通用 |
| `PG-02` | Glasses -> Phone | RFCOMM LocalLink | `hello_ack` | 接受或拒绝会话 | 通用 |
| `PG-03` | Phone -> Glasses | RFCOMM LocalLink | `ping` | 空闲保活 | 通用 |
| `PG-04` | Glasses -> Phone | RFCOMM LocalLink | `pong` | `ping` 应答 | 通用 |

### 4.2 `display_text`

| 编号 | 方向 | 通道 | 消息 | 用途 |
| - | - | - | - | - |
| `PG-10` | Phone -> Glasses | RFCOMM LocalLink | `command(action=DISPLAY_TEXT)` | 下发显示命令 |
| `PG-11` | Glasses -> Phone | RFCOMM LocalLink | `command_ack(action=DISPLAY_TEXT)` | 接受命令 |
| `PG-12` | Glasses -> Phone | RFCOMM LocalLink | `command_status(status=EXECUTING)` | 进入执行逻辑 |
| `PG-13` | Glasses -> Phone | RFCOMM LocalLink | `command_status(status=DISPLAYING)` | 正在显示文本 |
| `PG-14` | Glasses -> Phone | RFCOMM LocalLink | `command_result(action=DISPLAY_TEXT)` | 成功完成 |
| `PG-15` | Glasses -> Phone | RFCOMM LocalLink | `command_error(action=DISPLAY_TEXT)` | 失败收口 |

### 4.3 `capture_photo`

| 编号 | 方向 | 通道 | 消息 | 用途 |
| - | - | - | - | - |
| `PG-20` | Phone -> Glasses | RFCOMM LocalLink | `command(action=CAPTURE_PHOTO)` | 下发拍照命令 |
| `PG-21` | Glasses -> Phone | RFCOMM LocalLink | `command_ack(action=CAPTURE_PHOTO)` | 接受独占命令 |
| `PG-22` | Glasses -> Phone | RFCOMM LocalLink | `command_status(status=CAPTURING)` | 正在拍照 |
| `PG-23` | Glasses -> Phone | RFCOMM LocalLink | `chunk_start` | 声明开始发送图片 |
| `PG-24` | Glasses -> Phone | RFCOMM LocalLink | `chunk_data` | 发送图片块 |
| `PG-25` | Glasses -> Phone | RFCOMM LocalLink | `chunk_end` | 声明图片数据发送完成 |
| `PG-26` | Glasses -> Phone | RFCOMM LocalLink | `command_result(action=CAPTURE_PHOTO)` | 本地成功收口，表示图片已完整交付给 Phone |
| `PG-27` | Glasses -> Phone | RFCOMM LocalLink | `command_error(action=CAPTURE_PHOTO)` | 本地失败收口 |

## 5. 跨层引用建议

建议在日志、抓包和联调记录中直接引用编号，例如：

- “`RP-26` 已发送，但没有收到 `RP-28`”
- “`PG-24` 的 `offset` 与累计写入长度不一致”
- “`MR-08` 已返回 `COMPLETED`，接下来进入 `MR-09`”
