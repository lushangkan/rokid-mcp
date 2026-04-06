# Phone-Glasses Protocol Sequences

## 1. 目标

本文档定义 MVP 阶段 `Phone <-> Glasses` 本地链路的详细时序、保活时序与错误流转。

本文档重点回答：

- `hello / hello_ack / ping / pong` 如何运作
- `display_text` 与 `capture_photo` 的成功链路如何推进
- 不做重试时，error 如何流转与收口

## 2. 依赖文档

- `docs/phone-glasses-protocol-constants.md`
- `docs/phone-glasses-protocol-schemas.md`
- `docs/relay-protocol-sequences.md`

## 3. 通用前提与约束

在以下时序成立前，默认满足这些前提：

- Phone 与 Glasses 已完成蓝牙 pair
- Glasses 只接受已 pair 的远端设备
- Phone 作为 RFCOMM client 主动连接
- Glasses 作为 RFCOMM server 接受连接
- Phone 侧同一时刻只存在一个 active command
- `capture_photo` 是独占命令
- MVP 不做命令重试、图片重传、断点续传

## 4. 首次连接与握手时序

### 4.1 主成功链路

```text
Phone transport: connect RFCOMM socket
Glasses transport: accept paired client
Phone session -> Glasses: hello
Glasses session: validate hello
Glasses session -> Phone: hello_ack(accepted = true)
Phone session: state -> READY
Phone runtime: runtimeState -> READY
```

### 4.2 握手失败分支

| 场景 | 触发点 | 处理 |
| - | - | - |
| 远端未 pair | Glasses accept 阶段 | 直接拒绝连接，不进入协议层 |
| `hello_ack` 超时 | Phone 等待阶段 | session 发 `SessionFailed(BLUETOOTH_HELLO_TIMEOUT)`，关闭 socket |
| 版本不兼容 | Glasses 校验 `hello` | 回 `hello_ack(accepted = false, error = PROTOCOL_UNSUPPORTED_VERSION)` 后断链 |
| `hello` 字段非法 | Glasses decode / validate 阶段 | 视为协议错误，断链 |

## 5. 空闲保活时序

### 5.1 主成功链路

```text
Phone session: idle >= PING_INTERVAL_MS
Phone session -> Glasses: ping(seq, nonce)
Glasses session -> Phone: pong(seq, nonce)
Phone session: refresh lastSeenAt
```

### 5.2 重要约束

- 任何正常业务 frame 收发都刷新 `lastSeenAt`
- 在命令执行与 chunk 传输期间，不额外插入 `ping`
- `ping / pong` 属于协议会话层，不属于 transport 层

### 5.3 保活失败分支

| 场景 | 处理 |
| - | - |
| 单次 `pong` 超时 | 记一次 miss，继续观察 |
| 连续 miss 达到 `PING_MAX_MISSES` | session 发 `SessionFailed(BLUETOOTH_PONG_TIMEOUT)` |
| `IDLE_TIMEOUT_MS` 内无任何远端 frame | session 发 `SessionFailed(BLUETOOTH_PONG_TIMEOUT)` |

## 6. `display_text` 主成功链路

```text
Relay -> Phone: command(display_text)
Phone bridge: begin active command
Phone bridge -> Relay: command_status(forwarding_to_glasses)
Phone session -> Glasses: command(display_text)
Phone bridge -> Relay: command_status(waiting_glasses_ack)
Glasses dispatcher -> Phone: command_ack
Phone bridge -> Relay: command_ack
Glasses dispatcher -> Phone: command_status(executing)
Phone bridge -> Relay: command_status(executing)
Glasses dispatcher -> Phone: command_status(displaying)
Phone bridge -> Relay: command_status(displaying)
Glasses dispatcher -> Phone: command_result(display_text)
Phone bridge -> Relay: command_result(display_text)
Phone bridge: complete active command
```

## 7. `capture_photo` 主成功链路

```text
Relay -> Phone: command(capture_photo)
Phone bridge: begin active command
Phone bridge -> Relay: command_status(forwarding_to_glasses)
Phone session -> Glasses: command(capture_photo)
Phone bridge -> Relay: command_status(waiting_glasses_ack)
Glasses dispatcher -> Phone: command_ack(runtimeState = BUSY)
Phone bridge -> Relay: command_ack(runtimeState = BUSY)
Glasses dispatcher -> Phone: command_status(capturing)
Phone bridge -> Relay: command_status(capturing)
Glasses executor: capture photo
Glasses image sender -> Phone: chunk_start
Glasses image sender -> Phone: chunk_data * N
Glasses image sender -> Phone: chunk_end
Phone assembler: verify chunkChecksum + sha256
Glasses dispatcher -> Phone: command_result(capture_photo)
Phone bridge: cache local capture result
Phone bridge -> Relay: command_status(image_captured)
Phone bridge -> Relay: command_status(uploading_image)
Phone uploader -> Relay: HTTP PUT image
Relay -> Phone: upload success
Phone bridge -> Relay: command_status(image_uploaded)
Phone bridge -> Relay: command_result(capture_photo)
Phone bridge: complete active command
```

### 7.1 为什么先传 chunk，再发 `command_result`

- `command_result(capture_photo)` 的语义更稳定：表示图片已经完整交付给 Phone
- 避免“先成功、后文件失败”的语义冲突
- Phone 可以在收到最终结果前完成完整性校验
- 有利于 MVP 简化状态机，不需要增加“命令成功但附件未到”的中间态

## 8. error 流转原则

### 8.1 总原则

MVP 不做重试，任意 command 相关错误都立即终止当前命令。

错误流转固定为：

```text
发现错误的模块
  -> 做本地 cleanup
  -> emit typed error event / throw typed exception
  -> 上层协调器接管
  -> 单点收口当前 command failure
  -> 更新 runtime state
```

### 8.2 最终错误收口 owner

| 端 | 最终收口者 |
| - | - |
| Phone | `RelayCommandBridge` |
| Glasses | `CommandDispatcher` |

其他模块不得直接做最终命令失败收口。

## 9. Phone 端错误流转

## 9.1 蓝牙链路断开

```text
RfcommClientTransport: emit Disconnected(BLUETOOTH_DISCONNECTED)
PhoneLocalLinkSession: close session, emit SessionFailed(BLUETOOTH_DISCONNECTED)
RelayCommandBridge: if active command exists -> failActiveCommand()
RelayCommandBridge -> Relay: command_error(BLUETOOTH_DISCONNECTED)
Phone runtime: runtimeState -> ERROR / DISCONNECTED
Phone app: schedule reconnect
```

各模块动作：

- `RfcommClientTransport`: 关闭 socket，停止读写循环
- `PhoneLocalLinkSession`: 停止 keepalive job，关闭 session
- `RelayCommandBridge`: 清 active command，更新 runtime，向 Relay 收口失败

## 9.2 本地文件校验失败

```text
PhoneLocalLinkSession: emit ChunkEndReceived
RelayCommandBridge: call IncomingImageAssembler.finish()
IncomingImageAssembler: sha256 mismatch -> delete temp file -> throw IMAGE_CHECKSUM_MISMATCH
RelayCommandBridge: failActiveCommand(IMAGE_CHECKSUM_MISMATCH)
RelayCommandBridge -> Relay: command_error(IMAGE_CHECKSUM_MISMATCH)
Phone runtime: runtimeState -> READY
```

各模块动作：

- `IncomingImageAssembler`: 删除临时文件，清 transfer context
- `RelayCommandBridge`: 失败收口，清 active command
- session 保持健康，不必强制断链

## 9.3 上传 Relay 失败

```text
RelayImageUploader: upload failed -> delete temp file -> throw UPLOAD_FAILED
RelayCommandBridge: failActiveCommand(UPLOAD_FAILED)
RelayCommandBridge -> Relay: command_error(UPLOAD_FAILED)
Phone runtime: runtimeState -> READY
```

各模块动作：

- `RelayImageUploader`: 无条件删除临时文件
- `RelayCommandBridge`: 用上传失败原因码收口当前命令
- 本地 session 保持健康

## 10. Glasses 端错误流转

## 10.1 `display_text` 执行失败

```text
DisplayTextExecutor: throw DISPLAY_FAILED
CommandDispatcher: failCurrentCommand(DISPLAY_FAILED)
CommandDispatcher -> Phone: command_error(DISPLAY_FAILED)
Glasses runtime: runtimeState -> READY
```

各模块动作：

- `DisplayTextExecutor`: 仅抛业务异常
- `CommandDispatcher`: 统一发 `command_error` 并回收当前命令上下文

## 10.2 `capture_photo` 拍照失败

```text
CapturePhotoExecutor: throw CAMERA_CAPTURE_FAILED
CommandDispatcher: failCurrentCommand(CAMERA_CAPTURE_FAILED)
CommandDispatcher -> Phone: command_error(CAMERA_CAPTURE_FAILED)
ExclusiveExecutionGuard: release lock
Glasses runtime: runtimeState -> READY
```

## 10.3 图片发送失败

```text
OutgoingImageSender: emit TransferFailed(BLUETOOTH_SEND_FAILED or IMAGE_STORAGE_WRITE_FAILED)
CommandDispatcher: failCurrentCommand(...)
CommandDispatcher -> Phone: command_error(...)
ExclusiveExecutionGuard: release lock
Glasses runtime: runtimeState -> READY or ERROR
```

说明：

- `OutgoingImageSender` 只负责文件传输，不直接发最终 `command_error`
- 最终命令失败仍由 `CommandDispatcher` 收口

## 10.4 协议会话失败

```text
GlassesLocalLinkSession: emit SessionFailed(PROTOCOL_* or BLUETOOTH_PONG_TIMEOUT)
CommandDispatcher: if active command exists -> local cleanup only
Glasses runtime: runtimeState -> ERROR
Transport: close socket
```

说明：

- 如果链路已经断开，通常无法再把 `command_error` 发回 Phone
- 此时只要求本地 cleanup 正确，等待 Phone 侧通过断链感知命令失败

## 11. 事件监听关系

## 11.1 Phone 侧

```text
RfcommClientTransport.events
  -> PhoneLocalLinkSession 监听

PhoneLocalLinkSession.events
  -> RelayCommandBridge 监听
  -> PhoneRuntimeStore 监听

RelaySessionClient.events
  -> RelayCommandBridge 监听
  -> PhoneRuntimeStore 监听
```

## 11.2 Glasses 侧

```text
RfcommServerTransport.events
  -> GlassesLocalLinkSession 监听

GlassesLocalLinkSession.events
  -> CommandDispatcher 监听
  -> GlassesRuntimeStore 监听

OutgoingImageSender.events
  -> CommandDispatcher 监听
```

## 12. 关键不变量

1. 最终命令失败只能单点提交
2. 不允许多个模块重复收口同一个 command
3. `capture_photo` 的 `command_result` 必须在 `chunk_end` 和文件校验成功之后
4. 上传 Relay 失败后，临时文件必须立即删除
5. `BUSY` 只表示链路健康且在执行独占任务

## 13. 下一步

建议继续维护：

1. `docs/phone-glasses-module-design.md`
2. Android 两端的 class/API 设计落地文档
