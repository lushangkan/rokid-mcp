# MCP Interface Schemas

## 1. 目标

本文档定义 `packages/mcp-server/` 在 MVP 阶段对 AI 暴露的 tool schema、环境变量约定、Relay 依赖接口，以及图片下载与 base64 返回的硬校验规则。

本文档只描述 MCP 这一层的接口与约束，不重复定义 Relay 的 HTTP DTO。凡是已经在 Relay 文档中冻结的 HTTP 请求与响应结构，MCP 必须原样消费。

## 2. 依赖文档

- `docs/mvp-technical-implementation.md`
- `docs/relay-protocol-constants.md`
- `docs/relay-protocol-schemas.md`
- `docs/relay-protocol-sequences.md`

如果本文与 `docs/relay-protocol-schemas.md` 冲突，以 Relay 文档中的 HTTP schema 为准。

## 3. 设计范围

MVP 阶段 MCP 的范围固定为：

- 单设备默认绑定
- 所有配置从环境变量读取
- MCP 只通过 HTTP 调 Relay
- `display_text` 与 `capture_photo` 都走“提交命令 + 轮询状态”
- `capture_photo` 成功后，MCP 立即下载图片、做硬校验、转 base64 返回给 AI
- 图片下载优先直接进内存，不落本地文件；如果实现中使用本地临时文件，则在转 base64 后立即删除

MVP 不做：

- 多设备选择
- MCP 侧图片长期存储
- 图片 URL 直接返回给 AI 作为默认结果
- MCP 侧自定义命令状态机
- MCP 侧数据库或持久化任务表

## 4. 总体原则

1. MCP 对 Relay 的控制接口返回值必须与 `docs/relay-protocol-schemas.md` 保持一致
2. MCP tool 的最终输出可以是 AI 友好的映射结果，但映射前必须先完成 Relay DTO 硬校验
3. `capture_photo` 不允许在命令刚完成时直接把 Relay `url` 返回给 AI
4. `capture_photo` 必须先下载二进制图片并完成校验，再转 base64 输出
5. 图片下载优先使用内存缓冲；若落本地临时文件，必须在消费完成后立即清理

## 5. 环境变量

| 变量名 | 必填 | 默认值 | 说明 |
| - | - | - | - |
| `RELAY_BASE_URL` | 是 | 无 | Relay 服务基础地址，例如 `http://127.0.0.1:3000` |
| `ROKID_DEFAULT_DEVICE_ID` | 是 | 无 | MCP 默认操作设备 ID |
| `MCP_REQUEST_TIMEOUT_MS` | 否 | `10000` | 单次 Relay HTTP 请求超时 |
| `MCP_COMMAND_POLL_INTERVAL_MS` | 否 | `1000` | 轮询 `GET /commands/:requestId` 的间隔 |
| `MCP_COMMAND_TIMEOUT_MS` | 否 | `90000` | MCP 等待命令终态的最长时间 |
| `MCP_IMAGE_DOWNLOAD_TIMEOUT_MS` | 否 | `30000` | 图片下载超时 |
| `RELAY_API_TOKEN` | 否 | 无 | 若 Relay 控制接口启用鉴权，则通过该变量注入 |

## 6. MCP 依赖的 Relay 接口

本节不重新发明 schema，而是固定 MCP 依赖的 Relay DTO 名称与语义。

## 6.1 已冻结的 Relay HTTP DTO

MCP 必须按 `docs/relay-protocol-schemas.md` 直接消费以下类型：

- `GetDeviceStatusResponse`
- `SubmitDisplayTextCommandRequest`
- `SubmitDisplayTextCommandResponse`
- `SubmitCapturePhotoCommandRequest`
- `SubmitCapturePhotoCommandResponse`
- `GetCommandStatusResponse`
- `ErrorResponse`

对应接口：

- `GET /api/v1/devices/:deviceId/status`
- `POST /api/v1/commands`
- `GET /api/v1/commands/:requestId`
- `GET /api/v1/images/:imageId`

约束：

- `relay-client` 不得把这些 DTO 擅自拍平或改字段名
- `relay-client` 返回的对象结构必须与 Relay schema 完全一致
- MCP tool 层如果要返回更适合 AI 的结构，必须在 `result-mapper` 中单独映射

## 6.2 本地缓存清理约束

MCP 不负责删除 Relay 图片资源。

这里的“删除图片”只指 MCP 下载过程中可能产生的本地缓存文件：

- 如果图片直接下载到内存缓冲区，则无需额外删除本地文件
- 如果实现中为了兼容某些库而先写入本地临时文件，则必须在 base64 转换完成后立即删除

## 7. MCP tools

MVP 暴露三个 tools。

## 7.1 `rokid.get_device_status`

用途：查询默认设备当前状态。

输入 schema：

```ts
type RokidGetDeviceStatusInput = {}
```

内部依赖：

- `GET /api/v1/devices/:deviceId/status`
- 返回值必须先通过 `GetDeviceStatusResponse` 校验

输出 schema：

```ts
type RokidGetDeviceStatusOutput = {
  ok: true
  device: {
    deviceId: string
    connected: boolean
    sessionState: "OFFLINE" | "ONLINE" | "STALE" | "CLOSED"
    setupState: "UNINITIALIZED" | "INITIALIZED"
    runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR"
    uplinkState: "OFFLINE" | "CONNECTING" | "ONLINE" | "ERROR"
    capabilities: Array<"display_text" | "capture_photo">
    activeCommandRequestId?: string | null
    lastErrorCode?: string | null
    lastErrorMessage?: string | null
    lastSeenAt?: number | null
    sessionId?: string | null
  }
  timestamp: number
}
```

说明：

- 这里直接沿用 Relay `GetDeviceStatusResponse`
- 不额外做结构改写，保持最简单的一致性

## 7.2 `rokid.display_text`

用途：显示文本并等待命令终态。

输入 schema：

```ts
type RokidDisplayTextInput = {
  text: string
  durationMs: number
}
```

输入校验：

- `text` 去除首尾空白后不能为空
- `text.length` 建议限制在 `1..500`
- `durationMs` 必须为正整数
- `durationMs` 建议限制在 `1..60000`

内部依赖：

1. `POST /api/v1/commands`，请求体必须符合 `SubmitDisplayTextCommandRequest`
2. `GET /api/v1/commands/:requestId`，响应必须符合 `GetCommandStatusResponse`

输出 schema：

```ts
type RokidDisplayTextOutput = {
  ok: true
  requestId: string
  action: "display_text"
  completedAt: number
  result: {
    action: "display_text"
    displayed: true
    durationMs: number
  }
}
```

硬校验规则：

- 轮询终态必须是 `command.status = COMPLETED`
- `command.action` 必须等于 `display_text`
- `command.result` 必须存在
- `command.result.action` 必须等于 `display_text`
- `command.error` 必须为空
- `command.completedAt` 必须存在

## 7.3 `rokid.capture_photo`

用途：拍照、等待命令成功、下载图片、转 base64 返回给 AI。

输入 schema：

```ts
type RokidCapturePhotoInput = {
  quality?: "low" | "medium" | "high"
}
```

输入校验：

- `quality` 可选
- 若缺失，默认补为 `medium`

内部依赖：

1. `POST /api/v1/commands`，请求体必须符合 `SubmitCapturePhotoCommandRequest`
2. `GET /api/v1/commands/:requestId`，响应必须符合 `GetCommandStatusResponse`
3. `GET /api/v1/images/:imageId`，下载图片二进制

输出 schema：

```ts
type RokidCapturePhotoOutput = {
  ok: true
  requestId: string
  action: "capture_photo"
  completedAt: number
  image: {
    imageId: string
    mimeType: "image/jpeg"
    size: number
    width: number
    height: number
    sha256?: string
    dataBase64: string
  }
}
```

说明：

- 不向 AI 返回 `url`
- `imageId` 仍然保留，便于日志和链路追踪
- `dataBase64` 是最终交给 AI 的图片内容

## 8. `capture_photo` 的硬校验规则

`capture_photo` 必须分成三段校验：命令终态校验、图片下载校验、图片解析校验。

## 8.1 命令终态校验

在开始下载图片前，必须先校验最终 `GetCommandStatusResponse`：

- `command.status = COMPLETED`
- `command.action = capture_photo`
- `command.completedAt` 存在
- `command.result` 存在
- `command.result.action = capture_photo`
- `command.result.imageId` 存在
- `command.image` 存在
- `command.image.status = UPLOADED`
- `command.image.imageId = command.result.imageId`

若任一条件不满足，必须直接失败，不允许开始图片下载。

## 8.2 图片下载校验

下载 `GET /api/v1/images/:imageId` 后，必须至少校验：

- HTTP status 为 `200`
- `Content-Type = image/jpeg`
- Body 非空
- 若有 `Content-Length`，必须大于 `0`
- 若 `command.result.size` 存在，下载字节长度必须与之匹配

## 8.3 图片解析校验

下载成功后，必须继续校验：

- 能成功读取二进制缓冲区
- 能成功转成 base64 字符串
- base64 结果非空
- 若 `command.result.sha256` 存在，建议对原始 bytes 再算一次 SHA-256 并比对

若下载成功但 base64 生成失败，也必须视为失败，不能返回半成品结果。

## 8.4 本地缓存清理时机

如果实现采用内存缓冲下载，则这一节不涉及额外删除动作。

如果实现采用本地临时文件，清理时机固定为：

```text
command completed
  -> download image bytes
  -> validate image bytes
  -> convert to base64
  -> delete local temp file if one exists
  -> return tool success
```

原则：

- 优先直接下载到内存，避免引入临时文件管理
- 如果确实落了本地临时文件，不允许先返回 AI 再异步删除

## 9. MCP 错误码

MCP 优先沿用 Relay 已有错误码；只对适配层新增少量错误码。

## 9.1 直接透传的 Relay 错误码

- `DEVICE_OFFLINE`
- `DEVICE_NOT_INITIALIZED`
- `DEVICE_BUSY`
- `COMMAND_ACK_TIMEOUT`
- `COMMAND_EXECUTION_TIMEOUT`
- `BLUETOOTH_DISCONNECTED`
- `CAMERA_UNAVAILABLE`
- `CAMERA_CAPTURE_FAILED`
- `DISPLAY_FAILED`
- `IMAGE_NOT_FOUND`
- `IMAGE_NOT_READY`
- `IMAGE_EXPIRED`
- `AUTH_FORBIDDEN_IMAGE_ACCESS`

## 9.2 MCP 新增错误码

| 错误码 | 含义 | 是否可重试 |
| - | - | - |
| `MCP_CONFIG_INVALID` | 环境变量缺失或非法 | 否 |
| `MCP_RELAY_REQUEST_FAILED` | MCP 调 Relay HTTP 失败 | 是 |
| `MCP_RELAY_RESPONSE_INVALID` | Relay JSON 不符合 schema | 否 |
| `MCP_COMMAND_WAIT_TIMEOUT` | MCP 自己等待命令超时 | 是 |
| `MCP_IMAGE_DOWNLOAD_FAILED` | 图片下载失败或下载结果不满足约束 | 是 |
| `MCP_IMAGE_PARSE_FAILED` | 图片 bytes 转 base64 或校验失败 | 否 |

说明：

- `MCP_IMAGE_DOWNLOAD_FAILED` 覆盖 HTTP status 非 200、Content-Type 非法、空 body、大小不一致等场景
- `MCP_IMAGE_PARSE_FAILED` 覆盖 SHA-256 不一致、buffer 转换失败、base64 结果为空等场景

## 10. 日志要求

MCP 至少记录以下日志：

- tool 调用开始
- Relay 提交命令成功，记录 `requestId`
- 轮询结束，记录终态和耗时
- 图片下载开始与结束
- 如果使用本地临时文件，记录本地清理开始与结果

以下错误必须按 `error` 级别打印：

- `MCP_IMAGE_DOWNLOAD_FAILED`
- `MCP_IMAGE_PARSE_FAILED`

日志字段建议至少包含：

- `toolName`
- `deviceId`
- `requestId`
- `imageId`（如果存在）
- `errorCode`
- `message`

## 11. 建议的类型落地方式

建议在 `packages/mcp-server/` 中做两层类型：

1. Relay DTO 层：严格对应 `docs/relay-protocol-schemas.md`
2. MCP Tool Output 层：面向 AI 的简化结果

这样可以同时满足：

- 对 Relay 保持一致
- 对 AI 保持好用

## 12. 下一步

在本 schema 文档确认后，下一步建议配套维护：

1. `docs/mcp-interface-sequences.md`
2. `docs/mcp-server-module-design.md`
