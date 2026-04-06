# Rokid MCP MVP 技术实现文档

## 1. 目标

本文档定义 Rokid 智能眼镜 MCP MVP 的技术方案。MVP 目标是先跑通以下最小闭环：

- AI 通过 MCP 调用 `display_text`
- AI 通过 MCP 调用 `capture_photo`
- 眼镜端执行命令，并将结果回传给 AI
- 手机端状态机明确区分“未初始化”和“可用”状态

当前阶段重点是验证整体链路可行性，不以前后台切换保活、弱网鲁棒性、极致性能为目标。

## 2. MVP 范围

### 2.1 包含内容

- 眼镜端 Android App
- 手机端 Android App
- 经典蓝牙 Socket 通信
- Relay Server 与手机端 WebSocket 通信
- MCP Server/Package 暴露工具给 AI
- 基础设备状态查询
- 手机端初始化流程与状态机

### 2.2 暂不包含

- 后台长时间保活
- 前后台切换后的自动恢复
- 多设备复杂调度
- 生产级鉴权体系
- 图片长期存储体系
- A2UI 自定义界面渲染
- 音频、视频、流式相机预览

## 3. 总体架构

```text
                 +-----------------------+
                 |      AI Client        |
                 | Claude / GPT / Agent  |
                 +-----------+-----------+
                             |
                             | MCP
                             v
                 +-----------------------+
                 |   MCP Server/Package  |
                 | tools + protocol map  |
                 +-----------+-----------+
                             |
                             | WebSocket / HTTP
                             v
                 +-----------------------+
                 |     Relay Server      |
                 | session + routing     |
                 +-----------+-----------+
                             |
                             | WebSocket
                             v
                 +-----------------------+
                 |    Phone Gateway App  |
                 | BT bridge + uplink    |
                 +-----------+-----------+
                             |
                             | Bluetooth Classic Socket
                             v
                 +-----------------------+
                 |    Glasses Android    |
                 | capture + render      |
                 +-----------------------+
```

## 4. 分层职责

### 4.1 眼镜端 App

职责：

- 接收手机端命令
- 调用系统能力拍照
- 显示文字内容
- 将执行结果返回给手机端

MVP 约束：

- 前台运行即可
- 不要求后台自动恢复
- 拍照可以接受较简单的交互流程

### 4.2 手机端 App

职责：

- 管理蓝牙配对与连接
- 作为眼镜端与 Relay 的桥接层
- 负责命令转发、结果回传、基础重试
- 负责将图片结果上传或转发给 Relay
- 维护初始化状态、连接状态、执行状态

手机端是 MVP 的核心控制枢纽。

### 4.3 Relay Server

职责：

- 管理手机设备连接
- 将 MCP 请求路由到指定手机端
- 跟踪请求生命周期
- 将执行结果回传给 MCP

MVP 阶段可先做成单实例服务，不必考虑水平扩展。

### 4.4 MCP Server/Package

职责：

- 向 AI 暴露工具接口
- 将工具调用转换为 Relay 请求
- 屏蔽设备连接和协议细节
- 输出 AI 易消费的结构化结果

## 5. 推荐技术选型

### 5.1 语言

- 眼镜端 App：Kotlin
- 手机端 App：Kotlin
- Relay Server：Node.js + TypeScript
- MCP Package：Node.js + TypeScript

### 5.2 选择理由

Node.js + TypeScript 更适合 Relay 与 MCP 层，原因如下：

- 与 MCP 生态更贴近
- WebSocket 与 JSON 协议开发效率高
- 类型可在 Relay 和 MCP 之间共享
- 后续接入 Web 控制台或调试工具更顺畅
- 当前仓库约束已指定使用 bun 作为包管理器

Python 更适合后续补充以下能力，而不是作为当前主栈：

- OCR
- 图像分析
- 视觉模型推理
- 媒体后处理

结论：MVP 主栈建议使用 Kotlin + Node.js/TypeScript。

## 6. 手机端状态机

手机端建议至少实现以下状态：

```text
UNINITIALIZED
  -> 未完成初始化，例如未配对、未授权、未选择目标眼镜设备

PAIRING
  -> 正在配对或建立首次连接

DISCONNECTED
  -> 已初始化，但当前未建立蓝牙链路

CONNECTING
  -> 正在连接眼镜端蓝牙 Socket

READY
  -> 蓝牙连接已建立，可接收和转发命令

BUSY
  -> 正在执行拍照等独占型任务

ERROR
  -> 当前出现异常，需要用户干预或重新初始化
```

推荐状态流转：

```text
UNINITIALIZED -> PAIRING -> DISCONNECTED -> CONNECTING -> READY -> BUSY -> READY
       ^                             |            |          |
       |                             |            |          v
       +------------- ERROR <--------+------------+------ DISCONNECTED
```

状态说明：

- `UNINITIALIZED`：MVP 必须保留，用于表达“尚未配对/未授权/未选设备”
- `READY`：只有在蓝牙链路可用，且眼镜端握手完成后才能进入
- `BUSY`：执行拍照时进入，避免并发冲突
- `ERROR`：记录最近一次失败原因，便于 UI 和日志展示

### 6.1 初始化判定条件

手机端只有在以下条件都满足后，才能从 `UNINITIALIZED` 进入 `DISCONNECTED`：

- 已授予蓝牙相关权限
- 已完成与目标眼镜设备的配对，或已保存目标设备地址
- 已完成本地配置初始化，例如 `deviceId`、Relay 地址
- 应用已完成基础握手准备，可以开始建立蓝牙连接

只要上述任一条件失效，就应回退到 `UNINITIALIZED`，而不是错误地显示为 `DISCONNECTED`。

### 6.2 MVP 状态机实现建议

建议在手机端将状态拆成两层：

- `setupState`：`UNINITIALIZED` / `INITIALIZED`
- `runtimeState`：`DISCONNECTED` / `CONNECTING` / `READY` / `BUSY` / `ERROR`

对外展示时可以仍然合并成单一状态枚举，但内部实现拆层后更容易判断：

- 当前问题是初始化没完成
- 还是初始化完成但连接断开
- 还是连接正常但任务执行失败

MVP 如果不想引入复杂状态管理框架，可直接在 Android 端用 Kotlin sealed class 或 enum + data class 实现。

## 7. 通信设计

### 7.1 设计原则

- 控制面与数据面分离
- 所有请求带 `requestId`
- 所有响应带 `requestId`
- 明确 `ack`、`result`、`error` 三类消息
- 图片采用分片传输，避免单包过大

### 7.2 蓝牙链路

MVP 使用 Bluetooth Classic RFCOMM Socket。

原因：

- 适合传输图片等较大数据
- 双向长连接模型简单
- 比 BLE 更适合当前场景

建议：

- 蓝牙层跑自定义应用层协议
- 消息头使用固定长度 + payload 长度
- payload 可先用 JSON，二进制图片单独分片传输

### 7.3 Relay 链路

手机端与 Relay 使用 WebSocket 长连接。

原因：

- 双向通信简单
- 便于服务端主动下发命令
- 适合设备在线状态同步

MVP 阶段无需复杂消息队列、离线投递、持久化任务表，进程内路由即可。

## 8. 协议草案

### 8.1 统一消息结构

控制消息统一为 JSON：

```json
{
  "type": "command",
  "requestId": "req_123",
  "deviceId": "rokid_glasses_01",
  "action": "capture_photo",
  "payload": {
    "text": "hello"
  },
  "timestamp": 1710000000000
}
```

### 8.2 消息类型

- `hello`：连接建立后的身份声明
- `ack`：收到命令
- `command`：执行命令
- `progress`：可选，表示执行中
- `result`：成功结果
- `error`：失败结果
- `chunk_start`：图片分片开始
- `chunk_data`：图片分片数据
- `chunk_end`：图片分片结束

### 8.3 典型命令

#### `display_text`

```json
{
  "type": "command",
  "requestId": "req_124",
  "action": "display_text",
  "payload": {
    "text": "前方有路，请继续前进",
    "durationMs": 5000
  }
}
```

#### `capture_photo`

```json
{
  "type": "command",
  "requestId": "req_125",
  "action": "capture_photo",
  "payload": {
    "quality": "medium"
  }
}
```

### 8.4 结果消息

#### 文本显示结果

```json
{
  "type": "result",
  "requestId": "req_124",
  "status": "ok",
  "payload": {
    "displayed": true
  }
}
```

#### 拍照结果

```json
{
  "type": "result",
  "requestId": "req_125",
  "status": "ok",
  "payload": {
    "imageId": "img_001",
    "mimeType": "image/jpeg",
    "size": 2483921,
    "width": 1920,
    "height": 1080
  }
}
```

### 8.5 错误消息

```json
{
  "type": "error",
  "requestId": "req_125",
  "code": "CAMERA_UNAVAILABLE",
  "message": "camera is busy"
}
```

建议 MVP 先定义少量错误码：

- `NOT_INITIALIZED`
- `BLUETOOTH_DISCONNECTED`
- `DEVICE_BUSY`
- `CAMERA_UNAVAILABLE`
- `DISPLAY_FAILED`
- `TIMEOUT`
- `UNKNOWN_ERROR`

## 9. 图片传输策略

MVP 有两种可行方案：

### 9.1 方案 A：图片经手机转发到 Relay，再由 MCP 返回引用

流程：

- 眼镜拍照
- 图片通过蓝牙分片发给手机
- 手机通过 WebSocket 或 HTTP 上传给 Relay
- Relay 返回 `imageId` 或临时 URL
- MCP 将该引用返回给 AI

优点：

- 比在 MCP 里直接传大 base64 更稳
- 后续更容易扩展存储、预览、下载

这是推荐方案。

### 9.2 方案 B：MVP 临时返回 base64

优点：实现快。

缺点：

- 消息体大
- 结果不稳定
- 不利于后续扩展

建议只用于本地调试，不作为默认正式路径。

## 10. MCP 工具设计

MVP 建议先暴露 3 个工具：

### 10.1 `rokid.get_device_status`

用途：查询设备在线状态与手机端状态机状态。

返回示例：

```json
{
  "deviceId": "rokid_glasses_01",
  "phoneState": "READY",
  "connected": true,
  "initialized": true,
  "lastError": null
}
```

### 10.2 `rokid.display_text`

入参：

```json
{
  "text": "你好",
  "durationMs": 3000
}
```

返回：

```json
{
  "ok": true,
  "requestId": "req_124"
}
```

### 10.3 `rokid.capture_photo`

入参：

```json
{
  "quality": "medium"
}
```

返回：

```json
{
  "ok": true,
  "requestId": "req_125",
  "imageId": "img_001",
  "downloadUrl": "https://relay.example.com/images/img_001"
}
```

## 11. 关键时序

### 11.1 显示文字

```text
AI -> MCP: display_text
MCP -> Relay: command(display_text)
Relay -> Phone: command(display_text)
Phone -> Glasses: command(display_text)
Glasses -> Phone: result(ok)
Phone -> Relay: result(ok)
Relay -> MCP: result(ok)
MCP -> AI: success
```

### 11.2 拍照

```text
AI -> MCP: capture_photo
MCP -> Relay: command(capture_photo)
Relay -> Phone: command(capture_photo)
Phone -> Glasses: command(capture_photo)
Glasses -> Phone: ack
Glasses -> Phone: image chunks + result
Phone -> Relay: upload image + result
Relay -> MCP: image metadata
MCP -> AI: imageId + url
```

### 11.3 初始化与首连

```text
User -> Phone: 选择眼镜设备并授权
Phone: setupState = INITIALIZED
Phone -> Glasses: 建立 Bluetooth Socket
Phone -> Relay: 建立 WebSocket
Phone -> Glasses: hello
Glasses -> Phone: hello_ack
Phone: state = READY
```

如果任一步失败：

- 配对/授权失败，回到 `UNINITIALIZED`
- 蓝牙连接失败，进入 `DISCONNECTED` 或 `ERROR`
- Relay 连接失败，不影响本地蓝牙状态，但应标记 uplink 不可用

## 12. MVP 实现建议

推荐按以下顺序推进：

### 阶段 1：打通端到端最小链路

- 眼镜端接收 `display_text`
- 手机端与眼镜端建立蓝牙连接
- 手机端与 Relay 建立 WebSocket 连接
- MCP 调用 `display_text` 成功

目标：先验证“命令下发”链路。

### 阶段 2：接入拍照能力

- 眼镜端实现 `capture_photo`
- 蓝牙分片回传图片到手机端
- 手机端将图片上传或转发到 Relay
- MCP 返回图片引用

目标：验证“较大结果回传”链路。

### 阶段 3：补齐状态与错误处理

- 实现手机端状态机
- 增加超时、错误码、日志
- 提供 `get_device_status`

目标：让系统具备基本可调试性。

### 阶段 4：再考虑增强项

- 增加前后台切换后的恢复策略
- 增加设备鉴权与访问控制
- 增加图片存储和回放

目标：在主链路稳定后再处理工程化问题。

## 13. 仓库规划建议

如果后续在同一仓库内开发，建议使用 monorepo：

```text
docs/
  mvp-technical-implementation.md

apps/
  glasses-android/
  phone-android/
  relay-server/

packages/
  protocol/
  mcp-server/
```

说明：

- `protocol`：统一消息定义、错误码、序列化协议与 TS 类型出口
- `mcp-server`：对 AI 暴露工具

## 14. 风险与注意事项

MVP 阶段最值得优先验证的风险：

- 眼镜端是否能稳定调用拍照能力
- 蓝牙 Socket 对图片传输是否足够稳定
- 手机端前台运行时是否能稳定桥接蓝牙与 WebSocket
- 显示文字的体验是否满足可读性需求

建议不要过早优化后台保活、离线消息、复杂鉴权，先验证主链路是否成立。

对于当前 MVP，更重要的是：

- 命令能否稳定送达眼镜端
- 拍照结果能否稳定回传到 MCP
- 手机端状态是否足够清晰，便于排查“未初始化”和“连接失败”这两类问题

## 15. 后续演进方向

当 MVP 跑通后，可按以下顺序演进：

1. 增加图片上传存储与访问控制
2. 增加设备认证与会话鉴权
3. 增加更丰富的眼镜显示协议
4. 接入 A2UI，自定义界面下发
5. 补充 OCR、视觉分析等 Python 服务

## 16. 当前结论

当前方案整体可行，且适合作为 MVP：

- 眼镜端负责能力执行
- 手机端负责桥接与状态管理
- Relay 负责路由
- MCP 负责工具抽象

推荐技术路线：

- Android 端：Kotlin
- Relay / MCP：Node.js + TypeScript
- 包管理器：bun

在你当前目标下，先把链路跑通，比过早追求后台保活或复杂架构更重要。
