# Relay / Phone / Glasses 第一阶段 Heartbeat 设计

## 1. 目标

本文档冻结 `Relay Server`、`Phone Android` 与 `Glasses Android` 在第一阶段的 heartbeat / session / status 设计。

第一阶段目标不是打通完整命令链路，而是打通最小可信状态链路，使以下语义成立：

- `Relay` 能维护当前唯一设备的会话与状态快照
- `Phone <-> Relay` 已建立会话
- `Phone <-> Glasses` 已完成 `RFCOMM connect + hello / hello_ack`
- Phone 能对 Relay 持续上报 heartbeat 与状态快照
- 上层可通过 Relay / MCP 查询到可信设备状态

若本文档与旧版 MVP 总体设计或完整命令链路文档冲突，第一阶段实现一律以本文档为准。

本文档只覆盖：

- `apps/relay-server/src/modules/device/*`
- `apps/relay-server/src/routes/ws-device.ts`
- `apps/relay-server/src/routes/http-devices.ts`
- `packages/protocol/src/common/*`
- `packages/protocol/src/relay/*`
- `PhoneGatewayService`
- `PhoneAppController`
- `RfcommClientTransport`
- `PhoneLocalLinkSession`
- `RelaySessionClient`
- `PhoneRuntimeStore`
- `GlassesGatewayService`
- `GlassesAppController`
- `RfcommServerTransport`
- `GlassesLocalLinkSession`
- `GlassesRuntimeStore`

本文档不覆盖：

- `apps/relay-server/src/modules/command/*`
- `apps/relay-server/src/modules/image/*`
- `RelayCommandBridge`
- `ActiveCommandRegistry`
- `IncomingImageAssembler`
- `RelayImageUploader`
- `CommandDispatcher`
- `DisplayTextExecutor`
- `CapturePhotoExecutor`
- `OutgoingImageSender`

这些模块仍然保留为后续阶段的长期边界，但不参与第一阶段的 readiness、disconnect、heartbeat 设计。

## 2. 设计原则

第一阶段遵循以下原则：

1. 保留长期模块边界，不引入临时架构。
2. 只实现 session / status / heartbeat 最小闭环，不把 command / media 混入当前阶段。
3. `Transport` 只处理连接与字节流，不理解协议语义。
4. `Session` 只处理 `hello / hello_ack / ping / pong / lastSeenAt`，不负责业务命令编排。
5. `RuntimeStore` 只做对外状态聚合，不做业务判断。
6. `Controller` 做顶层编排，是第一阶段唯一的 store 写入者。
7. 正常业务 frame 与保活 frame 都能刷新活跃时间，`ping` 只在链路空闲时发送。
8. 第一阶段不做自动重连。

## 3. 第一阶段成功标准

第一阶段完成后，应满足以下可观察结果：

1. Phone 连接 Relay 成功后，Relay 侧能看到设备上线。
2. Phone 正在连接 Glasses 但尚未完成本地握手时，对外状态为 `CONNECTING`。
3. `hello / hello_ack` 成功后，对外状态为 `READY`。
4. `ping / pong` 保活正常时，状态保持 `READY`。
5. `ping / pong` 保活失败后，对外状态回到 `DISCONNECTED`。
6. `hello_ack` 超时或 `hello_ack.accepted = false` 时，对外状态回到 `DISCONNECTED`。
7. Phone 能按 Relay 下发参数定时发送 `heartbeat`。
8. Phone 能在状态变化时发送 `phone_state_update`。
9. Relay 能通过 `GET /api/v1/devices/:deviceId/status` 返回可信聚合状态。

## 4. 模块边界

## 4.1 Phone 端长期模块边界

第一阶段继续沿用以下长期模块边界：

```text
PhoneGatewayService
  -> PhoneAppController
      -> RelaySessionClient
      -> RfcommClientTransport
      -> PhoneLocalLinkSession
      -> PhoneRuntimeStore
```

说明：

- `RelayCommandBridge` 等命令模块不进入第一阶段主链路。
- 第一阶段只实现与 session / heartbeat / status 直接相关的 5 个核心模块。

## 4.2 Glasses 端长期模块边界

第一阶段继续沿用以下长期模块边界：

```text
GlassesGatewayService
  -> GlassesAppController
      -> RfcommServerTransport
      -> GlassesLocalLinkSession
      -> GlassesRuntimeStore
```

说明：

- `CommandDispatcher` 与 executor 系列模块不进入第一阶段主链路。
- 第一阶段只实现 transport / session / runtime 三层主干。

## 5. 状态 owner

## 5.1 Phone 端状态 owner

| 状态 | owner | 说明 |
| - | - | - |
| Relay uplink 状态 | `RelaySessionClient` | 只维护 `OFFLINE / CONNECTING / ONLINE / ERROR` |
| RFCOMM 连接状态 | `RfcommClientTransport` | 只维护 socket 级状态 |
| LocalLink 协议会话状态 | `PhoneLocalLinkSession` | 只维护 `IDLE / HANDSHAKING / READY / ERROR / CLOSED` |
| 对外运行态快照 | `PhoneRuntimeStore` | 只聚合，不推导底层协议细节 |

## 5.2 Glasses 端状态 owner

| 状态 | owner | 说明 |
| - | - | - |
| RFCOMM server / client 接入状态 | `RfcommServerTransport` | 只维护 transport 级状态 |
| LocalLink 协议会话状态 | `GlassesLocalLinkSession` | 只维护握手、保活、关闭 |
| Glasses 对外运行态快照 | `GlassesRuntimeStore` | 聚合 transport 与 session 事实 |

## 5.3 Store 单写者规则

冻结以下规则：

1. `PhoneRuntimeStore` 只允许 `PhoneAppController` 写入。
2. `GlassesRuntimeStore` 只允许 `GlassesAppController` 写入。
3. 其他模块只能产出 `state` 或 `events`，不能直接修改 store。
4. `heartbeat` 与 `phone_state_update` 的 payload 来源必须是 `PhoneRuntimeStore.snapshot`。

这样可以避免多个模块并发写状态导致的漂移、覆盖和竞态。

## 6. Phone 端模块设计

## 6.1 `PhoneGatewayService`

职责：

- Android 生命周期宿主
- 启动与停止整个 Phone gateway
- 不承载协议与状态机逻辑

补充说明：

- 第一阶段主页面可以作为本地调试控制台，负责展示配置、启动/停止入口、状态指示与日志输出。
- 主页面与 `PhoneGatewayService` 都只能调用 `PhoneAppController` 的显式控制面，不得直接触碰 transport、session 或 runtime store。

## 6.2 `PhoneAppController`

职责：

- 顶层编排器
- 装配 `RelaySessionClient`、`RfcommClientTransport`、`PhoneLocalLinkSession`、`PhoneRuntimeStore`
- 统一监听底层 `state / events`
- 统一写入 `PhoneRuntimeStore`
- 决定何时向 Relay 发送 `phone_state_update`

第一阶段约束：

- 它是 `PhoneRuntimeStore` 的唯一写入者。
- 它不承担 command / image 逻辑。
- 它负责解释 session 错误如何映射成对外状态。

第一阶段推荐编排顺序：

```text
PhoneAppController.start
  -> 读取当前本地配置
  -> 校验启动所需配置是否齐备
  -> runState = STARTING
  -> 记录 start 日志
  -> RelaySessionClient.connect()
  -> 监听 Relay uplink 进入 CONNECTING / ONLINE
  -> 启动本地 transport / session 主干
  -> 监听 transport / session state 与 event
  -> 统一更新 PhoneRuntimeStore
  -> 如状态发生对外可见变化，则调用 RelaySessionClient.sendPhoneStateUpdate(snapshot)

PhoneAppController.stop
  -> runState = STOPPING
  -> 记录 stop 日志
  -> 关闭 local session / transport
  -> RelaySessionClient.disconnect(reason)
  -> runState = STOPPED
```

第一阶段对 UI 的最小暴露：

1. 只读 `runState`
2. 只读 `PhoneRuntimeStore.snapshot`
3. 只读日志流 `logs`
4. 显式控制面 `start()` / `stop(reason)`

冻结规则：

1. UI 不得直接写 `PhoneRuntimeStore`。
2. UI 不得直接调用 `RelaySessionClient`、`RfcommClientTransport`、`PhoneLocalLinkSession`。
3. 若启动前缺少必要配置，`PhoneAppController` 必须拒绝启动、记录日志，并更新可观察错误状态。

## 6.3 `RfcommClientTransport`

职责：

- 建立 Phone -> Glasses RFCOMM 连接
- 管理 socket 读写循环
- 暴露原始 frame bytes
- 维护 socket 级状态

边界：

- 不理解 `hello`、`ping/pong`、`command`
- 不直接决定对外 `runtimeState`

建议状态：

```kotlin
enum class BluetoothTransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
```

## 6.4 `PhoneLocalLinkSession`

职责：

- 发 `hello`
- 等 `hello_ack`
- 空闲时发 `ping`
- 校验 `pong`
- 维护 `lastSeenAt`
- 管理 `hello_ack timeout`、`pong timeout`、`idle timeout`
- 产出协议级事件

边界：

- 不理解 Relay
- 不直接更新 `PhoneRuntimeStore`
- 不决定 `phone_state_update` 的发送时机
- 不参与 command / image 第一阶段逻辑

建议状态：

```kotlin
enum class PhoneSessionState {
    IDLE,
    HANDSHAKING,
    READY,
    ERROR,
    CLOSED,
}
```

第一阶段建议事件：

```kotlin
sealed interface PhoneSessionEvent {
    data class HelloAccepted(val payload: HelloAckPayload) : PhoneSessionEvent
    data class HelloRejected(val code: String, val message: String) : PhoneSessionEvent
    data class PongReceived(val seq: Long) : PhoneSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : PhoneSessionEvent
    data class SessionClosed(val reason: String) : PhoneSessionEvent
}
```

补充说明：

- `HelloRejected` 是第一阶段新增冻结事件，用于区分 `accepted=false` 与超时失败。
- 业务命令与 chunk 相关事件仍保留在长期设计里，但不进入第一阶段实现范围。

## 6.5 `RelaySessionClient`

职责：

- 管理 Phone <-> Relay WebSocket 会话
- 发送 `hello`
- 接收 Relay `hello_ack`
- 按 `heartbeatIntervalMs` 发送 heartbeat
- 提供显式状态控制面 API

边界：

- 它是 Relay uplink state owner。
- heartbeat 定时由它持有。
- heartbeat payload 与 `phone_state_update` payload 必须来自 `PhoneRuntimeStore.snapshot`。
- 它不直接读取底层蓝牙或 LocalLink 状态。

第一阶段建议 Public API：

```kotlin
enum class RelayLinkState {
    OFFLINE,
    CONNECTING,
    ONLINE,
    ERROR,
}

sealed interface RelayClientEvent {
    data class Connected(val sessionId: String?) : RelayClientEvent
    data class Disconnected(val reason: String) : RelayClientEvent
    data class RelayError(val error: Throwable) : RelayClientEvent
}

class RelaySessionClient(
    private val clock: Clock,
) {
    val state: StateFlow<RelayLinkState>
    val events: SharedFlow<RelayClientEvent>

    suspend fun connect()
    suspend fun disconnect(reason: String)
    suspend fun sendHeartbeat(snapshot: PhoneRuntimeSnapshot)
    suspend fun sendPhoneStateUpdate(snapshot: PhoneRuntimeSnapshot)
}
```

说明：

- command / upload API 仍属于长期模块边界，但第一阶段设计里不作为主链路使用。
- 第一阶段要明确把 `heartbeat` 与 `phone_state_update` 拉到显式 API 层，避免状态上报链路隐含在其他模块内部。

## 6.6 `PhoneRuntimeStore`

职责：

- 聚合 Phone 对外运行态
- 输出给 Relay heartbeat 与 `phone_state_update`
- 输出给后续 UI / 调试页

建议快照：

```kotlin
data class PhoneRuntimeSnapshot(
    val setupState: String,
    val runtimeState: String,
    val uplinkState: String,
    val activeCommandRequestId: String?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)
```

第一阶段约束：

- 只允许 `PhoneAppController` 写入。
- `activeCommandRequestId` 第一阶段固定为 `null`。
- `pendingCommandCount` 在 heartbeat 中固定为 `0`。

第一阶段补充说明：

- UI 侧的启动中、运行中、停止中等控制态不直接塞入 `PhoneRuntimeStore`，而是由 `PhoneAppController.runState` 单独暴露。
- 运行日志不进入 `PhoneRuntimeStore`；日志流由 controller 维护并提供给主页面观察。

## 7. Glasses 端模块设计

## 7.1 `GlassesGatewayService`

职责：

- Android 生命周期宿主
- 启动与停止 Glasses 侧 gateway

## 7.2 `GlassesAppController`

职责：

- 顶层编排器
- 装配 `RfcommServerTransport`、`GlassesLocalLinkSession`、`GlassesRuntimeStore`
- 统一监听 transport / session 事件
- 统一写入 `GlassesRuntimeStore`

第一阶段约束：

- 它是 `GlassesRuntimeStore` 的唯一写入者。
- 不依赖 `CommandDispatcher` 才能完成握手、保活与关闭。

## 7.3 `RfcommServerTransport`

职责：

- `BluetoothServerSocket.accept()`
- 校验远端设备是否已 pair
- 维持单连接控制
- 暴露原始 bytes
- 输出 transport state 与 transport event

第一阶段建议状态：

```kotlin
enum class GlassesTransportState {
    LISTENING,
    CLIENT_CONNECTED,
    ERROR,
    CLOSED,
}
```

说明：

- 这是第一阶段建议补充的显式状态来源。
- `GlassesRuntimeStore` 不应只靠 event 猜测 transport 真相。

## 7.4 `GlassesLocalLinkSession`

职责：

- 收 `hello` 并回复 `hello_ack`
- 收 `ping` 并回复 `pong`
- 维护握手与会话状态
- 产出协议级事件

边界：

- 不理解 Relay
- 不参与第一阶段 command / image 逻辑
- 不直接写 `GlassesRuntimeStore`

建议状态：

```kotlin
enum class GlassesSessionState {
    LISTENING,
    HANDSHAKING,
    SESSION_READY,
    SESSION_ERROR,
    CLOSED,
}
```

第一阶段建议事件：

```kotlin
sealed interface GlassesSessionEvent {
    data class HelloReceived(val message: HelloMessage) : GlassesSessionEvent
    data class PingReceived(val message: PingMessage) : GlassesSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : GlassesSessionEvent
    data class SessionClosed(val reason: String) : GlassesSessionEvent
}
```

## 7.5 `GlassesRuntimeStore`

职责：

- 聚合 Glasses 对外运行态
- 用于本地 UI / 调试观察

建议快照：

```kotlin
data class GlassesRuntimeSnapshot(
    val runtimeState: String,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)
```

第一阶段约束：

- 只允许 `GlassesAppController` 写入。
- 第一阶段不引入 `activeCommandRequestId` 和 `BUSY` 投影。

## 8. 监听关系

## 8.1 Phone 侧监听关系

第一阶段监听关系冻结如下：

```text
MainActivity / Phone 调试页
  -> PhoneAppController.start/stop
  -> 读取 PhoneAppController.runState/logs
  -> 读取 PhoneRuntimeStore.snapshot

RelaySessionClient.state/events
  -> PhoneAppController 监听

RfcommClientTransport.state/events
  -> PhoneLocalLinkSession 监听
  -> PhoneAppController 监听

PhoneLocalLinkSession.state/events
  -> PhoneAppController 监听

PhoneRuntimeStore.snapshot
  -> PhoneAppController 读取
  -> RelaySessionClient 用作 heartbeat / phone_state_update payload 来源
```

约束：

1. `RfcommClientTransport` 不能直接写 `PhoneRuntimeStore`。
2. `PhoneLocalLinkSession` 不能直接写 `PhoneRuntimeStore`。
3. `PhoneLocalLinkSession` 不能直接决定 `phone_state_update` 是否发送。
4. `PhoneRuntimeStore` 只能由 `PhoneAppController` 写。
5. 主页面只能通过 controller 控制面触发 start/stop，不能绕过 controller 直接操作底层模块。

## 8.2 Glasses 侧监听关系

第一阶段监听关系冻结如下：

```text
RfcommServerTransport.state/events
  -> GlassesLocalLinkSession 监听
  -> GlassesAppController 监听

GlassesLocalLinkSession.state/events
  -> GlassesAppController 监听

GlassesRuntimeStore.snapshot
  -> GlassesAppController 读取
```

约束：

1. `GlassesLocalLinkSession` 不能直接写 `GlassesRuntimeStore`。
2. 第一阶段 `CommandDispatcher` 不进入 handshake / keepalive 主链路。

## 9. 状态映射规则

## 9.1 Phone 外部状态映射

第一阶段对 Relay / MCP 可见的 `runtimeState` 按如下规则映射：

| 条件 | 对外 `runtimeState` |
| - | - |
| Relay 已连通，但 RFCOMM 未连通 | `CONNECTING` 或 `DISCONNECTED`，以 controller 启动阶段是否已开始本地建链为准 |
| RFCOMM 连接中 | `CONNECTING` |
| RFCOMM 已连通，但 `hello_ack` 未完成 | `CONNECTING` |
| `hello / hello_ack` 成功 | `READY` |
| `hello_ack` 超时 | `DISCONNECTED` |
| `hello_ack.accepted = false` | `DISCONNECTED` |
| `ping/pong` 保活失败 | `DISCONNECTED` |
| 本地不可恢复异常 | `ERROR` |

补充说明：

1. 第一阶段允许暴露 `CONNECTING`。
2. `READY` 的门槛是：Relay uplink online + RFCOMM connected + `hello / hello_ack` 成功。
3. 第一阶段不要求首轮 `pong` 成功才进入 `READY`。
4. 第一阶段冻结规则：`ping/pong` 失败对外必须映射为 `DISCONNECTED`，即使内部 transport / session 使用 `ERROR` 或 `STALE` 表示失活。

## 9.2 Glasses 内部状态映射

Glasses 第一阶段只要求内部状态事实清楚：

| 条件 | 内部状态 |
| - | - |
| server 正在 accept | `LISTENING` |
| 已接入客户端，等待 `hello` | `HANDSHAKING` |
| `hello_ack(accepted=true)` 已发出 | `SESSION_READY` |
| 协议错误或会话异常 | `SESSION_ERROR` |
| 当前连接关闭 | `CLOSED` |

第一阶段不要求把 Glasses 状态直接上抛给 Relay；对外统一状态语义以 Phone 侧聚合结果为准。

## 10. 状态流转与事件顺序

## 10.1 主成功链路

```text
PhoneGatewayService.start
  -> PhoneAppController.start
  -> RelaySessionClient.connect
  -> Relay hello / hello_ack success
  -> PhoneRuntimeStore.uplinkState = ONLINE
  -> RfcommClientTransport.connect
  -> PhoneLocalLinkSession.openHandshake
  -> Phone -> Glasses: hello
  -> GlassesLocalLinkSession validate hello
  -> Glasses -> Phone: hello_ack(accepted=true)
  -> PhoneLocalLinkSession state = READY
  -> PhoneRuntimeStore.runtimeState = READY
  -> RelaySessionClient.sendPhoneStateUpdate(snapshot)
```

## 10.2 空闲保活链路

```text
PhoneLocalLinkSession idle >= PING_INTERVAL_MS
  -> send ping(seq, nonce)
  -> GlassesLocalLinkSession receive ping
  -> send pong(seq, nonce)
  -> PhoneLocalLinkSession validate pong
  -> refresh lastSeenAt
  -> runtimeState remains READY
```

说明：

- `pong` 成功不会单独触发 `phone_state_update`。
- heartbeat 与 `phone_state_update` 不是同一种控制面消息。

## 10.3 握手失败链路

### A. `hello_ack` 超时

```text
PhoneLocalLinkSession waiting hello_ack
  -> timeout
  -> emit SessionFailed(BLUETOOTH_HELLO_TIMEOUT)
  -> PhoneAppController update store
  -> runtimeState = DISCONNECTED
  -> lastErrorCode = BLUETOOTH_HELLO_TIMEOUT
  -> RelaySessionClient.sendPhoneStateUpdate(snapshot)
```

### B. `hello_ack.accepted = false`

```text
PhoneLocalLinkSession receive hello_ack(accepted=false, error=...)
  -> emit HelloRejected(code, message)
  -> PhoneAppController update store
  -> runtimeState = DISCONNECTED
  -> lastErrorCode = code
  -> lastErrorMessage = message
  -> RelaySessionClient.sendPhoneStateUpdate(snapshot)
```

## 10.4 保活失败链路

```text
PhoneLocalLinkSession waiting pong
  -> timeout or idle timeout
  -> emit SessionFailed(BLUETOOTH_PONG_TIMEOUT)
  -> PhoneAppController close local session / transport
  -> PhoneRuntimeStore.runtimeState = DISCONNECTED
  -> lastErrorCode = BLUETOOTH_PONG_TIMEOUT
  -> RelaySessionClient.sendPhoneStateUpdate(snapshot)
```

## 11. Store 更新点

## 11.1 `PhoneRuntimeStore` 更新点

`PhoneAppController` 统一处理以下更新：

1. App 启动
2. Relay uplink 进入 `CONNECTING`
3. Relay uplink 进入 `ONLINE`
4. RFCOMM transport 进入 `CONNECTING`
5. LocalLink session 进入 `HANDSHAKING`
6. `HelloAccepted`
7. `HelloRejected`
8. `SessionFailed(BLUETOOTH_HELLO_TIMEOUT)`
9. `SessionFailed(BLUETOOTH_PONG_TIMEOUT)`
10. transport `Disconnected`
11. Relay uplink `OFFLINE`
12. Relay uplink `ERROR`

冻结规则：

- 除 `PhoneAppController` 之外，任何模块不得直接写入 `PhoneRuntimeStore`。

## 11.2 `GlassesRuntimeStore` 更新点

`GlassesAppController` 统一处理以下更新：

1. server 进入 `LISTENING`
2. transport 接受客户端
3. session 进入 `HANDSHAKING`
4. session 进入 `SESSION_READY`
5. session 进入 `SESSION_ERROR`
6. session `CLOSED`

冻结规则：

- 除 `GlassesAppController` 之外，任何模块不得直接写入 `GlassesRuntimeStore`。

## 12. Heartbeat 与 `phone_state_update` 规则

## 12.1 heartbeat

冻结规则：

1. heartbeat 定时器由 `RelaySessionClient` 持有。
2. heartbeat payload 来源于 `PhoneRuntimeStore.snapshot`。
3. heartbeat 周期由 Relay `hello_ack.heartbeatIntervalMs` 决定。
4. 第一阶段 `pendingCommandCount` 固定为 `0`。
5. 第一阶段 `activeCommandRequestId` 固定为 `null`。
6. heartbeat 中的 `uplinkState` 不得在编码时被本地硬编码覆盖为 `ONLINE`；应直接来自当前 snapshot。第一阶段只有在 snapshot 已经是 `ONLINE` 或 `ERROR` 时才允许发送 heartbeat。

## 12.2 `phone_state_update`

冻结规则：

1. 只在外部可见状态变化时发送。
2. 只在 `setupState`、`runtimeState`、`uplinkState`、`lastErrorCode`、`lastErrorMessage` 变化时发送。
3. `pong` 成功不触发 `phone_state_update`。
4. payload 来源于 `PhoneRuntimeStore.snapshot`。
5. 是否发送由 `PhoneAppController` 决定，具体发送动作由 `RelaySessionClient` 执行。

## 13. Error 冒泡树

## 13.1 Phone 侧错误冒泡

```text
RfcommClientTransport
  -> emit transport event
  -> PhoneLocalLinkSession 吸收 transport 事件
      -> 转成 SessionFailed / SessionClosed
      -> PhoneAppController 接管
          -> 更新 PhoneRuntimeStore
          -> 如 Relay 在线则发送 phone_state_update
```

具体规则：

1. transport 只发 transport 级事件。
2. session 负责把 transport 问题语义化为协议级失败。
3. controller 负责决定如何映射成外部状态。
4. store 不自行推断错误含义。

## 13.2 Glasses 侧错误冒泡

```text
RfcommServerTransport
  -> emit transport event
  -> GlassesLocalLinkSession 接管
      -> 转成 SessionFailed / SessionClosed
      -> GlassesAppController 接管
          -> 更新 GlassesRuntimeStore
          -> 回到 listening 或关闭当前会话
```

## 13.3 第一阶段错误分层规则

冻结以下规则：

1. transport 错误不上翻成业务错误码。
2. session 错误不上翻成直接 store 写入。
3. controller 才能决定外部状态如何表达。
4. 第一阶段 readiness / disconnect 处理不允许依赖 `RelayCommandBridge` 或 `CommandDispatcher`。

## 14. 第一阶段范围冻结

第一阶段实现范围冻结为：

### Phone

- `PhoneGatewayService`
- `PhoneAppController`
- `RelaySessionClient`
- `RfcommClientTransport`
- `PhoneLocalLinkSession`
- `PhoneRuntimeStore`

### Glasses

- `GlassesGatewayService`
- `GlassesAppController`
- `RfcommServerTransport`
- `GlassesLocalLinkSession`
- `GlassesRuntimeStore`

### 明确不进入当前阶段

- `RelayCommandBridge`
- `ActiveCommandRegistry`
- `IncomingImageAssembler`
- `RelayImageUploader`
- `CommandDispatcher`
- `DisplayTextExecutor`
- `CapturePhotoExecutor`
- `OutgoingImageSender`

## 15. 与后续阶段的兼容性

第一阶段虽然不实现 command / media，但这不是临时架构。

本设计要求：

1. 后续命令层必须建立在当前 `transport -> session -> controller -> runtime store` 主干之上。
2. 后续 `RelayCommandBridge` 与 `CommandDispatcher` 只能接入既有 session 事件，不得反向接管 heartbeat 与 readiness。
3. 后续 `BUSY`、图片上传、chunk 传输只是在当前架构上扩展，不需要推翻第一阶段边界。

## 16. 后续衔接

当本文档确认后，下一步进入实现规划时，应继续设计：

1. Relay 侧如何消费 Phone heartbeat 与 `phone_state_update`
2. Relay 侧 `GetDeviceStatusResponse` 如何投影这些状态
3. MCP 侧 `get-device-status` 如何查询并解释结果

## 17. Relay 第一阶段设计目标

Relay 第一阶段只做 **session / status Relay**，不做完整命令与图片链路。

第一阶段只围绕以下主线：

1. Phone 通过 `WS /ws/device` 接入 Relay
2. Relay 校验 `hello`
3. Relay 返回 `hello_ack`
4. Relay 接收 `heartbeat`
5. Relay 接收 `phone_state_update`
6. Relay 维护当前唯一设备的会话事实与运行态快照
7. MCP 后续通过 `GET /api/v1/devices/:deviceId/status` 查询可信状态

第一阶段明确不进入实现范围：

- `command-service`
- `image-manager`
- `POST /api/v1/commands`
- `PUT /api/v1/images/:imageId`
- Relay -> Phone `command`

## 18. Relay 模块边界

## 18.1 应用层结构

第一阶段 Relay 建议结构：

```text
apps/relay-server/src/
  app.ts
  main.ts
  config/
    env.ts
  lib/
    clock.ts
    logger.ts
    errors.ts
  modules/
    device/
      device-session-manager.ts
      single-device-session-store.ts
      single-device-runtime-store.ts
  routes/
    ws-device.ts
    http-devices.ts
```

职责边界：

- `routes/*` 只做协议适配，不直接改内部状态
- `device-session-manager` 是第一阶段唯一状态推进 owner
- 两个 singleton store 只保存当前唯一设备上下文

## 18.2 共享协议层结构

第一阶段共享协议定义放在 `packages/protocol`：

```text
packages/protocol/src/
  index.ts
  common/
    index.ts
    scalar.ts
    states.ts
    errors.ts
  relay/
    index.ts
    ws.ts
    http.ts
```

冻结规则：

1. `packages/protocol` 只放常量、TypeBox schema、静态类型。
2. `packages/protocol` 不依赖 Elysia。
3. `packages/protocol` 不放 store record、helper、route 逻辑、状态推进逻辑。
4. Relay 和 MCP 都直接复用这里的 DTO / schema / 常量。

## 19. Relay 状态模型

## 19.1 singleton 简化

MVP 阶段 Relay 不做多设备管理，只维护当前唯一设备与当前唯一会话。

冻结规则：

1. Relay 同一时刻只允许一个当前活跃设备上下文。
2. 新 `hello` 到来时，直接替换旧 session。
3. 若新 `hello.deviceId` 与当前不同，则当前 singleton 上下文整体切换到新设备。
4. Relay 不保留旧设备历史记录。

## 19.2 `single-device-session-store`

职责：

- 保存当前唯一会话事实
- 不做状态推进规则判断

建议记录：

```ts
type CurrentSessionRecord = {
  deviceId: string
  sessionId: string
  socketRef: unknown | null
  sessionState: "OFFLINE" | "ONLINE" | "STALE" | "CLOSED"
  appVersion: string
  capabilities: Array<"display_text" | "capture_photo">
  connectedAt: number
  authenticatedAt: number
  lastSeenAt: number
  lastHeartbeatAt?: number
  closedAt?: number
}
```

## 19.3 `single-device-runtime-store`

职责：

- 保存当前唯一设备的运行态快照
- 不做连接可用性推导

建议记录：

```ts
type CurrentRuntimeSnapshot = {
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

## 19.4 `device-session-manager`

冻结规则：

1. 它是 Relay 第一阶段唯一状态推进 owner。
2. route 与 store 都不得直接推进 sessionState 或 runtime snapshot。
3. `lastSeenAt` 由 manager 在任意合法入站控制消息时统一刷新。
4. `lastSeenAt` 与 stale 判断统一使用 Relay 服务端时间源，不使用客户端消息中的 `timestamp` 作为 freshness 依据。

第一阶段建议 API：

```ts
registerHello(message, socketRef)
confirmHello()
markInboundSeen(type)
markHeartbeat(message)
applyPhoneStateUpdate(message)
matchesCurrentSession(deviceId, sessionId)
closeCurrentSession(reason, socketRef?)
getCurrentDeviceStatus(requestedDeviceId)
startCleanupJob()
stopCleanupJob()
```

说明：

- `markInboundSeen()` 是统一 freshness 入口，供 `hello`、`heartbeat`、`phone_state_update` 复用。
- `markInboundSeen()` 内部使用 Relay 本地 `clock.now()` 刷新 `lastSeenAt`。
- `matchesCurrentSession()` 供 `ws-device` 在已认证态校验 `deviceId / sessionId` 是否仍然命中当前活跃会话。
- 后续命令阶段可继续让 `command_ack / status / result / error` 复用该入口。

## 20. Relay `ws-device` 消息流转

## 20.1 `ws-device` 职责边界

`WS /ws/device` route 只做以下四件事：

1. 接收 WebSocket 连接
2. 使用共享 schema 校验入站消息
3. 按消息类型分发到 `device-session-manager`
4. 发送 `hello_ack`

它不负责：

- 直接改 store
- 直接改 `lastSeenAt`
- 直接拼 `GetDeviceStatusResponse`
- 直接做 session 替换规则

## 20.2 第一阶段允许的入站消息

第一阶段 `ws-device` 主链路只处理：

1. `hello`
2. `heartbeat`
3. `phone_state_update`

其他 WS 消息类型不进入当前阶段主链路。

## 20.3 连接生命周期

第一阶段连接生命周期分为：

1. `open`
2. `pre-hello`
3. `authenticated`
4. `closed`

冻结规则：

1. `hello` 前只允许收到 `hello`。
2. `hello` 成功后才进入已认证态。
3. 旧 socket 被新 `hello` 替换后，旧 socket 的迟到 `close` 事件必须被忽略。
4. `closeCurrentSession(reason, socketRef?)` 必须支持按 `socketRef` 校验当前活跃连接身份。

## 20.4 `hello` 流转

```text
Phone -> WS /ws/device: hello
  -> 使用共享 schema 校验
  -> ws-device 检查当前阶段是否允许 hello
  -> device-session-manager.registerHello(message, socketRef)
      -> 若已有旧 session，则关闭旧 socket并替换
      -> 写入当前唯一 session record
      -> 初始化或覆盖当前 runtime snapshot
      -> 保留 Phone 在 hello 中自报的 `uplinkState`，不得在此处强制改为 `ONLINE`
      -> 调用 markInboundSeen(type="hello")
  -> device-session-manager.confirmHello()
  -> ws-device 发送 hello_ack
```

## 20.5 `heartbeat` 流转

```text
Phone -> WS /ws/device: heartbeat
  -> 使用共享 schema 校验
  -> 校验当前已认证
  -> 校验 deviceId / sessionId 与当前 session 一致
  -> device-session-manager.markHeartbeat(message)
      -> 内部调用 markInboundSeen(type="heartbeat")
      -> 刷新 lastSeenAt
      -> 刷新 lastHeartbeatAt
      -> 若 sessionState = STALE，则恢复为 ONLINE
  -> 不回消息
```

## 20.6 `phone_state_update` 流转

```text
Phone -> WS /ws/device: phone_state_update
  -> 使用共享 schema 校验
  -> 校验当前已认证
  -> 校验 deviceId / sessionId 与当前 session 一致
  -> device-session-manager.applyPhoneStateUpdate(message)
      -> 内部调用 markInboundSeen(type="phone_state_update")
      -> 刷新 lastSeenAt
      -> 更新 runtime snapshot
      -> 更新 lastUpdatedAt
  -> 不回消息
```

## 20.7 非法消息处理

第一阶段建议保守处理：

1. 非法 JSON 或 schema 校验失败，直接关闭当前 WS。
2. `hello` 前收到非 `hello` 消息，直接关闭当前 WS。
3. `deviceId` / `sessionId` 与当前 session 不匹配，直接关闭当前 WS。
4. manager 认证失败时，直接关闭当前 WS，不发临时自定义错误消息。

原因：第一阶段协议中没有定义 Relay -> Phone 的显式 hello reject frame，因此保持“握手失败直接断链”的最小规则更稳。

## 21. `GetDeviceStatusResponse` 聚合规则

## 21.1 聚合输入

`GetDeviceStatusResponse` 的输入只来自：

1. `currentSessionRecord`
2. `currentRuntimeSnapshot`

它返回的是 **Relay 计算后的有效设备状态**，不是原始存储快照直出。

## 21.2 请求路径与 singleton 匹配

虽然 Relay 第一阶段内部只维护 singleton 上下文，但 HTTP 仍保留：

`GET /api/v1/devices/:deviceId/status`

匹配规则：

- 若 `requestedDeviceId` 命中当前 singleton session 或 runtime snapshot，则返回当前设备聚合状态
- 若未命中，则返回合成离线响应，不返回 `404`

## 21.3 默认离线响应

当 `requestedDeviceId` 不命中当前 singleton 上下文时，返回：

```json
{
  "ok": true,
  "device": {
    "deviceId": "<requestedDeviceId>",
    "connected": false,
    "sessionState": "OFFLINE",
    "setupState": "UNINITIALIZED",
    "runtimeState": "DISCONNECTED",
    "uplinkState": "OFFLINE",
    "capabilities": [],
    "activeCommandRequestId": null,
    "lastErrorCode": null,
    "lastErrorMessage": null,
    "lastSeenAt": null,
    "sessionId": null
  },
  "timestamp": 1710000000000
}
```

## 21.4 有效状态覆盖规则

查询时先读取 runtime snapshot，再应用 Relay session 可用性覆盖：

| `sessionState` | `connected` | 最终 `runtimeState` | 最终 `uplinkState` |
| - | - | - | - |
| `ONLINE` | `true` | 使用 runtime snapshot | 使用 runtime snapshot |
| `STALE` | `false` | 强制为 `DISCONNECTED` | 强制为 `OFFLINE` |
| `CLOSED` | `false` | 强制为 `DISCONNECTED` | 强制为 `OFFLINE` |
| `OFFLINE` 或无记录 | `false` | 强制为 `DISCONNECTED` | 强制为 `OFFLINE` |

说明：

- runtime snapshot 代表 Phone 最后一次自报状态
- sessionState 代表 Relay 对当前连接可用性的判断
- 面向 MCP 的最终查询结果必须以 session 可用性为最高优先级

## 21.5 字段级聚合规则

| 字段 | 规则 |
| - | - |
| `deviceId` | 永远返回请求路径中的 `deviceId` |
| `connected` | 仅当 `sessionState = ONLINE` 时为 `true` |
| `sessionState` | 来自 Relay 当前会话事实；无记录时为 `OFFLINE` |
| `setupState` | 优先来自 runtime snapshot；无记录时为 `UNINITIALIZED` |
| `runtimeState` | 优先来自 runtime snapshot，再应用 session 覆盖规则 |
| `uplinkState` | 优先来自 runtime snapshot，再应用 session 覆盖规则 |
| `capabilities` | 来自当前 session record；无记录时为 `[]` |
| `activeCommandRequestId` | 第一阶段固定 `null` |
| `lastErrorCode` / `lastErrorMessage` | 来自 runtime snapshot；无记录时为 `null` |
| `lastSeenAt` | 来自 session record；无记录时为 `null` |
| `sessionId` | 来自当前 session record；无记录时为 `null` |

## 21.6 保留关闭态 session 记录

为了让查询能正确表达 `CLOSED`，第一阶段不在关闭时立刻删除整个 session 记录。

建议做法：

1. 关闭后保留当前 session record
2. 将 `socketRef` 清空
3. 将 `sessionState` 置为 `CLOSED`
4. 记录 `closedAt`
5. 新 `hello` 到来时整体覆盖为新的 singleton 上下文

## 22. `packages/protocol` 第一阶段组织

## 22.1 目录归属原则

共享协议目录按 **协议 ownership** 分，而不是按“谁会 import 它”分。

因此：

- `common/` 只放真正跨协议复用的原子 schema
- Relay HTTP DTO 虽然会被 MCP 复用，但仍然放在 `relay/http.ts`

## 22.2 `common/` 放置内容

建议放置：

- `DeviceIdSchema`
- `SessionIdSchema`
- `TimestampSchema`
- `ProtocolVersionSchema`
- `SetupStateSchema`
- `RuntimeStateSchema`
- `UplinkStateSchema`
- `DeviceSessionStateSchema`
- `CapabilitySchema`
- `CapabilitiesSchema`
- `NullableStringSchema`
- `ErrorResponseSchema`

## 22.3 `relay/ws.ts` 放置内容

建议放置：

- `RelayWsEnvelopeSchema`
- `RelayHelloPayloadSchema`
- `RelayHelloMessageSchema`
- `RelayHeartbeatPayloadSchema`
- `RelayHeartbeatMessageSchema`
- `RelayPhoneStateUpdatePayloadSchema`
- `RelayPhoneStateUpdateMessageSchema`
- `RelayDeviceInboundMessageSchema`
- `RelayHelloAckPayloadSchema`
- `RelayHelloAckMessageSchema`

## 22.4 `relay/http.ts` 放置内容

建议放置：

- `GetDeviceStatusParamsSchema`
- `GetDeviceStatusDeviceSchema`
- `GetDeviceStatusResponseSchema`

说明：

- `GetDeviceStatusResponse` 是 Relay HTTP API 的协议定义，因此归属 `relay/http.ts`
- MCP 复用它是正常的，但这不改变它的协议 ownership

## 22.5 命名与导出规则

冻结规则：

1. schema 名称统一以 `Schema` 结尾。
2. 静态类型统一从 schema 推导，不重复手写 interface。
3. `packages/protocol` 只导出 schema、type、constant。
4. 不导出 Relay 内部 store record。
5. 不导出 helper / builder / route config。

## 23. Relay 第一阶段范围冻结

第一阶段真正实现：

### `packages/protocol`

- `src/common/*`
- `src/relay/ws.ts`
- `src/relay/http.ts`
- 对应的 `index.ts` 导出

### `apps/relay-server`

- `config/env.ts`
- `lib/clock.ts`
- `lib/logger.ts`
- `lib/errors.ts`
- `modules/device/device-session-manager.ts`
- `modules/device/single-device-session-store.ts`
- `modules/device/single-device-runtime-store.ts`
- `routes/ws-device.ts`
- `routes/http-devices.ts`
- `app.ts`
- `main.ts`

第一阶段明确不进入实现：

- `modules/command/*`
- `modules/image/*`
- `routes/http-commands.ts`
- `routes/http-images.ts`
- Relay -> Phone `command`

## 24. 整体实现衔接

在当前设计下，第一阶段整体实现顺序建议为：

1. 先在 `packages/protocol` 中补齐共享 schema / types / constants
2. 再在 `apps/relay-server` 中落 `route -> manager -> singleton stores`
3. 然后让 Phone 端 `RelaySessionClient` 对接 `hello / hello_ack / heartbeat / phone_state_update`
4. 最后让 MCP 通过 `GET /api/v1/devices/:deviceId/status` 查询结果

## 25. MCP 第一阶段设计目标

MCP 第一阶段只做一个最小可用的 **Relay 状态消费层**，不做完整命令控制面。

第一阶段目标固定为：

1. 对 AI 暴露 `rokid.get_device_status`
2. 通过 HTTP 调 Relay 的 `GET /api/v1/devices/:deviceId/status`
3. 严格复用 `packages/protocol` 中共享的 Relay HTTP DTO schema
4. 向 AI 返回最小映射后的设备状态结果

第一阶段明确不进入实现范围：

- `rokid.display_text`
- `rokid.capture_photo`
- `command-poller`
- `image-downloader`
- `image-parser`
- `image-temp-file-store`

## 26. MCP 模块边界

第一阶段 MCP 建议结构：

```text
packages/mcp-server/src/
  index.ts
  server.ts
  config/
    env.ts
  lib/
    errors.ts
    logger.ts
  relay/
    relay-client.ts
    relay-response-validator.ts
  mapper/
    result-mapper.ts
  tools/
    get-device-status.ts
```

职责边界：

- `server.ts` 只做 MCP server 装配与 tool 注册
- `relay-client.ts` 只做 Relay HTTP 调用
- `relay-response-validator.ts` 只做共享 DTO runtime validation
- `result-mapper.ts` 只做 Relay DTO -> MCP tool 输出映射
- `get-device-status.ts` 是第一阶段唯一 tool handler，也是最终错误收口 owner

## 27. MCP 与 `packages/protocol` 的边界

冻结规则：

1. Relay HTTP DTO schema 归 `packages/protocol/src/relay/http.ts`。
2. MCP 直接复用 `GetDeviceStatusResponseSchema` 与相关公共状态 schema。
3. MCP tool 自己的输入输出类型不进入 `packages/protocol`，仍然保留在 `packages/mcp-server`。
4. `relay-client` 返回值必须严格符合共享协议 DTO，不得擅自拍平或改字段名。

## 28. MCP 第一阶段数据流

第一阶段只支持以下主链路：

```text
AI -> MCP: rokid.get_device_status()
MCP: read defaultDeviceId from env
MCP -> Relay: GET /api/v1/devices/:deviceId/status
Relay -> MCP: GetDeviceStatusResponse
MCP: validate response with shared protocol schema
MCP: map result
MCP -> AI: return tool result
```

## 29. MCP 模块职责

## 29.1 `config/env.ts`

职责：

- 读取并校验 MCP 环境变量
- 输出强类型配置对象

第一阶段最少包含：

```ts
type McpEnv = {
  relayBaseUrl: string
  defaultDeviceId: string
  requestTimeoutMs: number
  relayApiToken?: string
}
```

## 29.2 `relay/relay-client.ts`

职责：

- 封装所有 Relay HTTP 请求
- 统一处理 URL、headers、timeout、HTTP 错误、JSON 解析

第一阶段建议接口：

```ts
interface RelayClient {
  getDeviceStatus(deviceId: string): Promise<GetDeviceStatusResponse>
}
```

## 29.3 `relay/relay-response-validator.ts`

职责：

- 用共享 schema 对 Relay JSON 响应做 runtime validation
- 保证 `relay-client` 交给上层的数据一定符合共享协议 DTO

第一阶段最少校验：

- `GetDeviceStatusResponse`
- `ErrorResponse`

## 29.4 `mapper/result-mapper.ts`

职责：

- 把有效的 Relay DTO 映射成 MCP tool 输出

第一阶段建议接口：

```ts
interface ResultMapper {
  toGetDeviceStatusOutput(response: GetDeviceStatusResponse): RokidGetDeviceStatusOutput
}
```

说明：

- 第一阶段允许映射层非常薄
- `rokid.get_device_status` 输出可以与 Relay `GetDeviceStatusResponse` 基本保持一致

## 29.5 `tools/get-device-status.ts`

职责：

- 读取默认设备 ID
- 调 `relay-client.getDeviceStatus()`
- 调 `relay-response-validator`
- 调 `result-mapper`
- 统一收口最终 tool success / failure

## 29.6 `server.ts`

职责：

- 初始化 MCP server
- 只注册 `rokid.get_device_status`
- 装配 `env`、`relay-client`、`validator`、`mapper`、`tool handler`

第一阶段不负责：

- 注册命令类 tools
- 图片下载与解析

## 30. MCP 第一阶段错误边界

第一阶段最少处理以下错误：

1. `MCP_CONFIG_INVALID`
   - 环境变量缺失或非法
2. `MCP_RELAY_REQUEST_FAILED`
   - HTTP 请求失败、超时、网络错误
3. `MCP_RELAY_RESPONSE_INVALID`
   - Relay 返回的 JSON 不符合共享协议 schema
4. Relay 标准业务错误
   - 若 Relay 返回标准 `ErrorResponse`，MCP 直接透传 `error.code`

## 31. MCP 第一阶段输出策略

为了让第一阶段状态链路最简单、最稳，`rokid.get_device_status` 的输出应尽量沿用 Relay DTO。

建议第一阶段输出等价于：

```ts
type RokidGetDeviceStatusOutput = GetDeviceStatusResponse
```

说明：

- 第一阶段不为了 AI 友好额外改写结构
- 先保证 Relay -> MCP -> AI 的状态链路完全一致

## 32. MCP 第一阶段范围冻结

第一阶段真正实现：

- `config/env.ts`
- `lib/errors.ts`
- `lib/logger.ts`
- `relay/relay-client.ts`
- `relay/relay-response-validator.ts`
- `mapper/result-mapper.ts`
- `tools/get-device-status.ts`
- `server.ts`
- `index.ts`

第一阶段明确不进入实现：

- `tools/display-text.ts`
- `tools/capture-photo.ts`
- `command/command-poller.ts`
- `image/*`

## 33. 总体第一阶段实现顺序

至此，完整第一阶段实现顺序冻结为：

1. 在 `packages/protocol` 中补齐共享 schema / types / constants
2. 在 `apps/relay-server` 中实现 `route -> manager -> singleton stores`
3. 在 Phone 端实现 `RelaySessionClient` 对接 `hello / hello_ack / heartbeat / phone_state_update`
4. 在 `packages/mcp-server` 中实现最小状态消费层：`rokid.get_device_status`
5. 联调 `MCP -> Relay -> Phone -> Glasses` 的可信状态查询链路
