# Rokid 协议状态机总表

## 1. 目标

本文档集中整理三段链路中最关键的状态机，解决两个问题：

- 同一个请求在不同层分别处于什么状态
- 不同状态之间如何推进、谁负责收口

配套入口：`overview.md`

## 2. 设备与链路状态总表

### 2.1 Relay / Phone / LocalLink 状态集合

| 层 | 状态字段 | 取值 |
| - | - | - |
| Relay | `sessionState` | `OFFLINE` / `ONLINE` / `STALE` / `CLOSED` |
| Phone | `setupState` | `UNINITIALIZED` / `INITIALIZED` |
| Phone | `runtimeState` | `DISCONNECTED` / `CONNECTING` / `READY` / `BUSY` / `ERROR` |
| Phone | `uplinkState` | `OFFLINE` / `CONNECTING` / `ONLINE` / `ERROR` |
| LocalLink | `LocalRuntimeState` | `READY` / `BUSY` / `ERROR` |

### 2.2 Phone `runtimeState` 与本地链路映射

| Phone 蓝牙 / 会话视角 | LocalLink 视角 | Phone `runtimeState` |
| - | - | - |
| RFCOMM 未连接 | 无活动会话 | `DISCONNECTED` |
| RFCOMM 连接中 | 握手未完成 | `CONNECTING` |
| RFCOMM 已连接，`hello/hello_ack` 完成 | `LocalRuntimeState.READY` | `READY` |
| 独占命令执行中 | `LocalRuntimeState.BUSY` | `BUSY` |
| 会话错误、链路断开、保活失败 | `LocalRuntimeState.ERROR` 或会话关闭 | `ERROR` / `DISCONNECTED` |

## 3. Relay 命令状态机

### 3.1 通用命令状态机

| 当前状态 | 触发事件 | 下一个状态 | 适用动作 |
| - | - | - | - |
| `CREATED` | Relay 成功发出 WS `command` | `DISPATCHED_TO_PHONE` | 全部 |
| `DISPATCHED_TO_PHONE` | 收到 `command_ack` | `ACKNOWLEDGED_BY_PHONE` | 全部 |
| `ACKNOWLEDGED_BY_PHONE` | 收到任意 `command_status` | `RUNNING` | 全部 |
| `RUNNING` | 收到 `command_result` | `COMPLETED` | 全部 |
| `CREATED` / `DISPATCHED_TO_PHONE` / `ACKNOWLEDGED_BY_PHONE` / `RUNNING` | 收到 `command_error` | `FAILED` | 全部 |
| 任意非终态 | 超时 | `TIMEOUT` | 全部 |

### 3.2 `capture_photo` 扩展状态机

| 当前状态 | 触发事件 | 下一个状态 | 说明 |
| - | - | - | - |
| `RUNNING` | 收到 `command_status(image_captured)` | `WAITING_IMAGE_UPLOAD` | 图片已拍好，待上传 |
| `WAITING_IMAGE_UPLOAD` | 收到 `command_status(uploading_image)` | `IMAGE_UPLOADING` | Phone 开始上传 |
| `IMAGE_UPLOADING` | HTTP `PUT image` 成功且图片已落盘 | `RUNNING` | 图片已上传，但命令未最终收口 |
| `RUNNING` | 收到 `command_result(capture_photo)` | `COMPLETED` | 只有最终结果才能成功收口 |

关键规则：

- 图片上传成功不等于命令完成
- `capture_photo` 的命令终态必须由 `command_result` 或 `command_error` 收口

## 4. Relay 图片状态机

| 当前状态 | 触发事件 | 下一个状态 | 说明 |
| - | - | - | - |
| `RESERVED` | Phone 开始上传 | `UPLOADING` | 上传窗口已被使用 |
| `UPLOADING` | 上传成功并通过文件校验 | `UPLOADED` | 图片资源可读 |
| `UPLOADING` | 上传失败 | `FAILED` | 失败终态 |
| `RESERVED` | 上传窗口过期 | `EXPIRED` | 未上传超时 |
| `UPLOADING` | 上传超时或连接中断未恢复 | `FAILED` | 失败终态 |
| `UPLOADED` / `FAILED` / `EXPIRED` | 清理任务删除文件与记录 | `DELETED` | 清理终态 |

## 5. Phone 状态机

### 5.1 初始化状态机 `setupState`

| 当前状态 | 触发事件 | 下一个状态 |
| - | - | - |
| `UNINITIALIZED` | 蓝牙权限、目标眼镜、Relay 配置全部就绪 | `INITIALIZED` |
| `INITIALIZED` | 任一初始化前置条件失效 | `UNINITIALIZED` |

### 5.2 运行状态机 `runtimeState`

| 当前状态 | 触发事件 | 下一个状态 | 说明 |
| - | - | - | - |
| `DISCONNECTED` | 开始连接蓝牙 | `CONNECTING` | 发起 RFCOMM 连接 |
| `CONNECTING` | 蓝牙连接成功且眼镜握手完成 | `READY` | 可以接单 |
| `CONNECTING` | 蓝牙连接失败 | `DISCONNECTED` | 回到断开态 |
| `READY` | 接收独占命令并开始执行 | `BUSY` | 典型是 `capture_photo` |
| `BUSY` | 命令成功完成 | `READY` | 回到就绪态 |
| `BUSY` | 命令失败但链路仍健康 | `READY` | 失败收口后可继续接单 |
| `READY` / `BUSY` | 蓝牙断开 | `DISCONNECTED` | 本地链路断开 |
| `ANY` | 本地不可恢复异常 | `ERROR` | 例如相机资源异常 |
| `ERROR` | 错误恢复成功 | `DISCONNECTED` 或 `READY` | 取决于链路是否仍在线 |

## 6. LocalLink 状态机

### 6.1 握手与保活

| 当前阶段 | 触发事件 | 下一个阶段 | 说明 |
| - | - | - | - |
| Socket 已建立 | Phone 发送 `hello` | 等待 `hello_ack` | 进入协议握手 |
| 等待 `hello_ack` | 收到 `hello_ack(accepted=true)` | `READY` | 会话建立成功 |
| 等待 `hello_ack` | 超时或 `accepted=false` | 会话关闭 | 握手失败 |
| `READY` | 空闲达到 `PING_INTERVAL_MS` | `ping/pong` 周期 | 保活 |
| `READY` | 连续 `pong` 超时或空闲超时 | `ERROR` / 会话关闭 | 保活失败 |

### 6.2 本地命令状态机

| 当前阶段 | 触发事件 | 下一个阶段 | 适用动作 |
| - | - | - | - |
| 已收到 `command` | Glasses 接单 | `command_ack` | 全部 |
| 已接单 | Glasses 进入执行逻辑 | `command_status(EXECUTING)` | 全部 |
| 执行中 | Glasses 正在显示文本 | `command_status(DISPLAYING)` | `display_text` |
| 执行中 | Glasses 正在拍照 | `command_status(CAPTURING)` | `capture_photo` |
| 拍照执行中 | 图片开始发送 | `chunk_start` | `capture_photo` |
| 图片发送中 | 连续发送图片块 | `chunk_data*` | `capture_photo` |
| 图片发送中 | 图片发送完成 | `chunk_end` | `capture_photo` |
| 本地执行完成 | 发送成功结果 | `command_result` | 全部 |
| 任意阶段失败 | 发送失败结果 | `command_error` | 全部 |

## 7. 跨层状态对照表

### 7.1 `display_text`

| 阶段 | MCP | Relay 命令状态 | Phone `runtimeState` | LocalLink / Glasses |
| - | - | - | - | - |
| 提交命令 | 发起 `POST /commands` | `CREATED` | `READY` | 空闲 |
| Relay 下发 | 等待轮询 | `DISPATCHED_TO_PHONE` | `READY` | 空闲 |
| Phone 接单 | 等待轮询 | `ACKNOWLEDGED_BY_PHONE` | `READY` | 已收到本地命令 |
| 转发与执行 | 轮询中 | `RUNNING` | `READY` | `EXECUTING` / `DISPLAYING` |
| 成功终态 | 轮询到 `COMPLETED` | `COMPLETED` | `READY` | `command_result(display_text)` |
| 失败终态 | 轮询到 `FAILED` / `TIMEOUT` | `FAILED` / `TIMEOUT` | `READY` 或 `ERROR` | `command_error(display_text)` |

### 7.2 `capture_photo`

| 阶段 | MCP | Relay 命令状态 | Relay 图片状态 | Phone `runtimeState` | LocalLink / Glasses |
| - | - | - | - | - | - |
| 提交命令 | 发起 `POST /commands` | `CREATED` | `RESERVED` | `READY` | 空闲 |
| Relay 下发 | 等待轮询 | `DISPATCHED_TO_PHONE` | `RESERVED` | `READY` | 空闲 |
| Phone 接单 | 等待轮询 | `ACKNOWLEDGED_BY_PHONE` | `RESERVED` | `BUSY` | 已收到本地命令 |
| 拍照执行中 | 轮询中 | `RUNNING` | `RESERVED` | `BUSY` | `CAPTURING` |
| 图片已拍好 | 轮询中 | `WAITING_IMAGE_UPLOAD` | `RESERVED` | `BUSY` | `chunk_end` 后本地图片完整 |
| 上传进行中 | 轮询中 | `IMAGE_UPLOADING` | `UPLOADING` | `BUSY` | 本地已完成 |
| 图片已上传 | 轮询中 | `RUNNING` | `UPLOADED` | `BUSY` | 本地已完成 |
| 成功终态 | 轮询到 `COMPLETED`，然后下载图片 | `COMPLETED` | `UPLOADED` | `READY` | `command_result(capture_photo)` |
| 失败终态 | 轮询到 `FAILED` / `TIMEOUT` | `FAILED` / `TIMEOUT` | 保持当前或失败 | `READY` 或 `ERROR` | `command_error(capture_photo)` |

## 8. 最终收口 owner

| 层 | 成功收口 owner | 失败收口 owner |
| - | - | - |
| MCP | tool 层自身 | tool 层自身 |
| Relay | `command-service` | `command-service` |
| Phone | `RelayCommandBridge` | `RelayCommandBridge` |
| Glasses | `CommandDispatcher` | `CommandDispatcher` |

规则：

- 其他底层模块只做局部 cleanup
- 不允许多个模块分别对同一个命令做最终收口
