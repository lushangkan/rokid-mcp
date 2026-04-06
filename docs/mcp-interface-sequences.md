# MCP Interface Sequences

## 1. 目标

本文档定义 `packages/mcp-server/` 在 MVP 阶段的详细执行时序，重点覆盖：

- `rokid.get_device_status`
- `rokid.display_text`
- `rokid.capture_photo`
- `capture_photo` 的图片下载、base64 转换与本地缓存清理

## 2. 依赖文档

- `docs/mcp-interface-schemas.md`
- `docs/relay-protocol-schemas.md`
- `docs/relay-protocol-sequences.md`

## 3. 通用前提

- MCP 通过环境变量拿到 `RELAY_BASE_URL` 与 `ROKID_DEFAULT_DEVICE_ID`
- Relay 已经实现 `POST /api/v1/commands`、`GET /api/v1/commands/:requestId`、`GET /api/v1/images/:imageId`
- MCP 所有 Relay JSON 响应都先做 schema 校验，再进入业务逻辑
- MCP 下载图片优先直接进内存；只有在实现细节要求时才允许使用本地临时文件

## 4. `rokid.get_device_status`

### 4.1 主成功链路

```text
AI -> MCP: rokid.get_device_status()
MCP: read defaultDeviceId from env
MCP -> Relay: GET /api/v1/devices/:deviceId/status
Relay -> MCP: GetDeviceStatusResponse
MCP: validate response schema
MCP -> AI: return status result
```

### 4.2 失败分支

| 场景 | 触发点 | MCP 错误 |
| - | - | - |
| 环境变量缺失 | 读取配置 | `MCP_CONFIG_INVALID` |
| Relay 请求失败 | HTTP 请求阶段 | `MCP_RELAY_REQUEST_FAILED` |
| Relay JSON 非法 | 响应解析阶段 | `MCP_RELAY_RESPONSE_INVALID` |
| Relay 返回业务错误 | HTTP JSON 错误响应 | 直接透传 `error.code` |

## 5. `rokid.display_text`

### 5.1 主成功链路

```text
AI -> MCP: rokid.display_text(text, durationMs)
MCP: validate input
MCP -> Relay: POST /api/v1/commands (display_text)
Relay -> MCP: SubmitDisplayTextCommandResponse
MCP: validate response schema and requestId
loop:
  MCP -> Relay: GET /api/v1/commands/:requestId
  Relay -> MCP: GetCommandStatusResponse
  MCP: validate response schema
until command.status is terminal
MCP: validate completed display_text result
MCP -> AI: return display_text tool result
```

### 5.2 终态处理规则

| Relay 终态 | MCP 行为 |
| - | - |
| `COMPLETED` | 校验 `result.action=display_text` 后返回成功 |
| `FAILED` | 透传 Relay `error.code` |
| `TIMEOUT` | 透传 `COMMAND_EXECUTION_TIMEOUT` 或对应终态错误 |

### 5.3 失败分支

| 场景 | 触发点 | MCP 错误 |
| - | - | - |
| 输入非法 | tool 入参校验 | 直接返回参数错误 |
| Relay 提交失败 | `POST /commands` | `MCP_RELAY_REQUEST_FAILED` 或 Relay 业务错误 |
| 轮询超时 | 超过 `MCP_COMMAND_TIMEOUT_MS` | `MCP_COMMAND_WAIT_TIMEOUT` |
| 最终结果结构不合法 | `COMPLETED` 后硬校验失败 | `MCP_RELAY_RESPONSE_INVALID` |

## 6. `rokid.capture_photo`

## 6.1 主成功链路

```text
AI -> MCP: rokid.capture_photo(quality?)
MCP: validate input and fill default quality=medium
MCP -> Relay: POST /api/v1/commands (capture_photo)
Relay -> MCP: SubmitCapturePhotoCommandResponse
MCP: validate response schema and requestId
loop:
  MCP -> Relay: GET /api/v1/commands/:requestId
  Relay -> MCP: GetCommandStatusResponse
  MCP: validate response schema
until command.status is terminal
MCP: validate completed capture_photo result
MCP -> Relay: GET /api/v1/images/:imageId
Relay -> MCP: image/jpeg bytes
MCP: validate bytes, content-type, size, sha256
MCP: convert bytes to base64
MCP: delete local temp file if one exists
MCP -> AI: return base64 image result
```

## 6.2 为什么不能先把 URL 返回给 AI

- AI 不应该再自己决定什么时候下载图片
- MCP 必须自己完成图片硬校验
- 如果把 URL 提前暴露给 AI，MCP 就无法保证“下载成功 + 解析成功”这一整段链路
- 这会让安全边界和清理边界变得模糊

## 6.3 `capture_photo` 的阶段拆分

### 阶段 A：命令提交与轮询

```text
POST /api/v1/commands
  -> validate SubmitCapturePhotoCommandResponse
  -> poll GetCommandStatusResponse
  -> wait until COMPLETED
```

阶段 A 成功条件：

- `command.status = COMPLETED`
- `command.result.action = capture_photo`
- `command.image.status = UPLOADED`
- `command.result.imageId = command.image.imageId`

### 阶段 B：图片下载与解析

```text
GET /api/v1/images/:imageId
  -> validate status code
  -> validate content-type
  -> validate body bytes
  -> validate size and sha256
  -> convert to base64
```

阶段 B 成功条件：

- 图片下载成功
- 图片校验成功
- base64 转换成功

### 阶段 C：本地缓存清理

```text
delete local temp file if one exists
  -> cleanup success
  -> only then return tool success
```

阶段 C 成功条件：

- 如果采用内存缓冲下载，则没有本地文件需要清理
- 如果采用本地临时文件，则临时文件已经被删除

## 6.4 `capture_photo` 失败分支

| 场景 | 触发点 | MCP 错误 |
| - | - | - |
| 提交命令失败 | `POST /commands` | Relay 错误或 `MCP_RELAY_REQUEST_FAILED` |
| 轮询超时 | 超过 `MCP_COMMAND_TIMEOUT_MS` | `MCP_COMMAND_WAIT_TIMEOUT` |
| 命令终态是 `FAILED` | Relay 最终收口失败 | 透传 Relay `error.code` |
| 命令终态是 `TIMEOUT` | Relay 执行超时 | `COMMAND_EXECUTION_TIMEOUT` |
| `COMPLETED` 但 result/image 字段不一致 | 终态硬校验 | `MCP_RELAY_RESPONSE_INVALID` |
| 图片下载失败 | `GET /images/:imageId` 失败 | `MCP_IMAGE_DOWNLOAD_FAILED` |
| 图片 Content-Type 非法 | 图片下载后校验 | `MCP_IMAGE_DOWNLOAD_FAILED` |
| 图片大小不匹配 | 图片下载后校验 | `MCP_IMAGE_DOWNLOAD_FAILED` |
| 图片哈希不匹配 | 图片解析后校验 | `MCP_IMAGE_PARSE_FAILED` |
| base64 转换失败 | 图片解析阶段 | `MCP_IMAGE_PARSE_FAILED` |

## 6.5 图片下载失败后的处理

```text
GET image failed or invalid
  -> log MCP_IMAGE_DOWNLOAD_FAILED
  -> do not return URL
  -> do not return partial bytes
  -> command treated as tool failure
```

## 6.6 图片解析失败后的处理

```text
download image bytes success
  -> base64 conversion or checksum validation failed
  -> log MCP_IMAGE_PARSE_FAILED
  -> do not return partial result
  -> tool failure
```

## 6.7 本地缓存清理说明

- 推荐实现是直接下载到内存，因此通常不会产生本地文件
- 如果实现中使用了本地临时文件，则应在返回前立即删除
- 本地清理属于实现细节，不再要求 MCP 调 Relay 删除图片资源

## 7. 统一轮询策略

MCP 对两个命令 tool 都使用统一轮询策略：

```text
submit command
  -> get requestId
  -> poll every MCP_COMMAND_POLL_INTERVAL_MS
  -> if terminal: stop polling
  -> if exceeded MCP_COMMAND_TIMEOUT_MS: fail
```

终止条件：

- `COMPLETED`
- `FAILED`
- `TIMEOUT`
- MCP 本地等待超时

## 8. 日志时序要求

`capture_photo` 至少要按以下点打日志：

1. 命令提交成功，记录 `requestId`
2. 轮询拿到 `COMPLETED`
3. 开始下载图片，记录 `imageId`
4. 图片下载成功，记录 `size`
5. 图片转 base64 成功
6. 如果使用本地临时文件，记录本地清理结果

以下错误必须明确打印：

- `MCP_IMAGE_DOWNLOAD_FAILED`
- `MCP_IMAGE_PARSE_FAILED`

## 9. 当前结论

MCP 的 `capture_photo` 成功定义不再是“拿到 URL”，而是：

1. Relay 命令成功收口
2. MCP 成功下载图片
3. MCP 成功校验并转成 base64
4. 如果存在本地临时文件，则 MCP 已完成清理
5. 最终把 base64 返回给 AI

## 10. 下一步

建议配套维护：

1. `docs/mcp-server-module-design.md`
