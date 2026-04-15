# Rokid MCP

让 AI 通过 MCP 协议操控 Rokid 智能眼镜——查询设备状态、在眼镜上显示文字、用眼镜拍照。

## 架构

```
AI Client (Claude / GPT / Agent)
       ↓ MCP (stdio / HTTP)
  MCP Server ─── relay HTTP API
       ↓            ↓
       ↓       Relay Server ── device WebSocket
       ↓            ↓
       ↓       Phone App ── Bluetooth RFCOMM
       ↓            ↓
       ↓       Glasses App (capture / render)
```

- **MCP Server** — 对 AI 暴露标准 MCP 工具，内部通过 HTTP 调用 Relay
- **Relay Server** — 管理设备会话、路由命令、暂存图片
- **Phone App** — 通过蓝牙桥接 Relay 与 Glasses
- **Glasses App** — 执行拍照、显示文字

## MCP 工具

| 工具 | 说明 |
|------|------|
| `rokid.get_device_status` | 查询设备当前状态（READY / BUSY / OFFLINE 等） |
| `rokid.display_text` | 在眼镜上显示文字，指定持续时长 |
| `rokid.capture_photo` | 用眼镜拍照，返回 base64 编码的 JPEG 图片 |

配合支持 cron / heartbeat 的 Agent 框架（如 [Hermes Agent](https://github.com/NousResearch/hermes-agent)），可以让 AI 按时间表主动轮询设备状态或拍照，实现从被动响应到主动感知的跃迁。

## 仓库结构

```
rokid-mcp/
├── apps/
│   ├── android/           # Gradle workspace：phone-app、glasses-app、share
│   └── relay-server/      # Bun / Elysia relay server
├── packages/
│   ├── mcp-server/        # MCP tool wrapper
│   ├── protocol/          # TypeBox schemas & shared TS protocol DTOs
│   └── shared-types/      # 基础共享类型（将迁移至 protocol）
├── specs/                 # 协议真源：JSON Schema / OpenAPI / AsyncAPI
└── package.json           # Bun workspace root
```

## 技术栈

| 层 | 技术 |
|----|------|
| Glasses / Phone | Kotlin, Compose, Bluetooth Classic RFCOMM |
| Relay Server | Bun, Elysia, TypeScript, WebSocket |
| MCP Server | TypeScript, @modelcontextprotocol/sdk, Zod |
| Protocol | TypeBox (TS), JSON Schema / OpenAPI / AsyncAPI (specs) |
| 包管理 | Bun (TS workspace), Gradle (Android) |

## 快速开始

### 前置条件

- [Bun](https://bun.sh) >= 1.3
- Node.js >= 18（用于 MCP stdio 传输）
- Android SDK + JDK 17+（构建 Android 应用）

### 安装依赖

```bash
bun install --no-cache
```

### 启动 Relay 开发服务器

```bash
bun run dev:relay
```

默认监听 `http://0.0.0.0:3000`，可通过 `HOST` / `PORT` 环境变量覆盖。

### 配置 MCP Server

MCP Server 通过 stdio 与 AI 客户端通信，需要以下环境变量：

| 变量 | 必填 | 说明 |
|------|------|------|
| `RELAY_BASE_URL` | 是 | Relay Server 地址，如 `http://127.0.0.1:3000` |
| `RELAY_HTTP_AUTH_TOKEN` | 是 | Relay HTTP 认证 token |
| `ROKID_DEFAULT_DEVICE_ID` | 是 | 默认操作的设备 ID |
| `MCP_COMMAND_TIMEOUT_MS` | 否 | 命令超时（默认 90000） |
| `MCP_COMMAND_POLL_INTERVAL_MS` | 否 | 命令轮询间隔（默认 1000） |

在 Claude Desktop 等客户端中注册：

```json
{
  "mcpServers": {
    "rokid": {
      "command": "node",
      "args": ["./packages/mcp-server/dist/cli.js"],
      "env": {
        "RELAY_BASE_URL": "http://127.0.0.1:3000",
        "RELAY_HTTP_AUTH_TOKEN": "your-token",
        "ROKID_DEFAULT_DEVICE_ID": "your-device-id"
      }
    }
  }
}
```

### 构建 Android 应用

```bash
apps/android/gradlew -p apps/android :phone-app:assembleDebug :glasses-app:assembleDebug
```

## Relay Server 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `PORT` | 否 | 3000 | 监听端口 |
| `HOST` | 否 | 0.0.0.0 | 监听地址 |
| `RELAY_HTTP_AUTH_TOKENS` | 是 | — | HTTP 认证 token 列表（逗号分隔） |
| `RELAY_WS_AUTH_TOKENS` | 是 | — | WebSocket 认证 token 列表（逗号分隔） |
| `RELAY_HEARTBEAT_INTERVAL_MS` | 否 | 5000 | 心跳间隔 |
| `RELAY_HEARTBEAT_TIMEOUT_MS` | 否 | 15000 | 心跳超时 |
| `RELAY_WS_HELLO_TIMEOUT_MS` | 否 | 5000 | WebSocket 握手超时 |

## 协议概览

项目定义了两层协议：

**Relay 协议**（Phone ↔ Relay Server）
- HTTP REST：设备状态查询、命令提交与轮询、图片上传/下载
- WebSocket：设备连接、双向命令流、心跳保活、状态推送
- 认证：`Authorization: Bearer <token>`

**本地链路协议**（Phone ↔ Glasses）
- 传输：Bluetooth Classic RFCOMM，自定义二进制分帧
- 帧格式：`[magic][headerLength][bodyLength][headerCrc32][headerJson][bodyBytes]`
- 控制消息用 JSON header，图片数据用原始二进制 body + chunk 分片

所有协议的真源定义在 `specs/` 目录下：JSON Schema 定义数据模型，OpenAPI 定义 REST 接口，AsyncAPI 定义 WS 消息，transport spec 定义本地链路分帧规则。`packages/protocol` 中的 TypeScript 类型和 `apps/android/share` 中的 Kotlin 模型均从 specs 派生。

## 设备状态

设备通过双层状态机管理生命周期：

```
setupState:  DISCONNECTED → CONNECTING → CONNECTED
runtimeState: IDLE → READY → BUSY → IDLE
```

- `DISCONNECTED` — 设备未连接
- `CONNECTED` + `IDLE` — 已连接，等待就绪
- `READY` — 空闲可用，可接受命令
- `BUSY` — 正在执行命令

## 交互时序

### display_text

```
AI → MCP Server → Relay → Phone → Glasses (显示文字)
                                    ← Glasses (完成)
AI ← MCP Server ← Relay ← Phone
```

### capture_photo

```
AI → MCP Server → Relay → Phone → Glasses (拍照)
                                    ← Glasses (图片分片)
AI ← MCP Server ← Relay ← Phone (base64 JPEG)
```

## 开发

```bash
# 类型检查
bun run typecheck

# 构建
bun run build

# TS 测试
bun test apps/relay-server/src packages/protocol/src packages/mcp-server/src

# Android 测试
apps/android/gradlew -p apps/android :share:test

# 协议生成（lint → bundle → generate TS → generate Kotlin）
bun run spec:generate
```

## 贡献

- 分支命名：`feature/feature-name`
- Commit 格式：`feat:` / `fix:` / `docs:` / `style:` / `refactor:` / `test:` / `chore:`
- 协议变更必须先改 `specs/`，再运行生成流程同步到 TS / Kotlin
- 公共 API、状态拥有者、协议边界处请添加 doc comment

## 许可证

本项目采用 [GNU General Public License v3.0](./LICENSE)（`GPL-3.0-only`）。
