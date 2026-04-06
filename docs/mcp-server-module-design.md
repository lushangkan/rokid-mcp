# MCP Server Module Design

## 1. 目标

本文档定义 `packages/mcp-server/` 在 MVP 阶段的模块边界、职责分工、错误收口与日志要求。

MCP 在本项目中的定位是：

- 对 AI 暴露 tool
- 对 Relay 消费 HTTP DTO
- 对 `capture_photo` 承担图片下载、校验、base64 转换与本地缓存清理职责

## 2. 依赖文档

- `docs/mcp-interface-schemas.md`
- `docs/mcp-interface-sequences.md`
- `docs/relay-protocol-schemas.md`

## 3. 模块划分原则

本次设计遵循以下原则：

- Relay DTO 消费层与 MCP tool 输出层分离
- 轮询逻辑单独抽模块，不散落在 tool 文件中
- 图片下载、图片解析、本地缓存清理分成独立职责
- 最终 tool failure 必须单点收口
- 日志必须覆盖图片下载失败、解析失败两类关键错误

## 4. 顶层目录建议

建议 `packages/mcp-server/src/` 按如下方式组织：

```text
src/
  index.ts
  server.ts
  config/
    env.ts
  lib/
    errors.ts
    logger.ts
    sleep.ts
    checksum.ts
  relay/
    relay-client.ts
    relay-response-validator.ts
  command/
    command-poller.ts
  image/
    image-downloader.ts
    image-parser.ts
    image-temp-file-store.ts
  tools/
    get-device-status.ts
    display-text.ts
    capture-photo.ts
  mapper/
    result-mapper.ts
```

## 5. 模块总览

```text
server.ts
  -> env.ts
  -> relay-client.ts
  -> command-poller.ts
  -> result-mapper.ts
  -> get-device-status tool
  -> display-text tool
  -> capture-photo tool

capture-photo tool
  -> command-poller.ts
  -> image-downloader.ts
  -> image-parser.ts
  -> image-temp-file-store.ts
  -> result-mapper.ts
```

## 6. 模块职责

## 6.1 `config/env.ts`

职责：

- 读取环境变量
- 做启动期校验
- 输出强类型配置对象

推荐接口：

```ts
type McpEnv = {
  relayBaseUrl: string
  defaultDeviceId: string
  requestTimeoutMs: number
  commandPollIntervalMs: number
  commandTimeoutMs: number
  imageDownloadTimeoutMs: number
  relayApiToken?: string
}
```

错误：

- 配置不合法时抛 `MCP_CONFIG_INVALID`

## 6.2 `server.ts`

职责：

- 初始化 MCP stdio server
- 注册三个 tools
- 装配所有模块依赖

不负责：

- 不处理具体业务逻辑
- 不直接拼 Relay URL
- 不直接处理图片 bytes

## 6.3 `relay/relay-client.ts`

职责：

- 封装所有 Relay HTTP 请求
- 统一处理 headers、超时、HTTP 错误、JSON 解码

推荐接口：

```ts
interface RelayClient {
  getDeviceStatus(deviceId: string): Promise<GetDeviceStatusResponse>
  submitDisplayText(input: SubmitDisplayTextCommandRequest): Promise<SubmitDisplayTextCommandResponse>
  submitCapturePhoto(input: SubmitCapturePhotoCommandRequest): Promise<SubmitCapturePhotoCommandResponse>
  getCommandStatus(requestId: string): Promise<GetCommandStatusResponse>
  getImage(imageId: string): Promise<Response>
}
```

关键约束：

- `getDeviceStatus`、`submitDisplayText`、`submitCapturePhoto`、`getCommandStatus` 的返回值必须严格对应 Relay 文档
- 这里不做 AI 友好映射
- 这里也不做命令终态判断

## 6.4 `relay/relay-response-validator.ts`

职责：

- 对 Relay JSON 响应做 runtime validation
- 保证 `relay-client` 交给上层的数据一定符合 Relay schema

错误：

- 校验失败时抛 `MCP_RELAY_RESPONSE_INVALID`

## 6.5 `command/command-poller.ts`

职责：

- 轮询 `GET /api/v1/commands/:requestId`
- 判断是否进入终态
- 处理 MCP 本地等待超时

推荐接口：

```ts
type WaitForTerminalStateResult = {
  requestId: string
  response: GetCommandStatusResponse
}

interface CommandPoller {
  waitForTerminalState(requestId: string): Promise<WaitForTerminalStateResult>
}
```

不负责：

- 不直接映射 tool 输出
- 不直接下载图片

## 6.6 `image/image-downloader.ts`

职责：

- 下载 `GET /api/v1/images/:imageId`
- 校验 HTTP status、Content-Type、body、size
- 返回原始 `Uint8Array` 或 `Buffer`

推荐接口：

```ts
type DownloadedImage = {
  imageId: string
  mimeType: "image/jpeg"
  bytes: Uint8Array
  size: number
}

interface ImageDownloader {
  download(imageId: string, expectedSize?: number): Promise<DownloadedImage>
}
```

错误：

- 下载失败统一抛 `MCP_IMAGE_DOWNLOAD_FAILED`

## 6.7 `image/image-parser.ts`

职责：

- 对下载后的图片 bytes 做进一步校验
- 可选校验 SHA-256
- 转成 base64

推荐接口：

```ts
type ParsedImage = {
  imageId: string
  mimeType: "image/jpeg"
  size: number
  dataBase64: string
}

interface ImageParser {
  parseToBase64(input: {
    imageId: string
    mimeType: "image/jpeg"
    bytes: Uint8Array
    expectedSha256?: string
  }): Promise<ParsedImage>
}
```

错误：

- 解析失败统一抛 `MCP_IMAGE_PARSE_FAILED`

## 6.8 `image/image-temp-file-store.ts`

职责：

- 如果实现中需要落本地临时文件，则负责创建和删除临时文件
- 推荐实现仍然是直接在内存中处理图片，不依赖这个模块

推荐接口：

```ts
interface ImageTempFileStore {
  createTempFile(imageId: string): Promise<string>
  deleteTempFile(filePath: string): Promise<void>
}
```

错误：

- 本地临时文件清理失败时记录日志；MVP 不单独冻结新的协议错误码

## 6.9 `mapper/result-mapper.ts`

职责：

- 把最终有效的 Relay DTO 映射成 MCP tool output
- 把 Relay 错误和 MCP 适配层错误映射成统一异常对象

推荐接口：

```ts
interface ResultMapper {
  toGetDeviceStatusOutput(response: GetDeviceStatusResponse): RokidGetDeviceStatusOutput
  toDisplayTextOutput(response: GetCommandStatusResponse): RokidDisplayTextOutput
  toCapturePhotoOutput(input: {
    response: GetCommandStatusResponse
    imageBase64: string
  }): RokidCapturePhotoOutput
}
```

## 6.10 `tools/get-device-status.ts`

职责：

- 调用 `relay-client.getDeviceStatus()`
- 调用 `result-mapper.toGetDeviceStatusOutput()`

## 6.11 `tools/display-text.ts`

职责：

- 校验 tool 输入
- 提交 `display_text` 命令
- 调 `command-poller`
- 对 `COMPLETED` 结果做硬校验
- 调 `result-mapper`

## 6.12 `tools/capture-photo.ts`

职责：

- 校验 tool 输入
- 提交 `capture_photo` 命令
- 调 `command-poller`
- 对最终 `GetCommandStatusResponse` 做硬校验
- 调 `image-downloader`
- 调 `image-parser`
- 如果存在本地临时文件，则负责清理
- 调 `result-mapper`

它是 MCP 侧图片命令的最终收口 owner。

## 7. 单点错误收口

MCP 侧固定以下收口规则：

- `get-device-status.ts` 是 `rokid.get_device_status` 的收口 owner
- `display-text.ts` 是 `rokid.display_text` 的收口 owner
- `capture-photo.ts` 是 `rokid.capture_photo` 的收口 owner

其他模块只负责：

- 做本模块自己的校验
- 抛出 typed error
- 不直接决定最终 tool success 或 tool failure

## 8. `capture_photo` 的执行编排

推荐伪代码：

```ts
submit capture_photo command
  -> waitForTerminalState(requestId)
  -> validate completed command response
  -> download image bytes
  -> validate image bytes
  -> parse to base64
  -> delete local temp file if one exists
  -> map final output
  -> return success
```

失败时：

```text
any step failed
  -> log typed error
  -> stop pipeline
  -> return tool failure
```

## 9. 校验边界

为了避免职责混乱，固定以下边界：

### 9.1 `relay-client` 校验什么

- HTTP 是否可达
- JSON 是否可解析
- JSON 是否符合 Relay DTO schema

### 9.2 `capture-photo` tool 校验什么

- 最终 `command.status` 是否允许继续下载图片
- `command.image` 与 `command.result` 是否一致

### 9.3 `image-downloader` 校验什么

- 图片下载状态码
- Content-Type
- body 非空
- size 是否匹配

### 9.4 `image-parser` 校验什么

- bytes 是否能正常处理
- SHA-256 是否匹配
- base64 是否成功生成

### 9.5 `image-temp-file-store` 校验什么

- 临时文件是否成功创建
- 临时文件是否成功删除

## 10. 日志设计

建议日志接口至少支持：

- `info`
- `warn`
- `error`

## 10.1 必打 `info`

- MCP 启动成功
- tool 调用开始
- Relay 提交命令成功
- 命令轮询完成
- 如果使用本地临时文件，记录本地清理成功

## 10.2 必打 `error`

- `MCP_RELAY_REQUEST_FAILED`
- `MCP_RELAY_RESPONSE_INVALID`
- `MCP_COMMAND_WAIT_TIMEOUT`
- `MCP_IMAGE_DOWNLOAD_FAILED`
- `MCP_IMAGE_PARSE_FAILED`

## 10.3 `capture_photo` 错误日志字段

至少包括：

- `toolName = rokid.capture_photo`
- `deviceId`
- `requestId`
- `imageId`
- `errorCode`
- `message`
- `expectedSize`
- `actualSize`
- `mimeType`

## 11. 模块间不变量

1. `relay-client` 返回的控制接口 DTO 必须与 Relay 文档一致
2. `capture_photo` 不允许直接把 Relay `url` 返回给 AI
3. `capture_photo` 只有在图片下载、解析成功后才算 tool success
4. 如果实现使用了本地临时文件，则返回前必须完成本地清理
5. 图片下载失败必须打印 `MCP_IMAGE_DOWNLOAD_FAILED`
6. 图片解析失败必须打印 `MCP_IMAGE_PARSE_FAILED`
7. 最终 tool failure 只能由 tool handler 收口

## 12. 测试建议

## 12.1 `relay-client`

- 正常解析 `GetDeviceStatusResponse`
- 正常解析 `SubmitDisplayTextCommandResponse`
- 正常解析 `SubmitCapturePhotoCommandResponse`
- `GetCommandStatusResponse` 字段非法时抛 `MCP_RELAY_RESPONSE_INVALID`

## 12.2 `command-poller`

- `COMPLETED` 时停止轮询
- `FAILED` 时停止轮询并返回终态
- `TIMEOUT` 时停止轮询并返回终态
- 超过 `MCP_COMMAND_TIMEOUT_MS` 时抛 `MCP_COMMAND_WAIT_TIMEOUT`

## 12.3 `image-downloader`

- 成功下载 JPEG
- HTTP 404 时抛 `MCP_IMAGE_DOWNLOAD_FAILED`
- `Content-Type` 非 `image/jpeg` 时抛 `MCP_IMAGE_DOWNLOAD_FAILED`
- 空 body 时抛 `MCP_IMAGE_DOWNLOAD_FAILED`
- size 不匹配时抛 `MCP_IMAGE_DOWNLOAD_FAILED`

## 12.4 `image-parser`

- bytes 成功转 base64
- SHA-256 不匹配时抛 `MCP_IMAGE_PARSE_FAILED`
- base64 为空时抛 `MCP_IMAGE_PARSE_FAILED`

## 12.5 `capture-photo` tool

- 主成功链路：提交 -> 轮询 -> 下载 -> base64 -> 成功返回
- 图片下载失败链路
- 图片解析失败链路
- 如果使用临时文件，则覆盖本地清理链路

## 13. MVP 实现顺序建议

### 阶段 1

- `env.ts`
- `relay-client.ts`
- `relay-response-validator.ts`
- `get-device-status.ts`

目标：先验证 MCP 能稳定消费 Relay JSON。

### 阶段 2

- `command-poller.ts`
- `display-text.ts`
- `result-mapper.ts`

目标：先打通 `display_text` 最小闭环。

### 阶段 3

- `image-downloader.ts`
- `image-parser.ts`
- `image-temp-file-store.ts`（仅在需要落盘时实现）
- `capture-photo.ts`

目标：打通“下载图片 -> base64 -> 返回”的完整闭环，并处理可选的本地缓存清理。

### 阶段 4

- 补日志
- 补错误测试
- 补删除失败场景测试

目标：让 MCP 层具备基本可调试性。

## 14. 当前结论

MVP 阶段最合适的 MCP 结构是：

- Relay DTO 消费层保持与 Relay 文档一致
- tool handler 负责最终收口
- `capture_photo` 额外引入下载、解析两个核心模块，以及可选的本地临时文件模块
- 图片下载失败、解析失败都必须有独立错误码和日志

这套结构对实现最友好，因为每个模块只做一件事，Node.js 代码也不会变成一个大而杂的文件。
