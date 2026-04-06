# Phone-Glasses Module Design

## 1. 目标

本文档定义 `apps/phone-android/` 与 `apps/glasses-android/` 在 MVP 阶段的核心模块边界、Public API、状态归属、事件模型与错误收口方式。

本文档的目标是：

- 让两端按一致的分层思想实现
- 避免一个大而全的 `Manager`
- 冻结“谁拥有什么状态、谁监听谁、谁最终处理错误”

## 2. 依赖文档

- `docs/phone-glasses-protocol-constants.md`
- `docs/phone-glasses-protocol-schemas.md`
- `docs/phone-glasses-protocol-sequences.md`

## 3. 模块划分原则

本次设计遵循以下原则：

- Android 组件只做生命周期宿主，不承载协议与业务逻辑
- 传输层、协议会话层、命令/文件业务层、运行态聚合层分离
- 每一份状态只允许一个 owner
- 同步一对一依赖优先直接方法调用
- 异步广播优先使用模块级 `SharedFlow<SealedEvent>`
- 模块内部串行处理优先使用 `Channel`
- 单次等待 ack/result 之类的场景优先使用 `CompletableDeferred`
- 最终命令失败只能单点收口

## 4. Kotlin / OOP 实践约定

### 4.1 类型约定

- 闭集协议值使用 `enum class`
- DTO、结果、快照使用 `data class`
- 离散事件使用 `sealed interface`
- 平台/外设边界使用接口
- 业务编排类直接用 concrete class

### 4.2 边界接口化原则

适合抽接口的边界：

- 蓝牙传输
- Relay 客户端
- 相机能力
- 显示能力
- 文件存储
- 校验算法
- 时钟

不需要为了“好看”强行抽接口的对象：

- `RelayCommandBridge`
- `CommandDispatcher`
- `IncomingImageAssembler`
- `OutgoingImageSender`
- `ExclusiveExecutionGuard`

### 4.3 类命名语义

| 后缀 | 用途 |
| - | - |
| `Controller` | 顶层生命周期 / 编排入口 |
| `Transport` | socket 与 bytes |
| `Session` | 协议会话与 keepalive |
| `Store` | 状态 owner |
| `Bridge` | 两套协议 / 两个系统之间的映射 |
| `Dispatcher` | 命令分发与统一收口 |
| `Executor` | 具体能力执行 |
| `Guard` | 业务约束控制 |

## 5. 总体分层

```text
bootstrap
  -> transport
  -> protocol/session
  -> command/media
  -> runtime/ui
```

解释：

- `bootstrap`: Android `Service` / 顶层 controller
- `transport`: Bluetooth socket 与 raw bytes
- `protocol/session`: frame codec、`hello`、`ping/pong`、message dispatch
- `command/media`: command 业务、文件收发、上传桥接
- `runtime/ui`: 运行态聚合、UI / 调试页读取

## 6. 共享基础能力建议

### 6.1 基础接口

```kotlin
interface Clock {
    fun nowMs(): Long
}

interface ChecksumCalculator {
    fun crc32(bytes: ByteArray): String
    fun sha256(file: File): String
}

interface TempFileStore {
    fun createTempImageFile(requestId: String, transferId: String): File
    fun deleteQuietly(file: File)
}
```

### 6.2 Frame Codec

```kotlin
data class DecodedFrame(
    val header: LocalFrameHeader<*>,
    val body: ByteArray?,
)

interface LocalFrameCodec {
    fun encode(header: LocalFrameHeader<*>, body: ByteArray? = null): ByteArray
    fun decode(bytes: ByteArray): DecodedFrame
}
```

职责：

- 只负责 frame 编解码
- 不处理 `hello` / `command` 业务逻辑

## 7. Phone 端模块设计

## 7.1 模块总览

```text
PhoneGatewayService
  -> PhoneAppController
      -> RelaySessionClient
      -> PhoneLocalLinkSession
      -> RelayCommandBridge
      -> PhoneRuntimeStore

PhoneLocalLinkSession
  -> RfcommClientTransport
  -> LocalFrameCodec

RelayCommandBridge
  -> RelaySessionClient
  -> PhoneLocalLinkSession
  -> ActiveCommandRegistry
  -> IncomingImageAssembler
  -> RelayImageUploader
  -> PhoneRuntimeStore
```

## 7.2 `PhoneGatewayService`

职责：

- Android 生命周期宿主
- 启动 / 停止整个 gateway
- 持有 `PhoneAppController`

不负责：

- 不直接处理蓝牙协议
- 不直接处理 Relay 命令

Public API：

```kotlin
class PhoneGatewayService : LifecycleService() {
    override fun onCreate()
    override fun onDestroy()
}
```

## 7.3 `PhoneAppController`

职责：

- 顶层编排器
- 装配模块
- 控制整体 start / stop
- 订阅状态并驱动 `PhoneRuntimeStore`

Public API：

```kotlin
class PhoneAppController(
    private val relayClient: RelaySessionClient,
    private val localSession: PhoneLocalLinkSession,
    private val commandBridge: RelayCommandBridge,
    private val runtimeStore: PhoneRuntimeStore,
) {
    suspend fun start(targetDeviceAddress: String)
    suspend fun stop(reason: String)
}
```

## 7.4 `RfcommClientTransport`

职责：

- 建立 Phone -> Glasses RFCOMM 连接
- 管理 socket 读写循环
- 暴露原始 frame bytes

拥有状态：

- 蓝牙连接状态

Public API：

```kotlin
enum class BluetoothTransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STALE,
    ERROR,
}

sealed interface TransportEvent {
    data class Connected(val deviceAddress: String) : TransportEvent
    data class Disconnected(val reason: String) : TransportEvent
    data class FrameBytesReceived(val bytes: ByteArray) : TransportEvent
    data class SendFailed(val error: Throwable) : TransportEvent
    data class ReadFailed(val error: Throwable) : TransportEvent
}

interface BluetoothTransport {
    val state: StateFlow<BluetoothTransportState>
    val events: SharedFlow<TransportEvent>

    suspend fun connect(deviceAddress: String)
    suspend fun disconnect(reason: String)
    suspend fun sendFrame(frameBytes: ByteArray)
}
```

约束：

- transport 不理解 `hello`、`ping/pong`、`command`
- transport 只服务于 `PhoneLocalLinkSession`

## 7.5 `PhoneLocalLinkSession`

职责：

- `hello / hello_ack`
- `ping / pong`
- `lastSeenAt`
- idle timer / pong timeout
- frame decode / encode
- typed message dispatch

拥有状态：

- 协议会话状态
- keepalive 状态

Public API：

```kotlin
enum class PhoneSessionState {
    IDLE,
    HANDSHAKING,
    READY,
    EXECUTING_EXCLUSIVE,
    ERROR,
    CLOSED,
}

sealed interface PhoneSessionEvent {
    data class HelloAccepted(val payload: HelloAckPayload) : PhoneSessionEvent
    data class PongReceived(val seq: Long) : PhoneSessionEvent
    data class CommandAckReceived(val message: LocalCommandAckMessage) : PhoneSessionEvent
    data class CommandStatusReceived(val message: LocalCommandStatusMessage) : PhoneSessionEvent
    data class CommandResultReceived(val message: LocalFrameHeader<*>) : PhoneSessionEvent
    data class CommandErrorReceived(val message: LocalCommandErrorMessage) : PhoneSessionEvent
    data class ChunkStartReceived(val message: ChunkStartMessage) : PhoneSessionEvent
    data class ChunkDataReceived(val message: ChunkDataMessage, val body: ByteArray) : PhoneSessionEvent
    data class ChunkEndReceived(val message: ChunkEndMessage) : PhoneSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : PhoneSessionEvent
    data class SessionClosed(val reason: String) : PhoneSessionEvent
}

class PhoneLocalLinkSession(
    private val transport: BluetoothTransport,
    private val codec: LocalFrameCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<PhoneSessionState>
    val events: SharedFlow<PhoneSessionEvent>

    suspend fun openHandshake(
        deviceId: String,
        appVersion: String,
        supportedActions: List<LocalAction>,
    )

    suspend fun sendCommand(message: LocalFrameHeader<*>)
    suspend fun sendMessage(message: LocalFrameHeader<*>, body: ByteArray? = null)
    suspend fun close(reason: String)
}
```

说明：

- `ping / pong` 固定在这个类里实现
- 不下沉到底层 `Transport`
- 内部建议使用 `Channel` 串行处理入站 frame

## 7.6 `RelaySessionClient`

职责：

- 管理 Phone <-> Relay WS / HTTP
- 接收 Relay `command`
- 发送 `command_ack / status / result / error`
- 上传图片到 Relay

Public API：

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
    data class RelayCommandReceived(val command: RelayCommandMessage) : RelayClientEvent
    data class RelayError(val error: Throwable) : RelayClientEvent
}

class RelaySessionClient(
    private val clock: Clock,
) {
    val state: StateFlow<RelayLinkState>
    val events: SharedFlow<RelayClientEvent>

    suspend fun connect()
    suspend fun disconnect(reason: String)

    suspend fun sendCommandAck(message: CommandAckMessage)
    suspend fun sendCommandStatus(message: CommandStatusMessage)
    suspend fun sendCommandResult(message: CommandResultMessage)
    suspend fun sendCommandError(message: CommandErrorMessage)

    suspend fun uploadImage(
        uploadUrl: String,
        requestId: String,
        deviceId: String,
        imageFile: File,
        mimeType: String,
        sha256: String?,
    ): RelayUploadResult
}
```

## 7.7 `ActiveCommandRegistry`

职责：

- 维护当前唯一 active command
- 维护中间结果与传输上下文
- 防止重复收口

Public API：

```kotlin
data class ActiveCommand(
    val requestId: String,
    val action: LocalAction,
    val transferId: String? = null,
    val terminal: Boolean = false,
)

class ActiveCommandRegistry {
    val activeCommand: StateFlow<ActiveCommand?>

    fun begin(command: RelayCommandMessage)
    fun requireActive(requestId: String): ActiveCommand
    fun markAcked(requestId: String)
    fun markImageTransferStarted(requestId: String, transferId: String)
    fun attachTempImage(requestId: String, image: TempImageHandle)
    fun cacheLocalResult(requestId: String, result: CapturePhotoResult)
    fun complete(requestId: String)
    fun fail(requestId: String, code: String)
    fun clear()
}
```

## 7.8 `IncomingImageAssembler`

职责：

- 处理 `chunk_start / chunk_data / chunk_end`
- 顺序落盘
- 校验 `offset / size / chunkChecksum / sha256`
- 成功后产出 `TempImageHandle`

Public API：

```kotlin
data class TempImageHandle(
    val requestId: String,
    val transferId: String,
    val file: File,
    val mimeType: String,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val sha256: String?,
)

class IncomingImageAssembler(
    private val tempFileStore: TempFileStore,
    private val checksumCalculator: ChecksumCalculator,
) {
    suspend fun begin(message: ChunkStartMessage)
    suspend fun append(message: ChunkDataMessage, body: ByteArray)
    suspend fun finish(message: ChunkEndMessage): TempImageHandle
    suspend fun abort(requestId: String, transferId: String)
}
```

约束：

- 上传 Relay 失败前，图片只存在临时文件里
- 校验失败必须立即删除临时文件

## 7.9 `RelayImageUploader`

职责：

- 上传 `TempImageHandle` 到 Relay
- 无论成功或失败都负责删除本地临时文件

Public API：

```kotlin
class RelayImageUploader(
    private val relayClient: RelaySessionClient,
    private val tempFileStore: TempFileStore,
) {
    suspend fun uploadAndDelete(
        requestId: String,
        deviceId: String,
        uploadUrl: String,
        image: TempImageHandle,
    ): RelayUploadResult
}
```

## 7.10 `RelayCommandBridge`

职责：

- 接收 Relay 入站命令
- 下发本地命令到 `PhoneLocalLinkSession`
- 监听 session 事件
- 驱动图片接收、上传、Relay 收口
- 最终处理命令失败

它是 Phone 端最终命令收口 owner。

Public API：

```kotlin
sealed interface PhoneDomainEvent {
    data class ActiveCommandChanged(val requestId: String?) : PhoneDomainEvent
    data class LocalCommandForwarding(val requestId: String) : PhoneDomainEvent
    data class LocalImageAssembled(val requestId: String, val file: File) : PhoneDomainEvent
    data class CommandCompleted(val requestId: String) : PhoneDomainEvent
    data class CommandFailed(val requestId: String, val code: String) : PhoneDomainEvent
}

class RelayCommandBridge(
    private val relayClient: RelaySessionClient,
    private val localSession: PhoneLocalLinkSession,
    private val commandRegistry: ActiveCommandRegistry,
    private val imageAssembler: IncomingImageAssembler,
    private val imageUploader: RelayImageUploader,
    private val runtimeStore: PhoneRuntimeStore,
    private val scope: CoroutineScope,
) {
    val events: SharedFlow<PhoneDomainEvent>

    suspend fun start()
    suspend fun stop()

    suspend fun handleRelayCommand(command: RelayCommandMessage)
    suspend fun onSessionEvent(event: PhoneSessionEvent)
    suspend fun onRelayEvent(event: RelayClientEvent)
    suspend fun failActiveCommand(
        code: String,
        message: String,
        retryable: Boolean,
        closeLocalSession: Boolean,
    )
}
```

## 7.11 `PhoneRuntimeStore`

职责：

- 聚合对外运行态
- 给 UI 与 Relay `phone_state_update` 提供统一快照

Public API：

```kotlin
data class PhoneRuntimeSnapshot(
    val setupState: String,
    val runtimeState: String,
    val uplinkState: String,
    val activeCommandRequestId: String?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)

class PhoneRuntimeStore {
    val snapshot: StateFlow<PhoneRuntimeSnapshot>

    fun updateBluetoothState(state: BluetoothTransportState)
    fun updateLocalSessionState(state: PhoneSessionState)
    fun updateRelayState(state: RelayLinkState)
    fun setActiveCommand(requestId: String?)
    fun setLastError(code: String?, message: String?)
}
```

## 8. Glasses 端模块设计

## 8.1 模块总览

```text
GlassesGatewayService
  -> GlassesAppController
      -> RfcommServerTransport
      -> GlassesLocalLinkSession
      -> CommandDispatcher
      -> GlassesRuntimeStore

CommandDispatcher
  -> ExclusiveExecutionGuard
  -> DisplayTextExecutor
  -> CapturePhotoExecutor
  -> OutgoingImageSender
  -> GlassesLocalLinkSession
```

## 8.2 `GlassesGatewayService`

职责：

- Android 生命周期宿主
- 启动 server accept、session 与 dispatcher

Public API：

```kotlin
class GlassesGatewayService : LifecycleService() {
    override fun onCreate()
    override fun onDestroy()
}
```

## 8.3 `GlassesAppController`

职责：

- 顶层编排器
- 装配 transport / session / dispatcher / runtime store

Public API：

```kotlin
class GlassesAppController(
    private val transport: RfcommServerTransport,
    private val session: GlassesLocalLinkSession,
    private val dispatcher: CommandDispatcher,
    private val runtimeStore: GlassesRuntimeStore,
) {
    suspend fun start()
    suspend fun stop(reason: String)
}
```

## 8.4 `RfcommServerTransport`

职责：

- `BluetoothServerSocket.accept()`
- 校验远端设备是否已 pair
- 单连接控制
- 暴露原始 frame bytes

Public API：

```kotlin
sealed interface GlassesTransportEvent {
    data class ClientAccepted(val remoteAddress: String) : GlassesTransportEvent
    data class ClientDisconnected(val reason: String) : GlassesTransportEvent
    data class FrameBytesReceived(val bytes: ByteArray) : GlassesTransportEvent
    data class TransportError(val error: Throwable) : GlassesTransportEvent
}

class RfcommServerTransport(
    private val bluetoothAdapter: BluetoothAdapter,
) : BluetoothTransport {
    suspend fun startListening()
    suspend fun stopListening()
}
```

## 8.5 `GlassesLocalLinkSession`

职责：

- 处理 `hello` 并回 `hello_ack`
- 处理 `ping` 并回 `pong`
- frame decode / encode
- 输出 typed inbound message

Public API：

```kotlin
enum class GlassesSessionState {
    LISTENING,
    PAIR_CHECK,
    HANDSHAKING,
    SESSION_READY,
    EXECUTING_EXCLUSIVE,
    SESSION_ERROR,
    CLOSED,
}

sealed interface GlassesSessionEvent {
    data class HelloReceived(val message: HelloMessage) : GlassesSessionEvent
    data class PingReceived(val message: PingMessage) : GlassesSessionEvent
    data class CommandReceived(val message: LocalFrameHeader<*>) : GlassesSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : GlassesSessionEvent
    data class SessionClosed(val reason: String) : GlassesSessionEvent
}

class GlassesLocalLinkSession(
    private val transport: BluetoothTransport,
    private val codec: LocalFrameCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<GlassesSessionState>
    val events: SharedFlow<GlassesSessionEvent>

    suspend fun start()
    suspend fun sendAck(message: LocalCommandAckMessage)
    suspend fun sendStatus(message: LocalCommandStatusMessage)
    suspend fun sendResult(message: LocalFrameHeader<*>)
    suspend fun sendError(message: LocalCommandErrorMessage)
    suspend fun sendChunk(message: LocalFrameHeader<*>, body: ByteArray)
    suspend fun close(reason: String)
}
```

说明：

- `hello / ping` 的协议级必答逻辑在 session 内部处理
- `command` 再向上交给 `CommandDispatcher`

## 8.6 `ExclusiveExecutionGuard`

职责：

- 管理独占命令锁
- 保证 `capture_photo` 同一时刻只能执行一个

Public API：

```kotlin
class ExclusiveExecutionGuard {
    fun tryAcquire(requestId: String, action: LocalAction): Boolean
    fun release(requestId: String)
    fun isBusy(): Boolean
    fun currentRequestId(): String?
}
```

## 8.7 `DisplayTextExecutor`

职责：

- 只负责文本显示

Public API：

```kotlin
interface DisplayGateway {
    suspend fun showText(text: String, durationMs: Long)
}

class DisplayTextExecutor(
    private val displayGateway: DisplayGateway,
    private val clock: Clock,
) {
    suspend fun execute(params: DisplayTextParams): DisplayTextResult
}
```

## 8.8 `CapturePhotoExecutor`

职责：

- 只负责拍照
- 返回本地图片句柄

Public API：

```kotlin
data class CapturedImage(
    val file: File,
    val mimeType: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val sha256: String?,
)

interface CameraGateway {
    suspend fun capture(quality: String?): CapturedImage
}

class CapturePhotoExecutor(
    private val cameraGateway: CameraGateway,
) {
    suspend fun execute(params: CapturePhotoParams): CapturedImage
}
```

## 8.9 `OutgoingImageSender`

职责：

- 把 `CapturedImage` 切成 `chunk_*`
- 为每个 chunk 计算 `CRC32`
- 通过 session 发送图片
- 只负责传图，不负责发最终 `command_result`

Public API：

```kotlin
sealed interface ImageSendEvent {
    data class TransferStarted(val requestId: String, val transferId: String) : ImageSendEvent
    data class ChunkSent(val requestId: String, val index: Int, val bytes: Int) : ImageSendEvent
    data class TransferCompleted(val requestId: String, val transferId: String) : ImageSendEvent
    data class TransferFailed(val requestId: String, val error: Throwable) : ImageSendEvent
}

class OutgoingImageSender(
    private val session: GlassesLocalLinkSession,
    private val checksumCalculator: ChecksumCalculator,
) {
    val events: SharedFlow<ImageSendEvent>

    suspend fun sendCapturedImage(
        requestId: String,
        transferId: String,
        image: CapturedImage,
    )
}
```

约束：

- `OutgoingImageSender` 不直接发最终 `command_error`
- `TransferCompleted` 后由 `CommandDispatcher` 再发 `command_result`

## 8.10 `CommandDispatcher`

职责：

- 接收本地 `command`
- 做接单校验
- 调对应 executor
- 接收 `OutgoingImageSender` 事件
- 统一发 `command_ack / status / result / error`
- 最终处理当前命令失败

它是 Glasses 端最终命令失败收口 owner。

Public API：

```kotlin
sealed interface GlassesDomainEvent {
    data class CommandAccepted(val requestId: String, val action: LocalAction) : GlassesDomainEvent
    data class CommandRejected(val requestId: String, val code: String) : GlassesDomainEvent
    data class ExecutionStarted(val requestId: String, val action: LocalAction) : GlassesDomainEvent
    data class ImageTransferStarted(val requestId: String, val transferId: String) : GlassesDomainEvent
    data class ImageTransferCompleted(val requestId: String, val transferId: String) : GlassesDomainEvent
    data class CommandCompleted(val requestId: String) : GlassesDomainEvent
    data class CommandFailed(val requestId: String, val code: String) : GlassesDomainEvent
}

class CommandDispatcher(
    private val session: GlassesLocalLinkSession,
    private val exclusiveGuard: ExclusiveExecutionGuard,
    private val displayExecutor: DisplayTextExecutor,
    private val captureExecutor: CapturePhotoExecutor,
    private val imageSender: OutgoingImageSender,
    private val runtimeStore: GlassesRuntimeStore,
    private val scope: CoroutineScope,
) {
    val events: SharedFlow<GlassesDomainEvent>

    suspend fun start()
    suspend fun stop()
    suspend fun handleCommand(message: LocalFrameHeader<*>)
    suspend fun onImageSendEvent(event: ImageSendEvent)
    suspend fun failCurrentCommand(
        requestId: String,
        action: LocalAction,
        code: String,
        message: String,
        retryable: Boolean,
        canReplyToPhone: Boolean,
    )
}
```

## 8.11 `GlassesRuntimeStore`

职责：

- 聚合 Glasses 对外运行态

Public API：

```kotlin
data class GlassesRuntimeSnapshot(
    val runtimeState: String,
    val activeCommandRequestId: String?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)

class GlassesRuntimeStore {
    val snapshot: StateFlow<GlassesRuntimeSnapshot>

    fun updateTransportState(state: BluetoothTransportState)
    fun updateSessionState(state: GlassesSessionState)
    fun setBusy(requestId: String?)
    fun setError(code: String?, message: String?)
}
```

## 9. 事件模型设计

## 9.1 不使用全局 EventBus

MVP 明确不建议使用全局 `EventBus` 单例或字符串 topic 机制。

推荐模式：

- 模块之间：`SharedFlow<SealedEvent>`
- 模块内部：`Channel`
- 单次等待：`CompletableDeferred`
- 持续状态：`StateFlow`

## 9.2 监听关系

### 9.2.1 Phone 侧

```text
RfcommClientTransport.events
  -> PhoneLocalLinkSession 监听

PhoneLocalLinkSession.events
  -> RelayCommandBridge 监听
  -> PhoneRuntimeStore 监听

RelaySessionClient.events
  -> RelayCommandBridge 监听
  -> PhoneRuntimeStore 监听

RelayCommandBridge.events
  -> PhoneRuntimeStore 监听
```

### 9.2.2 Glasses 侧

```text
RfcommServerTransport.events
  -> GlassesLocalLinkSession 监听

GlassesLocalLinkSession.events
  -> CommandDispatcher 监听
  -> GlassesRuntimeStore 监听

OutgoingImageSender.events
  -> CommandDispatcher 监听

CommandDispatcher.events
  -> GlassesRuntimeStore 监听
```

## 9.3 触发点约束

- transport 只发 transport 级事件
- session 只发协议级事件
- executor 不直接发最终 command failure
- 最终 command failure 只能由 `RelayCommandBridge` / `CommandDispatcher` 发 domain 事件

## 10. 错误处理与最终收口

## 10.1 总原则

错误处理固定为：

```text
底层模块发现错误
  -> 做自己的 cleanup
  -> emit typed error event / throw typed exception
  -> 上层协调器接管
  -> 最终命令失败单点收口
```

## 10.2 Phone 端错误分工

| 模块 | 出错后做什么 | 最终谁收口 |
| - | - | - |
| `RfcommClientTransport` | 关 socket，停读写，发 `TransportEvent` | `PhoneLocalLinkSession` |
| `PhoneLocalLinkSession` | 停 keepalive，关 session，发 `SessionFailed` | `RelayCommandBridge` |
| `IncomingImageAssembler` | 删临时文件，清 transfer context，抛异常 | `RelayCommandBridge` |
| `RelayImageUploader` | 删除临时文件，抛异常 | `RelayCommandBridge` |
| `RelaySessionClient` | 更新 uplink 状态，发 relay error event | `RelayCommandBridge` / `PhoneRuntimeStore` |
| `RelayCommandBridge` | 向 Relay 发 `command_error`，清 active command | 自己 |

## 10.3 Glasses 端错误分工

| 模块 | 出错后做什么 | 最终谁收口 |
| - | - | - |
| `RfcommServerTransport` | 关 socket，发 transport error | `GlassesLocalLinkSession` |
| `GlassesLocalLinkSession` | 关 session，发 `SessionFailed` | `CommandDispatcher` |
| `DisplayTextExecutor` | 抛业务异常 | `CommandDispatcher` |
| `CapturePhotoExecutor` | 抛业务异常 | `CommandDispatcher` |
| `OutgoingImageSender` | 停止传输，发 `TransferFailed` | `CommandDispatcher` |
| `CommandDispatcher` | 发 `command_error`，释放独占锁，清上下文 | 自己 |

## 10.4 重复收口保护

每个活跃命令上下文都必须维护终态保护：

- `COMPLETED`
- `FAILED`

进入任一终态后，后续重复事件必须直接忽略，防止：

- 重复发 `command_error`
- 重复释放 busy lock
- 重复删除临时文件

## 11. 模块间不变量

1. `ping / pong` 只能由 Session 层处理
2. Transport 层不得理解协议消息语义
3. `BUSY` 只表示链路健康但正在执行独占任务
4. `capture_photo` 的最终成功必须满足“文件传输完成 + 文件校验通过”
5. 上传 Relay 失败后，本地临时图片文件必须立即删除
6. `OutgoingImageSender` 不得直接发最终 `command_result`
7. `IncomingImageAssembler` 不得直接上传 Relay
8. 最终命令失败只能由单一 owner 收口

## 12. 实现优先级建议

### 12.1 Phase 1

- `RfcommClientTransport`
- `RfcommServerTransport`
- `PhoneLocalLinkSession`
- `GlassesLocalLinkSession`

目标：先跑通建链、握手、保活。

### 12.2 Phase 2

- `DisplayTextExecutor`
- `CommandDispatcher`
- `RelayCommandBridge`

目标：先打通 `display_text` 最小闭环。

### 12.3 Phase 3

- `CapturePhotoExecutor`
- `OutgoingImageSender`
- `IncomingImageAssembler`
- `RelayImageUploader`

目标：打通 `capture_photo` 主链路。

## 13. 测试建议

每个关键模块最少要覆盖：

### 13.1 Session

- `hello / hello_ack` 成功
- `hello_ack` 超时失败
- 空闲时 `ping / pong` 成功
- `pong` 超时进入失败

### 13.2 Command 业务层

- `display_text` 从 command 到 result 成功
- `capture_photo` 从 command 到 chunk 到 result 成功
- 当前 command 失败后只收口一次

### 13.3 文件层

- chunk `offset` 连续时成功
- chunk `CRC32` 错误时失败
- 最终 `sha256` 错误时失败
- 上传 Relay 失败后文件立即删除

## 14. 下一步

当本设计确认后，下一步最适合进入实现准备：

1. 在 Android 两端初始化协议模型与 codec 骨架
2. 先实现 Session 层
3. 再实现 `display_text` 主链路
4. 最后接入 `capture_photo` 与文件传输
