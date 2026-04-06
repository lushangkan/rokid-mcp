# 第一阶段 Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通第一阶段最小可信状态链路，让 `MCP -> Relay -> Phone -> Glasses` 至少能稳定返回可信设备状态，并满足 spec 冻结的 session、heartbeat、status 查询行为。

**Architecture:** 严格按 `docs/superpowers/specs/2026-03-30-heartbeat-phase1-design.md` 第 33 节顺序推进：先补 `packages/protocol` 的共享 schema 与类型，再实现 `apps/relay-server` 的 `route -> manager -> singleton stores`，然后实现 Android 本地握手与 Phone 上行 heartbeat，最后实现 `packages/mcp-server` 的 `rokid.get_device_status`。整个过程坚持 DRY、YAGNI、TDD、小步提交，不提前进入 command、image、多设备、自动重连范围。

实现范围冲突时，一律以 `docs/superpowers/specs/2026-03-30-heartbeat-phase1-design.md` 为准，不回退到旧版完整 MVP 文档。

**Tech Stack:** Bun、TypeScript、Elysia、TypeBox、Bun test、Kotlin、Android SDK、kotlinx.serialization、kotlinx.coroutines、OkHttp、JUnit4

---

## 文件结构与职责锁定

### `packages/protocol`

- `packages/protocol/src/common/scalar.ts`：协议版本、标量 schema、静态类型。
- `packages/protocol/src/common/states.ts`：`setupState`、`runtimeState`、`uplinkState`、`deviceSessionState`、`capability`。
- `packages/protocol/src/common/errors.ts`：`ErrorResponseSchema`。
- `packages/protocol/src/common/index.ts`：common 汇总导出。
- `packages/protocol/src/relay/ws.ts`：第一阶段 WebSocket `hello`、`hello_ack`、`heartbeat`、`phone_state_update`。
- `packages/protocol/src/relay/http.ts`：第一阶段 `GetDeviceStatus*` HTTP DTO。
- `packages/protocol/src/relay/index.ts`：relay 汇总导出。
- `packages/protocol/src/index.ts`：根导出。
- `packages/protocol/src/common/*.test.ts`、`packages/protocol/src/relay/*.test.ts`：schema runtime validation 测试。

### `apps/relay-server`

- `apps/relay-server/src/config/env.ts`：Relay 环境变量解析。
- `apps/relay-server/src/lib/clock.ts`：统一时间源，便于 stale/timeout 测试。
- `apps/relay-server/src/lib/logger.ts`：日志接口。
- `apps/relay-server/src/lib/errors.ts`：路由和 manager 的统一错误对象。
- `apps/relay-server/src/modules/device/single-device-session-store.ts`：当前唯一 session 事实。
- `apps/relay-server/src/modules/device/single-device-runtime-store.ts`：当前唯一 runtime snapshot。
- `apps/relay-server/src/modules/device/device-session-manager.ts`：唯一状态推进 owner。
- `apps/relay-server/src/routes/ws-device.ts`：WS 协议适配。
- `apps/relay-server/src/routes/http-devices.ts`：HTTP 状态查询适配。
- `apps/relay-server/src/app.ts`：应用装配。
- `apps/relay-server/src/main.ts`：进程入口，负责 listen。
- `apps/relay-server/src/**/*.test.ts`：manager、route、app 测试。

### `apps/android/share`

- `apps/android/share/src/test/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodecTest.kt`：扩充本地协议 codec 测试，覆盖 `hello_ack`、`ping`、`pong`、拒绝握手。

### `apps/android/phone-app`

- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneRuntimeStore.kt`：Phone 对外状态快照 owner。
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RfcommClientTransport.kt`：RFCOMM 客户端字节流边界。
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLocalLinkSession.kt`：本地 `hello / hello_ack / ping / pong` 会话。
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt`：Phone <-> Relay WebSocket 会话 owner。
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`：唯一 store 写入者。
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayService.kt`：生命周期宿主。
- `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/*.kt`：session、controller、relay client 的 JVM 单测。

### `apps/android/glasses-app`

- `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesRuntimeStore.kt`：Glasses 对外状态快照 owner。
- `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/RfcommServerTransport.kt`：RFCOMM 服务端字节流边界。
- `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesLocalLinkSession.kt`：本地握手与 `ping/pong` 应答。
- `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppController.kt`：唯一 store 写入者。
- `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesGatewayService.kt`：生命周期宿主。
- `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/*.kt`：session、controller 的 JVM 单测。

### `packages/mcp-server`

- `packages/mcp-server/src/config/env.ts`：MCP 环境变量解析。
- `packages/mcp-server/src/lib/errors.ts`：错误码与错误工厂。
- `packages/mcp-server/src/lib/logger.ts`：最小日志接口。
- `packages/mcp-server/src/relay/relay-response-validator.ts`：Relay DTO runtime validation。
- `packages/mcp-server/src/relay/relay-client.ts`：HTTP 调 Relay。
- `packages/mcp-server/src/mapper/result-mapper.ts`：`GetDeviceStatusResponse -> RokidGetDeviceStatusOutput`。
- `packages/mcp-server/src/tools/get-device-status.ts`：唯一 tool handler 与最终错误收口。
- `packages/mcp-server/src/server.ts`：MCP server 装配与 tool 注册。
- `packages/mcp-server/src/index.ts`：包根导出。
- `packages/mcp-server/src/**/*.test.ts`：env、validator、client、tool 测试。

## 依赖与验证约束

- TypeScript 侧测试统一使用 `bun test <path>`，不额外引入 Vitest 或 Jest。
- Android 侧优先做 JVM 单测，不承诺 `GatewayService` 和真实 Bluetooth transport 的本地单测。
- Android 侧若实现 `PhoneGatewayService` / `GlassesGatewayService`，必须补 `androidx.lifecycle:lifecycle-service`。
- Android 侧默认引入 `kotlinx-coroutines-core`、`kotlinx-coroutines-android`、`kotlinx-coroutines-test`、`okhttp`。
- Android 侧若在 JVM 单测中使用 `ApplicationProvider`，必须补 `androidx.test:core` 与 `org.robolectric:robolectric`。
- Compose 层统一使用 `collectAsStateWithLifecycle()`，并补 `androidx.lifecycle:lifecycle-runtime-compose`。
- `RelaySessionClient` 默认使用 `OkHttp WebSocket`，但测试必须通过自定义 `RelayWebSocket` 接口隔离，不直接依赖真实网络。
- 阶段一不以真机链路打通为验收门槛；Android 验证以 JVM 单测、fake transport、loopback 测试为准。
- `AndroidRfcommClientTransport` / `AndroidRfcommServerTransport` 中保留 `TODO(...)` 作为阶段二实现占位是预期内行为，不视为 placeholder 缺陷。
- 根 `package.json` 的 workspace 需要补齐 `apps/android`，并新增最小 `apps/android/package.json` 作为仓库对齐 shim；Android 构建与测试仍然只通过 Gradle 执行。
- `docs/` 目录保持未跟踪，不加入版本控制。

## 推荐并行窗口

| Task | 是否可并行 | 硬依赖 | 评估 |
| - | - | - | - |
| Task 1 | 可 | 无 | 可与 Android 侧 Task 6、Task 7 并行；不应与依赖它的 Task 2、Task 3 并行落主干。 |
| Task 2 | 条件可并行 | Task 1 | 完成 Task 1 后，可与 Android 侧任务、Task 3 并行；它是 Task 4、Task 5、Task 11 的前置。 |
| Task 3 | 条件可并行 | Task 1 | 完成 Task 1 后，可与 Task 2、Task 7、Task 8.5 并行；它是 Task 4、Task 5 的前置。 |
| Task 4 | 条件可并行 | Task 2、Task 3 | 完成协议与 Relay 骨架后，可与 Android 侧 Task 8、Task 8.5、Task 11 并行；不可与 Task 5 并行。 |
| Task 5 | 不建议并行 | Task 4 | 直接依赖 manager API 与 route 接线结果，应串行跟在 Task 4 后。 |
| Task 6 | 可 | 无 | 与 Task 1、Task 7 完全独立；应在 Task 8、Task 9、Task 10.5 之前完成。 |
| Task 7 | 可 | 无 | 可与 Task 1、Task 6 并行；是 Task 8、Task 8.5、Task 9、Task 10 的前置。 |
| Task 8 | 条件可并行 | Task 6、Task 7 | 可与 Task 4、Task 8.5、Task 11 并行；与 Task 9 没有硬编译依赖，但为了减少本地链路语义分歧，默认不与 Task 9 同时推进。 |
| Task 8.5 | 条件可并行 | Task 7 | 可与 Task 8、Task 11 并行；会修改 `PhoneAppController.kt`，因此不要与 Task 9、Task 10 并行。 |
| Task 9 | 不建议并行 | Task 6、Task 7、Task 8.5 | 与 Task 8 没有硬编译依赖，但和 Task 8.5 都会推动 phone gateway 契约，默认串行更稳；它是 Task 10、Task 10.5 的前置。 |
| Task 10 | 条件可并行 | Task 2、Task 7、Task 8.5、Task 9 | 完成 phone 侧本地链路与配置装配后，可与 Task 11 并行；不要早于 Task 9。 |
| Task 10.5 | 不可 | Task 8、Task 9 | loopback 需要 phone/glasses session 都稳定后再做，只能串行。 |
| Task 11 | 条件可并行 | Task 2 | 完成共享 Relay DTO 后即可启动；可与 Task 4、Task 8、Task 8.5、Task 10 并行。 |
| Task 12 | 不可 | Task 1-11 | 全仓验证必须最后统一执行。 |

并行执行注意事项：
- Task 1、Task 3、Task 11 都会更新 `bun.lock`；若采用多分支并行，合并时要串行 rebase/resolve lockfile。
- Task 8.5、Task 9、Task 10 都会修改 `apps/android/phone-app/src/main/java/.../PhoneAppController.kt`，这三个任务默认必须串行。
- Task 4 与 Task 5 同属 `apps/relay-server` 主链路，manager API 未稳定前不要并行推进 route 接线。
- Task 12 只消费前面成果，不承担设计探索；任何失败都回流到对应前置 Task 修复。

推荐执行波次：
1. Wave 1：Task 1 + Task 6 + Task 7
2. Wave 2：Task 2 + Task 3
3. Wave 3：Task 4 + Task 8 + Task 8.5 + Task 11
4. Wave 4：Task 5 + Task 9
5. Wave 5：Task 10
6. Wave 6：Task 10.5
7. Wave 7：Task 12

若需要进一步压缩工期，可采用更激进但风险更高的排法：
- 在 Wave 3 中让 Task 8 与 Task 9 交叠，但前提是 phone/glasses 两侧先对齐 `hello_ack`、`ping/pong` 的事件契约。
- 在 Wave 4 中让 Task 10 与 Task 5 交叠，但前提是 Relay HTTP/WS 行为已经由 Task 4 的测试稳定下来。

### Task 1: `packages/protocol` common schema

**Files:**
- Modify: `packages/protocol/package.json`
- Modify: `packages/protocol/src/index.ts`
- Create: `packages/protocol/src/common/scalar.ts`
- Create: `packages/protocol/src/common/states.ts`
- Create: `packages/protocol/src/common/errors.ts`
- Create: `packages/protocol/src/common/index.ts`
- Test: `packages/protocol/src/common/scalar.test.ts`
- Test: `packages/protocol/src/common/states.test.ts`

- [ ] **Step 1: 安装 schema 依赖**

运行：

```bash
bun add @sinclair/typebox --cwd packages/protocol && bun install --no-cache
```

预期：
- `packages/protocol/package.json` 出现 `@sinclair/typebox`
- 根 `bun.lock` 更新

- [ ] **Step 2: 写失败测试**

```ts
// packages/protocol/src/common/scalar.test.ts
import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  DeviceIdSchema,
  SessionIdSchema,
  TimestampSchema,
  NullableStringSchema
} from "./scalar.js";

describe("common scalar schema", () => {
  test("accepts valid device id", () => {
    expect(Value.Check(DeviceIdSchema, "rokid_glasses_01")).toBe(true);
  });

  test("rejects invalid session id", () => {
    expect(Value.Check(SessionIdSchema, "bad-session")).toBe(false);
  });

  test("rejects non-positive timestamp", () => {
    expect(Value.Check(TimestampSchema, 0)).toBe(false);
  });

  test("accepts nullable string", () => {
    expect(Value.Check(NullableStringSchema, null)).toBe(true);
    expect(Value.Check(NullableStringSchema, "ERR_TIMEOUT")).toBe(true);
  });
});
```

```ts
// packages/protocol/src/common/states.test.ts
import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  SetupStateSchema,
  RuntimeStateSchema,
  UplinkStateSchema,
  DeviceSessionStateSchema,
  CapabilitySchema,
  CapabilitiesSchema
} from "./states.js";

describe("common state schema", () => {
  test("accepts setup state INITIALIZED", () => {
    expect(Value.Check(SetupStateSchema, "INITIALIZED")).toBe(true);
  });

  test("accepts runtime state READY", () => {
    expect(Value.Check(RuntimeStateSchema, "READY")).toBe(true);
  });

  test("rejects invalid uplink state", () => {
    expect(Value.Check(UplinkStateSchema, "BROKEN")).toBe(false);
  });

  test("accepts session state STALE", () => {
    expect(Value.Check(DeviceSessionStateSchema, "STALE")).toBe(true);
  });

  test("accepts capabilities array", () => {
    expect(Value.Check(CapabilitiesSchema, ["display_text", "capture_photo"])).toBe(true);
    expect(Value.Check(CapabilitySchema, "capture_photo")).toBe(true);
  });
});
```

- [ ] **Step 3: 运行测试，确认失败**

运行：

```bash
bun test packages/protocol/src/common/scalar.test.ts packages/protocol/src/common/states.test.ts
```

预期：
- 失败
- 报错 `Cannot find module './scalar.js'` 或 `Cannot find module './states.js'`

- [ ] **Step 4: 写最小实现**

```ts
// packages/protocol/src/common/scalar.ts
import { Type, type Static } from "@sinclair/typebox";

export const PROTOCOL_NAME = "rokid-relay-protocol";
export const PROTOCOL_VERSION = "1.0" as const;

export const DeviceIdSchema = Type.String({
  pattern: "^[a-zA-Z0-9._-]{3,64}$"
});

export const SessionIdSchema = Type.String({
  pattern: "^ses_[a-zA-Z0-9_-]{6,128}$"
});

export const TimestampSchema = Type.Number({
  minimum: 1
});

export const NullableStringSchema = Type.Union([Type.String(), Type.Null()]);

export type DeviceId = Static<typeof DeviceIdSchema>;
export type SessionId = Static<typeof SessionIdSchema>;
export type Timestamp = Static<typeof TimestampSchema>;
```

```ts
// packages/protocol/src/common/states.ts
import { Type, type Static } from "@sinclair/typebox";

export const SetupStateSchema = Type.Union([
  Type.Literal("UNINITIALIZED"),
  Type.Literal("INITIALIZED")
]);

export const RuntimeStateSchema = Type.Union([
  Type.Literal("DISCONNECTED"),
  Type.Literal("CONNECTING"),
  Type.Literal("READY"),
  Type.Literal("BUSY"),
  Type.Literal("ERROR")
]);

export const UplinkStateSchema = Type.Union([
  Type.Literal("OFFLINE"),
  Type.Literal("CONNECTING"),
  Type.Literal("ONLINE"),
  Type.Literal("ERROR")
]);

export const DeviceSessionStateSchema = Type.Union([
  Type.Literal("OFFLINE"),
  Type.Literal("ONLINE"),
  Type.Literal("STALE"),
  Type.Literal("CLOSED")
]);

export const CapabilitySchema = Type.Union([
  Type.Literal("display_text"),
  Type.Literal("capture_photo")
]);

export const CapabilitiesSchema = Type.Array(CapabilitySchema, {
  minItems: 0
});

export type SetupState = Static<typeof SetupStateSchema>;
export type RuntimeState = Static<typeof RuntimeStateSchema>;
export type UplinkState = Static<typeof UplinkStateSchema>;
export type DeviceSessionState = Static<typeof DeviceSessionStateSchema>;
export type Capability = Static<typeof CapabilitySchema>;
```

```ts
// packages/protocol/src/common/errors.ts
import { Type, type Static } from "@sinclair/typebox";
import { TimestampSchema } from "./scalar.js";

export const ErrorResponseSchema = Type.Object({
  ok: Type.Literal(false),
  error: Type.Object({
    code: Type.String(),
    message: Type.String(),
    retryable: Type.Boolean(),
    details: Type.Optional(Type.Record(Type.String(), Type.Unknown()))
  }),
  timestamp: TimestampSchema
});

export type ErrorResponse = Static<typeof ErrorResponseSchema>;
```

```ts
// packages/protocol/src/common/index.ts
export * from "./scalar.js";
export * from "./states.js";
export * from "./errors.js";
```

```ts
// packages/protocol/src/index.ts
export * from "./common/index.js";
```

- [ ] **Step 5: 运行测试，确认通过**

运行：

```bash
bun test packages/protocol/src/common/scalar.test.ts packages/protocol/src/common/states.test.ts
```

预期：
- PASS

- [ ] **Step 6: 运行类型检查和构建**

运行：

```bash
bun run --cwd packages/protocol typecheck && bun run --cwd packages/protocol build
```

预期：
- 通过

- [ ] **Step 7: Commit**

```bash
git add packages/protocol package.json bun.lock
git commit -m "feat: add protocol common schemas"
```

### Task 2: `packages/protocol` Relay WS/HTTP DTO

**Files:**
- Modify: `packages/protocol/src/index.ts`
- Create: `packages/protocol/src/relay/ws.ts`
- Create: `packages/protocol/src/relay/http.ts`
- Create: `packages/protocol/src/relay/index.ts`
- Test: `packages/protocol/src/relay/ws.test.ts`
- Test: `packages/protocol/src/relay/http.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
// packages/protocol/src/relay/ws.test.ts
import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
  RelayHelloAckMessageSchema,
  RelayDeviceInboundMessageSchema
} from "./ws.js";

describe("relay ws schema", () => {
  test("accepts hello message", () => {
    const message = {
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "dev_token_xxx",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"]
      }
    };

    expect(Value.Check(RelayHelloMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
  });

  test("accepts heartbeat message", () => {
    const message = {
      version: "1.0",
      type: "heartbeat",
      deviceId: "rokid_glasses_01",
      sessionId: "ses_abcdef",
      timestamp: 1710000001000,
      payload: {
        seq: 1,
        runtimeState: "READY",
        uplinkState: "ONLINE",
        pendingCommandCount: 0,
        activeCommandRequestId: null
      }
    };

    expect(Value.Check(RelayHeartbeatMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
  });

  test("rejects hello_ack from inbound union", () => {
    const message = {
      version: "1.0",
      type: "hello_ack",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        sessionId: "ses_abcdef",
        serverTime: 1710000000000,
        heartbeatIntervalMs: 5000,
        heartbeatTimeoutMs: 15000,
        limits: {
          maxPendingCommands: 1,
          maxImageUploadSizeBytes: 10485760,
          acceptedImageContentTypes: ["image/jpeg"]
        }
      }
    };

    expect(Value.Check(RelayHelloAckMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(false);
  });
});
```

```ts
// packages/protocol/src/relay/http.test.ts
import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  GetDeviceStatusParamsSchema,
  GetDeviceStatusResponseSchema
} from "./http.js";

describe("relay http schema", () => {
  test("accepts device status params", () => {
    expect(Value.Check(GetDeviceStatusParamsSchema, { deviceId: "rokid_glasses_01" })).toBe(true);
  });

  test("accepts synthetic offline response", () => {
    const response = {
      ok: true,
      device: {
        deviceId: "rokid_glasses_01",
        connected: false,
        sessionState: "OFFLINE",
        setupState: "UNINITIALIZED",
        runtimeState: "DISCONNECTED",
        uplinkState: "OFFLINE",
        capabilities: [],
        activeCommandRequestId: null,
        lastErrorCode: null,
        lastErrorMessage: null,
        lastSeenAt: null,
        sessionId: null
      },
      timestamp: 1710000000000
    };

    expect(Value.Check(GetDeviceStatusResponseSchema, response)).toBe(true);
  });
});
```

- [ ] **Step 2: 运行测试，确认失败**

运行：

```bash
bun test packages/protocol/src/relay/ws.test.ts packages/protocol/src/relay/http.test.ts
```

预期：
- 失败
- 报错 `Cannot find module './ws.js'` 或 `Cannot find module './http.js'`

- [ ] **Step 3: 写最小实现**

```ts
// packages/protocol/src/relay/http.ts
import { Type, type Static } from "@sinclair/typebox";
import {
  DeviceIdSchema,
  NullableStringSchema,
  SessionIdSchema,
  TimestampSchema
} from "../common/scalar.js";
import {
  CapabilitiesSchema,
  DeviceSessionStateSchema,
  RuntimeStateSchema,
  SetupStateSchema,
  UplinkStateSchema
} from "../common/states.js";

export const GetDeviceStatusParamsSchema = Type.Object({
  deviceId: DeviceIdSchema
});

export const GetDeviceStatusDeviceSchema = Type.Object({
  deviceId: DeviceIdSchema,
  connected: Type.Boolean(),
  sessionState: DeviceSessionStateSchema,
  setupState: SetupStateSchema,
  runtimeState: RuntimeStateSchema,
  uplinkState: UplinkStateSchema,
  capabilities: CapabilitiesSchema,
  activeCommandRequestId: Type.Null(),
  lastErrorCode: Type.Optional(NullableStringSchema),
  lastErrorMessage: Type.Optional(NullableStringSchema),
  lastSeenAt: Type.Optional(Type.Union([TimestampSchema, Type.Null()])),
  sessionId: Type.Optional(Type.Union([SessionIdSchema, Type.Null()]))
});

export const GetDeviceStatusResponseSchema = Type.Object({
  ok: Type.Literal(true),
  device: GetDeviceStatusDeviceSchema,
  timestamp: TimestampSchema
});

export type GetDeviceStatusParams = Static<typeof GetDeviceStatusParamsSchema>;
export type GetDeviceStatusDevice = Static<typeof GetDeviceStatusDeviceSchema>;
export type GetDeviceStatusResponse = Static<typeof GetDeviceStatusResponseSchema>;
```

```ts
// packages/protocol/src/relay/ws.ts
import { Type, type Static } from "@sinclair/typebox";
import {
  DeviceIdSchema,
  NullableStringSchema,
  PROTOCOL_VERSION,
  SessionIdSchema,
  TimestampSchema
} from "../common/scalar.js";
import {
  CapabilitiesSchema,
  RuntimeStateSchema,
  SetupStateSchema,
  UplinkStateSchema
} from "../common/states.js";

const RelayEnvelopeBaseSchema = Type.Object({
  version: Type.Literal(PROTOCOL_VERSION),
  type: Type.String(),
  deviceId: DeviceIdSchema,
  requestId: Type.Optional(Type.String()),
  sessionId: Type.Optional(SessionIdSchema),
  timestamp: TimestampSchema
});

const RelayPhoneInfoSchema = Type.Object({
  brand: Type.Optional(Type.String()),
  model: Type.Optional(Type.String()),
  androidVersion: Type.Optional(Type.String()),
  sdkInt: Type.Optional(Type.Number())
});

const RelayTargetGlassesSchema = Type.Object({
  bluetoothName: Type.Optional(Type.String()),
  bluetoothAddress: Type.Optional(Type.String())
});

const RelayConfigSchema = Type.Object({
  baseUrl: Type.Optional(Type.String())
});

export const RelayHelloMessageSchema = Type.Composite([
  RelayEnvelopeBaseSchema,
  Type.Object({
    type: Type.Literal("hello"),
    payload: Type.Object({
      authToken: Type.String({ minLength: 1 }),
      appVersion: Type.String({ minLength: 1 }),
      appBuild: Type.Optional(Type.String()),
      phoneInfo: RelayPhoneInfoSchema,
      setupState: SetupStateSchema,
      runtimeState: RuntimeStateSchema,
      uplinkState: Type.Union([Type.Literal("CONNECTING"), Type.Literal("ONLINE")]),
      capabilities: Type.Array(Type.Union([Type.Literal("display_text"), Type.Literal("capture_photo")]), { minItems: 1 }),
      targetGlasses: Type.Optional(RelayTargetGlassesSchema),
      relayConfig: Type.Optional(RelayConfigSchema)
    })
  })
]);

export const RelayHeartbeatMessageSchema = Type.Composite([
  RelayEnvelopeBaseSchema,
  Type.Object({
    type: Type.Literal("heartbeat"),
    payload: Type.Object({
      seq: Type.Number({ minimum: 0 }),
      runtimeState: RuntimeStateSchema,
      uplinkState: Type.Union([Type.Literal("ONLINE"), Type.Literal("ERROR")]),
      pendingCommandCount: Type.Number({ minimum: 0 }),
      activeCommandRequestId: Type.Null()
    })
  })
]);

export const RelayPhoneStateUpdateMessageSchema = Type.Composite([
  RelayEnvelopeBaseSchema,
  Type.Object({
    type: Type.Literal("phone_state_update"),
    payload: Type.Object({
      setupState: SetupStateSchema,
      runtimeState: RuntimeStateSchema,
      uplinkState: UplinkStateSchema,
      lastErrorCode: Type.Optional(NullableStringSchema),
      lastErrorMessage: Type.Optional(NullableStringSchema),
      activeCommandRequestId: Type.Null()
    })
  })
]);

export const RelayHelloAckMessageSchema = Type.Object({
  version: Type.Literal(PROTOCOL_VERSION),
  type: Type.Literal("hello_ack"),
  deviceId: DeviceIdSchema,
  timestamp: TimestampSchema,
  payload: Type.Object({
    sessionId: SessionIdSchema,
    serverTime: TimestampSchema,
    heartbeatIntervalMs: Type.Number({ minimum: 1 }),
    heartbeatTimeoutMs: Type.Number({ minimum: 1 }),
    sessionTtlMs: Type.Optional(Type.Number({ minimum: 1 })),
    limits: Type.Object({
      maxPendingCommands: Type.Number({ minimum: 0 }),
      maxImageUploadSizeBytes: Type.Number({ minimum: 1 }),
      acceptedImageContentTypes: Type.Array(Type.String(), { minItems: 1 })
    })
  })
});

export const RelayDeviceInboundMessageSchema = Type.Union([
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
  RelayPhoneStateUpdateMessageSchema
]);

export type RelayHelloMessage = Static<typeof RelayHelloMessageSchema>;
export type RelayHeartbeatMessage = Static<typeof RelayHeartbeatMessageSchema>;
export type RelayPhoneStateUpdateMessage = Static<typeof RelayPhoneStateUpdateMessageSchema>;
export type RelayHelloAckMessage = Static<typeof RelayHelloAckMessageSchema>;
export type RelayDeviceInboundMessage = Static<typeof RelayDeviceInboundMessageSchema>;
```

```ts
// packages/protocol/src/relay/index.ts
export * from "./ws.js";
export * from "./http.js";
```

```ts
// packages/protocol/src/index.ts
export * from "./common/index.js";
export * from "./relay/index.js";
```

- [ ] **Step 4: 运行测试，确认通过**

运行：

```bash
bun test packages/protocol/src/relay/ws.test.ts packages/protocol/src/relay/http.test.ts
```

预期：
- PASS

- [ ] **Step 5: 运行类型检查和构建**

运行：

```bash
bun run --cwd packages/protocol typecheck && bun run --cwd packages/protocol build
```

预期：
- 通过

- [ ] **Step 6: Commit**

```bash
git add packages/protocol
git commit -m "feat: add relay phase1 protocol schemas"
```

### Task 3: Relay 基础骨架与依赖

**Files:**
- Modify: `apps/relay-server/package.json`
- Modify: `apps/relay-server/src/main.ts`
- Create: `apps/relay-server/src/config/env.ts`
- Create: `apps/relay-server/src/lib/clock.ts`
- Create: `apps/relay-server/src/lib/logger.ts`
- Create: `apps/relay-server/src/lib/errors.ts`
- Create: `apps/relay-server/src/app.ts`
- Test: `apps/relay-server/src/app.test.ts`

- [ ] **Step 1: 安装依赖**

运行：

```bash
bun add @sinclair/typebox --cwd apps/relay-server && bun install --no-cache
```

预期：
- `apps/relay-server/package.json` 更新

- [ ] **Step 2: 写失败测试**

```ts
// apps/relay-server/src/app.test.ts
import { describe, expect, test } from "bun:test";
import { createApp } from "./app.js";

describe("relay app", () => {
  test("health route responds", async () => {
    const app = createApp();
    const response = await app.handle(new Request("http://localhost/health"));
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.ok).toBe(true);
    expect(json.service).toBe("relay-server");
  });
});
```

- [ ] **Step 3: 运行测试，确认失败**

运行：

```bash
bun test apps/relay-server/src/app.test.ts
```

预期：
- 失败
- 报错 `Cannot find module './app.js'`

- [ ] **Step 4: 写最小实现**

```ts
// apps/relay-server/src/config/env.ts
export type RelayEnv = {
  port: number;
  host: string;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
};

export function readRelayEnv(env: NodeJS.ProcessEnv = process.env): RelayEnv {
  return {
    port: Number(env.PORT ?? 3000),
    host: env.HOST ?? "0.0.0.0",
    heartbeatIntervalMs: Number(env.RELAY_HEARTBEAT_INTERVAL_MS ?? 5000),
    heartbeatTimeoutMs: Number(env.RELAY_HEARTBEAT_TIMEOUT_MS ?? 15000)
  };
}
```

```ts
// apps/relay-server/src/lib/clock.ts
export interface Clock {
  now(): number;
}

export const systemClock: Clock = {
  now: () => Date.now()
};
```

```ts
// apps/relay-server/src/lib/logger.ts
export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

export const consoleLogger: Logger = {
  info(message, meta) {
    console.info(message, meta ?? {});
  },
  error(message, meta) {
    console.error(message, meta ?? {});
  }
};
```

```ts
// apps/relay-server/src/lib/errors.ts
export class RelayAppError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryable = false
  ) {
    super(message);
  }
}
```

```ts
// apps/relay-server/src/app.ts
import { Elysia } from "elysia";
import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";

export function createApp() {
  return new Elysia().get("/health", () => ({
    ok: true,
    service: "relay-server",
    protocol: PROTOCOL_NAME,
    version: PROTOCOL_VERSION
  }));
}
```

```ts
// apps/relay-server/src/main.ts
import { createApp } from "./app.js";
import { readRelayEnv } from "./config/env.js";

const env = readRelayEnv();

createApp().listen({
  hostname: env.host,
  port: env.port
}, ({ hostname, port }) => {
  console.log(`relay-server listening on http://${hostname}:${port}`);
});
```

- [ ] **Step 5: 运行测试与类型检查**

运行：

```bash
bun test apps/relay-server/src/app.test.ts && bun run --cwd apps/relay-server typecheck && bun run --cwd apps/relay-server build
```

预期：
- 通过

- [ ] **Step 6: Commit**

```bash
git add apps/relay-server package.json bun.lock
git commit -m "feat: scaffold relay phase1 app"
```

### Task 4: Relay singleton stores 与 manager

**Files:**
- Create: `apps/relay-server/src/modules/device/single-device-session-store.ts`
- Create: `apps/relay-server/src/modules/device/single-device-runtime-store.ts`
- Create: `apps/relay-server/src/modules/device/device-session-manager.ts`
- Test: `apps/relay-server/src/modules/device/device-session-manager.test.ts`

实现边界澄清：
- `single-device-session-store` 和 `single-device-runtime-store` 都只允许保存 **当前唯一设备上下文**，不得退化成 `Map<deviceId, ...>` 的“按设备单例”模型。
- Task 4 只修正 singleton store 与 manager 语义，不在这里引入 route 级 connection registry；WS 连接生命周期与 `ws.data` 挂载放到 Task 5。
- `getCurrentDeviceStatus(requestedDeviceId)` 只回答当前 singleton；若 `requestedDeviceId` 不命中当前上下文，一律返回 synthetic offline，不暴露旧设备历史。

- [ ] **Step 1: 写失败测试**

```ts
// apps/relay-server/src/modules/device/device-session-manager.test.ts
import { describe, expect, test } from "bun:test";
import { DeviceSessionManager } from "./device-session-manager.js";

describe("DeviceSessionManager", () => {
  test("returns synthetic offline response for unknown device", () => {
    const manager = new DeviceSessionManager({
      now: () => 1710000000000,
      heartbeatIntervalMs: 5000,
      heartbeatTimeoutMs: 15000
    });

    const status = manager.getCurrentDeviceStatus("rokid_glasses_01");

    expect(status.ok).toBe(true);
    expect(status.device.connected).toBe(false);
    expect(status.device.sessionState).toBe("OFFLINE");
    expect(status.device.runtimeState).toBe("DISCONNECTED");
    expect(status.device.uplinkState).toBe("OFFLINE");
  });

  test("new hello from same device replaces old session and keeps latest capability set", () => {
    const manager = new DeviceSessionManager({
      now: () => 1710000000000,
      heartbeatIntervalMs: 5000,
      heartbeatTimeoutMs: 15000
    });

    const socketA = { send() {}, close() {} };
    const socketB = { send() {}, close() {} };

    manager.registerHello({
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "token",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"]
      }
    }, socketA);

    manager.registerHello({
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000001000,
      payload: {
        authToken: "token",
        appVersion: "0.1.1",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "CONNECTING",
        capabilities: ["display_text", "capture_photo"]
      }
    }, socketB);

    const status = manager.getCurrentDeviceStatus("rokid_glasses_01");

    expect(status.device.connected).toBe(true);
    expect(status.device.capabilities).toEqual(["display_text", "capture_photo"]);
  });

  test("new hello from different device replaces singleton context and old device becomes synthetic offline", () => {
    const manager = new DeviceSessionManager({
      now: () => 1710000000000,
      heartbeatIntervalMs: 5000,
      heartbeatTimeoutMs: 15000
    });

    const socketA = { send() {}, close() {} };
    const socketB = { send() {}, close() {} };

    manager.registerHello({
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "token",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"]
      }
    }, socketA);

    manager.registerHello({
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_02",
      timestamp: 1710000001000,
      payload: {
        authToken: "token",
        appVersion: "0.1.1",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "CONNECTING",
        capabilities: ["display_text", "capture_photo"]
      }
    }, socketB);

    const replaced = manager.getCurrentDeviceStatus("rokid_glasses_01");
    const current = manager.getCurrentDeviceStatus("rokid_glasses_02");

    expect(replaced.device.connected).toBe(false);
    expect(replaced.device.sessionState).toBe("OFFLINE");
    expect(replaced.device.sessionId).toBeNull();
    expect(current.device.connected).toBe(true);
    expect(current.device.deviceId).toBe("rokid_glasses_02");
    expect(current.device.capabilities).toEqual(["display_text", "capture_photo"]);
  });

  test("late close from old socket does not clear current session", () => {
    let now = 1710000000000;
    const manager = new DeviceSessionManager({
      now: () => now,
      heartbeatIntervalMs: 5000,
      heartbeatTimeoutMs: 15000
    });

    const socketA = { send() {}, close() {} };
    const socketB = { send() {}, close() {} };

    manager.registerHello({
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: now,
      payload: {
        authToken: "token",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"]
      }
    }, socketA);

    now += 1000;

    manager.registerHello({
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: now,
      payload: {
        authToken: "token",
        appVersion: "0.1.1",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"]
      }
    }, socketB);

    manager.closeCurrentSession("WS_CLOSED", socketA);

    const status = manager.getCurrentDeviceStatus("rokid_glasses_01");
    expect(status.device.connected).toBe(true);
    expect(status.device.sessionState).toBe("ONLINE");
  });
});
```

- [ ] **Step 2: 运行测试，确认失败**

运行：

```bash
bun test apps/relay-server/src/modules/device/device-session-manager.test.ts
```

预期：
- 失败
- 报错 `Cannot find module './device-session-manager.js'`

- [ ] **Step 3: 写最小实现**

```ts
// apps/relay-server/src/modules/device/single-device-session-store.ts
export type CurrentSessionRecord = {
  deviceId: string;
  sessionId: string;
  socketRef: unknown | null;
  sessionState: "OFFLINE" | "ONLINE" | "STALE" | "CLOSED";
  appVersion: string;
  capabilities: Array<"display_text" | "capture_photo">;
  connectedAt: number;
  authenticatedAt: number;
  lastSeenAt: number;
  lastHeartbeatAt?: number;
  closedAt?: number;
};

export class SingleDeviceSessionStore {
  private current: CurrentSessionRecord | null = null;

  get() {
    return this.current;
  }

  set(record: CurrentSessionRecord) {
    this.current = record;
  }

  patch(partial: Partial<CurrentSessionRecord>) {
    if (!this.current) return;
    this.current = { ...this.current, ...partial };
  }

  clear() {
    this.current = null;
  }
}
```

```ts
// apps/relay-server/src/modules/device/single-device-runtime-store.ts
export type CurrentRuntimeSnapshot = {
  deviceId: string;
  setupState: "UNINITIALIZED" | "INITIALIZED";
  runtimeState: "DISCONNECTED" | "CONNECTING" | "READY" | "BUSY" | "ERROR";
  uplinkState: "OFFLINE" | "CONNECTING" | "ONLINE" | "ERROR";
  activeCommandRequestId?: string | null;
  lastErrorCode?: string | null;
  lastErrorMessage?: string | null;
  lastUpdatedAt: number;
};

export class SingleDeviceRuntimeStore {
  private current: CurrentRuntimeSnapshot | null = null;

  get() {
    return this.current;
  }

  set(snapshot: CurrentRuntimeSnapshot) {
    this.current = snapshot;
  }

  patch(partial: Partial<CurrentRuntimeSnapshot>) {
    if (!this.current) return;
    this.current = { ...this.current, ...partial };
  }

  clear() {
    this.current = null;
  }
}
```

```ts
// apps/relay-server/src/modules/device/device-session-manager.ts
import type {
  GetDeviceStatusResponse,
  RelayHeartbeatMessage,
  RelayHelloAckMessage,
  RelayHelloMessage,
  RelayPhoneStateUpdateMessage
} from "@rokid-mcp/protocol";
import { SingleDeviceRuntimeStore } from "./single-device-runtime-store.js";
import { SingleDeviceSessionStore } from "./single-device-session-store.js";

type DeviceSessionManagerDeps = {
  now: () => number;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
};

export class DeviceSessionManager {
  private readonly sessionStore = new SingleDeviceSessionStore();
  private readonly runtimeStore = new SingleDeviceRuntimeStore();

  constructor(private readonly deps: DeviceSessionManagerDeps) {}

  registerHello(message: RelayHelloMessage, socketRef: unknown) {
    const now = this.deps.now();
    const sessionId = `ses_${Math.random().toString(36).slice(2, 10)}`;

    // Phase 1 is a true singleton: any new hello replaces the entire current device context.
    const current = this.sessionStore.get();
    if (current?.socketRef && current.socketRef !== socketRef) {
      const oldSocket = current.socketRef as { close?: () => void };
      oldSocket.close?.();
    }

    this.sessionStore.set({
      deviceId: message.deviceId,
      sessionId,
      socketRef,
      sessionState: "ONLINE",
      appVersion: message.payload.appVersion,
      capabilities: message.payload.capabilities,
      connectedAt: now,
      authenticatedAt: now,
      lastSeenAt: now
    });

    this.runtimeStore.set({
      deviceId: message.deviceId,
      setupState: message.payload.setupState,
      runtimeState: message.payload.runtimeState,
      uplinkState: message.payload.uplinkState,
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastUpdatedAt: now
    });

    this.markInboundSeen("hello");
  }

  confirmHello(): RelayHelloAckMessage {
    const current = this.sessionStore.get();
    if (!current) {
      throw new Error("No current session");
    }

    return {
      version: "1.0",
      type: "hello_ack",
      deviceId: current.deviceId,
      timestamp: this.deps.now(),
      payload: {
        sessionId: current.sessionId,
        serverTime: this.deps.now(),
        heartbeatIntervalMs: this.deps.heartbeatIntervalMs,
        heartbeatTimeoutMs: this.deps.heartbeatTimeoutMs,
        limits: {
          maxPendingCommands: 1,
          maxImageUploadSizeBytes: 10 * 1024 * 1024,
          acceptedImageContentTypes: ["image/jpeg"]
        }
      }
    };
  }

  markInboundSeen(_type: "hello" | "heartbeat" | "phone_state_update") {
    this.sessionStore.patch({ lastSeenAt: this.deps.now() });
  }

  matchesCurrentSession(deviceId: string, sessionId?: string) {
    const current = this.sessionStore.get();
    if (!current) return false;
    return current.deviceId === deviceId && current.sessionId === sessionId;
  }

  markHeartbeat(message: RelayHeartbeatMessage) {
    this.markInboundSeen("heartbeat");

    this.sessionStore.patch({
      lastHeartbeatAt: this.deps.now(),
      sessionState: "ONLINE"
    });

    this.runtimeStore.patch({
      runtimeState: message.payload.runtimeState,
      uplinkState: message.payload.uplinkState,
      activeCommandRequestId: message.payload.activeCommandRequestId ?? null,
      lastUpdatedAt: this.deps.now()
    });
  }

  applyPhoneStateUpdate(message: RelayPhoneStateUpdateMessage) {
    this.markInboundSeen("phone_state_update");

    this.runtimeStore.patch({
      setupState: message.payload.setupState,
      runtimeState: message.payload.runtimeState,
      uplinkState: message.payload.uplinkState,
      activeCommandRequestId: message.payload.activeCommandRequestId ?? null,
      lastErrorCode: message.payload.lastErrorCode ?? null,
      lastErrorMessage: message.payload.lastErrorMessage ?? null,
      lastUpdatedAt: this.deps.now()
    });
  }

  closeCurrentSession(reason: string, socketRef?: unknown) {
    const current = this.sessionStore.get();
    if (!current) return;
    if (socketRef && current.socketRef !== socketRef) return;

    this.sessionStore.patch({
      socketRef: null,
      sessionState: "CLOSED",
      closedAt: this.deps.now()
    });

    this.runtimeStore.patch({
      runtimeState: "DISCONNECTED",
      uplinkState: "OFFLINE",
      lastErrorCode: reason,
      lastUpdatedAt: this.deps.now()
    });
  }

  getCurrentDeviceStatus(requestedDeviceId: string): GetDeviceStatusResponse {
    const now = this.deps.now();
    const session = this.sessionStore.get();
    const runtime = this.runtimeStore.get();

    // Relay only answers for the current singleton context; previous devices always read as synthetic offline.
    if (!session || !runtime || session.deviceId !== requestedDeviceId) {
      return {
        ok: true,
        device: {
          deviceId: requestedDeviceId,
          connected: false,
          sessionState: "OFFLINE",
          setupState: "UNINITIALIZED",
          runtimeState: "DISCONNECTED",
          uplinkState: "OFFLINE",
          capabilities: [],
          activeCommandRequestId: null,
          lastErrorCode: null,
          lastErrorMessage: null,
          lastSeenAt: null,
          sessionId: null
        },
        timestamp: now
      };
    }

    const connected = session.sessionState === "ONLINE";

    return {
      ok: true,
      device: {
        deviceId: session.deviceId,
        connected,
        sessionState: session.sessionState,
        setupState: runtime.setupState,
        runtimeState: connected ? runtime.runtimeState : "DISCONNECTED",
        uplinkState: connected ? runtime.uplinkState : "OFFLINE",
        capabilities: session.capabilities,
        activeCommandRequestId: runtime.activeCommandRequestId ?? null,
        lastErrorCode: runtime.lastErrorCode ?? null,
        lastErrorMessage: runtime.lastErrorMessage ?? null,
        lastSeenAt: session.lastSeenAt ?? null,
        sessionId: connected || session.sessionState === "CLOSED" ? session.sessionId : null
      },
      timestamp: now
    };
  }

  startCleanupJob() {
    return setInterval(() => {
      const current = this.sessionStore.get();
      if (!current) return;
      if (this.deps.now() - current.lastSeenAt > this.deps.heartbeatTimeoutMs) {
        this.sessionStore.patch({ sessionState: "STALE" });
      }
    }, Math.min(this.deps.heartbeatIntervalMs, 5000));
  }

  stopCleanupJob(timer: ReturnType<typeof setInterval>) {
    clearInterval(timer);
  }
}
```

- [ ] **Step 4: 运行测试，确认通过**

运行：

```bash
bun test apps/relay-server/src/modules/device/device-session-manager.test.ts
```

预期：
- PASS

- [ ] **Step 5: 增加一个 stale 覆盖规则测试并运行**

追加测试，注意这里必须真实等待 cleanup interval 触发，不允许通过“改 fake clock 后立刻 stop timer”伪造定时器行为：

```ts
test("stale session forces disconnected runtime on query after cleanup interval fires", async () => {
  let now = 1710000000000;
  const manager = new DeviceSessionManager({
    now: () => now,
    heartbeatIntervalMs: 5,
    heartbeatTimeoutMs: 15
  });

  const socket = { send() {}, close() {} };

  manager.registerHello({
    version: "1.0",
    type: "hello",
    deviceId: "rokid_glasses_01",
    timestamp: now,
    payload: {
      authToken: "token",
      appVersion: "0.1.0",
      phoneInfo: {},
      setupState: "INITIALIZED",
      runtimeState: "READY",
      uplinkState: "CONNECTING",
      capabilities: ["display_text"]
    }
  }, socket);

  const timer = manager.startCleanupJob();
  now += 16;
  await Bun.sleep(20);
  manager.stopCleanupJob(timer);

  const status = manager.getCurrentDeviceStatus("rokid_glasses_01");
  expect(status.device.sessionState).toBe("STALE");
  expect(status.device.runtimeState).toBe("DISCONNECTED");
  expect(status.device.uplinkState).toBe("OFFLINE");
});
```

运行：

```bash
bun test apps/relay-server/src/modules/device/device-session-manager.test.ts
```

预期：
- PASS

- [ ] **Step 6: Commit**

```bash
git add apps/relay-server/src/modules/device
git commit -m "feat: implement relay singleton session manager"
```

### Task 5: Relay WS/HTTP routes 与入口接线

**Files:**
- Modify: `apps/relay-server/src/app.ts`
- Modify: `apps/relay-server/src/main.ts`
- Create: `apps/relay-server/src/routes/ws-device.ts`
- Create: `apps/relay-server/src/routes/http-devices.ts`
- Test: `apps/relay-server/src/routes/http-devices.test.ts`
- Test: `apps/relay-server/src/routes/ws-device.test.ts`

实现边界澄清：
- Task 5 只负责把 Task 4 的 singleton manager 接到 HTTP / WS 入口，不得重新引入多设备 store 或历史 session 查询。
- `ws-device.ts` 负责保存 **当前连接自己的认证态元数据**（推荐挂到 `ws.data`），例如 `authenticated`、`deviceId`、`sessionId`；manager 仍然只保存全局当前 singleton 上下文。
- 关闭连接时必须通过 `socketRef` 校验当前活跃连接身份；不要在 route 中通过 `deviceId` 查 store 后直接关闭。
- `GET /api/v1/devices/:deviceId/status` 只调用 `manager.getCurrentDeviceStatus(requestedDeviceId)`；若当前 singleton 已切换到新设备，则旧 `deviceId` 查询必须返回 synthetic offline。

- [ ] **Step 1: 写失败测试**

```ts
// apps/relay-server/src/routes/http-devices.test.ts
import { describe, expect, test } from "bun:test";
import { createApp } from "../app.js";

describe("GET /api/v1/devices/:deviceId/status", () => {
  test("returns synthetic offline response when no current session exists", async () => {
    const app = createApp();
    const response = await app.handle(
      new Request("http://localhost/api/v1/devices/rokid_glasses_01/status")
    );
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.device.connected).toBe(false);
    expect(json.device.sessionState).toBe("OFFLINE");
  });

  test("returns synthetic offline response for replaced device id", async () => {
    const app = createApp();

    // 这里只冻结契约：当前 singleton 若已切换，旧设备 id 查询必须离线。
    // 具体接线通过 ws-device route test 或 manager fake 注入完成。
    const response = await app.handle(
      new Request("http://localhost/api/v1/devices/rokid_glasses_old/status")
    );
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.device.deviceId).toBe("rokid_glasses_old");
    expect(json.device.sessionState).toBe("OFFLINE");
  });
});
```

```ts
// apps/relay-server/src/routes/ws-device.test.ts
import { describe, expect, test } from "bun:test";

describe("WS /ws/device", () => {
  test("route module exists and can be imported", async () => {
    const module = await import("./ws-device.js");
    expect(typeof module.registerWsDeviceRoute).toBe("function");
  });
});
```

- [ ] **Step 2: 运行测试，确认失败**

运行：

```bash
bun test apps/relay-server/src/routes/http-devices.test.ts apps/relay-server/src/routes/ws-device.test.ts
```

预期：
- `http-devices` 测试失败，当前是 `404`
- `ws-device` 测试失败，模块不存在

- [ ] **Step 3: 写最小实现**

```ts
// apps/relay-server/src/routes/http-devices.ts
import type { Elysia } from "elysia";
import type { DeviceSessionManager } from "../modules/device/device-session-manager.js";

export function registerHttpDevicesRoute(app: Elysia, manager: DeviceSessionManager) {
  return app.get("/api/v1/devices/:deviceId/status", ({ params }) => {
    return manager.getCurrentDeviceStatus(params.deviceId);
  });
}
```

```ts
// apps/relay-server/src/routes/ws-device.ts
import { Value } from "@sinclair/typebox/value";
import { RelayDeviceInboundMessageSchema } from "@rokid-mcp/protocol";
import type { Elysia } from "elysia";
import type { DeviceSessionManager } from "../modules/device/device-session-manager.js";

type WsConnectionData = {
  authenticated: boolean;
  deviceId?: string;
  sessionId?: string;
};

export function registerWsDeviceRoute(app: Elysia, manager: DeviceSessionManager) {
  return app.ws("/ws/device", {
    open(ws) {
      (ws.data as WsConnectionData).authenticated = false;
    },
    message(ws, raw) {
      let message: unknown;

      try {
        const text = typeof raw === "string" ? raw : String(raw);
        message = JSON.parse(text);
      } catch {
        ws.close();
        return;
      }

      if (!Value.Check(RelayDeviceInboundMessageSchema, message)) {
        ws.close();
        return;
      }

      const data = ws.data as WsConnectionData;

      if (message.type === "hello") {
        manager.registerHello(message, ws);
        const ack = manager.confirmHello();
        data.authenticated = true;
        data.deviceId = message.deviceId;
        data.sessionId = ack.payload.sessionId;
        ws.send(JSON.stringify(ack));
        return;
      }

      if (!data.authenticated) {
        ws.close();
        return;
      }

      if (!manager.matchesCurrentSession(message.deviceId, message.sessionId)) {
        ws.close();
        return;
      }

      if (message.type === "heartbeat") {
        manager.markHeartbeat(message);
        return;
      }

      if (message.type === "phone_state_update") {
        manager.applyPhoneStateUpdate(message);
      }
    },
    close(ws) {
      // Close must only affect the current singleton session if this socket is still the active one.
      manager.closeCurrentSession("WS_CLOSED", ws);
    }
  });
}
```

```ts
// apps/relay-server/src/app.ts
import { Elysia } from "elysia";
import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";
import { readRelayEnv } from "./config/env.js";
import { DeviceSessionManager } from "./modules/device/device-session-manager.js";
import { registerHttpDevicesRoute } from "./routes/http-devices.js";
import { registerWsDeviceRoute } from "./routes/ws-device.js";

export function createApp() {
  const env = readRelayEnv();
  const manager = new DeviceSessionManager({
    now: () => Date.now(),
    heartbeatIntervalMs: env.heartbeatIntervalMs,
    heartbeatTimeoutMs: env.heartbeatTimeoutMs
  });

  const app = new Elysia().get("/health", () => ({
    ok: true,
    service: "relay-server",
    protocol: PROTOCOL_NAME,
    version: PROTOCOL_VERSION
  }));

  registerHttpDevicesRoute(app, manager);
  registerWsDeviceRoute(app, manager);

  return app;
}
```

```ts
// apps/relay-server/src/main.ts
import { createApp } from "./app.js";
import { readRelayEnv } from "./config/env.js";

const env = readRelayEnv();

createApp().listen({
  hostname: env.host,
  port: env.port
}, ({ hostname, port }) => {
  console.log(`relay-server listening on http://${hostname}:${port}`);
});
```

- [ ] **Step 4: 运行测试与构建**

运行：

```bash
bun test apps/relay-server/src/routes/http-devices.test.ts apps/relay-server/src/routes/ws-device.test.ts && bun run --cwd apps/relay-server typecheck && bun run --cwd apps/relay-server build
```

预期：
- 通过

- [ ] **Step 5: 补 3 个关键测试再运行**

补充测试项：
- 非 `hello` 的 pre-hello 消息应关闭连接。
- 非法 JSON 或 schema 校验失败应关闭连接。
- `phone_state_update` 不应直接回消息，但 HTTP 查询能看到更新后的 runtime snapshot。
- 新 `hello` 若来自不同 `deviceId`，也必须整体替换当前 singleton，上一个 `deviceId` 的 HTTP 查询回到 synthetic offline。
- 已认证后若 `deviceId` 不匹配当前 session，应关闭连接。
- 已认证后若 `sessionId` 不匹配当前 session，应关闭连接。
- 新 `hello` 替换旧 socket 后，旧 socket 的迟到 `close` 不得清空当前 session。
- 新 `hello` 替换旧 socket 后，旧 socket 的迟到 `heartbeat` / `phone_state_update` 不得污染当前 singleton。

`ws-device.ts` 的最小实现需补齐以下硬规则：

```ts
if (message.type !== "hello" && !data.authenticated) {
  ws.close();
  return;
}

if (message.type === "heartbeat" || message.type === "phone_state_update") {
  if (!manager.matchesCurrentSession(message.deviceId, message.sessionId)) {
    ws.close();
    return;
  }
}

close(ws) {
  manager.closeCurrentSession("WS_CLOSED", ws)
}
```

运行：

```bash
bun test apps/relay-server/src/routes/http-devices.test.ts apps/relay-server/src/routes/ws-device.test.ts
```

预期：
- PASS

- [ ] **Step 6: Commit**

```bash
git add apps/relay-server/src
git commit -m "feat: add relay phase1 ws and status routes"
```

### Task 6: Android shared protocol codec 校验矩阵补强

**Files:**
- Modify: `apps/android/share/src/test/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodecTest.kt`
- Modify: `apps/android/share/src/main/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodec.kt`

- [ ] **Step 1: 先补正向编解码测试**

在 `LocalFrameCodecTest.kt` 追加以下测试：

```kotlin
@Test
fun `encode and decode accepted hello ack frame`() {
    val encoded = codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.HELLO_ACK,
            timestamp = 1_717_171_720L,
            payload = HelloAckPayload(
                accepted = true,
                glassesInfo = GlassesInfo(
                    model = "Rokid Max",
                    appVersion = "0.1.0",
                ),
                capabilities = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
                runtimeState = LocalRuntimeState.READY,
            ),
        ),
    )

    val decoded = codec.decode(encoded)
    @Suppress("UNCHECKED_CAST")
    val header = decoded.header as LocalFrameHeader<HelloAckPayload>

    assertEquals(LocalMessageType.HELLO_ACK, header.type)
    assertEquals(true, header.payload.accepted)
    assertEquals("Rokid Max", header.payload.glassesInfo?.model)
}

@Test
fun `encode and decode rejected hello ack frame`() {
    val encoded = codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.HELLO_ACK,
            timestamp = 1_717_171_721L,
            payload = HelloAckPayload(
                accepted = false,
                error = HelloError(
                    code = "LOCAL_HELLO_REJECTED",
                    message = "unsupported role",
                ),
            ),
        ),
    )

    val decoded = codec.decode(encoded)
    @Suppress("UNCHECKED_CAST")
    val header = decoded.header as LocalFrameHeader<HelloAckPayload>

    assertEquals(false, header.payload.accepted)
    assertEquals("LOCAL_HELLO_REJECTED", header.payload.error?.code)
}

@Test
fun `encode and decode ping and pong frames`() {
    val ping = codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.PING,
            timestamp = 1_717_171_722L,
            payload = PingPayload(seq = 8, nonce = "nonce-8"),
        ),
    )
    val pong = codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.PONG,
            timestamp = 1_717_171_723L,
            payload = PongPayload(seq = 8, nonce = "nonce-8"),
        ),
    )

    assertEquals(LocalMessageType.PING, codec.decode(ping).header.type)
    assertEquals(LocalMessageType.PONG, codec.decode(pong).header.type)
}
```

- [ ] **Step 2: 再补反向校验测试**

在同一测试文件追加以下测试，覆盖阶段一最关键的边界：

```kotlin
@Test(expected = ProtocolCodecException::class)
fun `encode rejects command status without request id`() {
    codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.COMMAND_STATUS,
            timestamp = 1_717_171_724L,
            payload = CommandStatusPayload(
                action = LocalAction.DISPLAY_TEXT,
                status = LocalCommandStatus.EXECUTING,
                statusAt = 1_717_171_724L,
            ),
        ),
    )
}

@Test(expected = ProtocolCodecException::class)
fun `encode rejects chunk data without transfer id`() {
    val body = byteArrayOf(1, 2, 3)

    codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.CHUNK_DATA,
            requestId = "req_123456",
            timestamp = 1_717_171_725L,
            payload = ChunkDataPayload(
                index = 0,
                offset = 0,
                size = body.size,
                chunkChecksum = LocalProtocolChecksums.crc32(body),
            ),
        ),
        body = body,
    )
}

@Test(expected = ProtocolCodecException::class)
fun `encode rejects chunk data without binary body`() {
    codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.CHUNK_DATA,
            requestId = "req_123456",
            transferId = "tx_123456",
            timestamp = 1_717_171_726L,
            payload = ChunkDataPayload(
                index = 0,
                offset = 0,
                size = 3,
                chunkChecksum = "deadbeef",
            ),
        ),
    )
}

@Test(expected = ProtocolCodecException::class)
fun `encode rejects body on non chunk message`() {
    codec.encode(
        header = LocalFrameHeader(
            type = LocalMessageType.PING,
            timestamp = 1_717_171_727L,
            payload = PingPayload(seq = 1, nonce = "nonce-1"),
        ),
        body = byteArrayOf(1),
    )
}
```

- [ ] **Step 3: 运行 share 测试，确认失败或直接通过**

运行：

```bash
cd apps/android && ./gradlew :share:test
```

预期：
- 若直接通过，说明当前 codec 已满足阶段一握手与校验矩阵要求
- 若失败，只修 `DefaultLocalFrameCodec` 的编解码和 validate 逻辑，不扩到 command/image 流程编排

- [ ] **Step 4: 如失败则做最小修复**

只允许改：

```kotlin
// apps/android/share/src/main/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodec.kt
// 仅修正 hello_ack / ping / pong / requestId / transferId / body 校验逻辑
```

不允许：
- 修改协议字段名
- 修改 share module 的模型含义
- 提前引入 command/image 第二阶段行为

- [ ] **Step 5: 重新运行测试**

运行：

```bash
cd apps/android && ./gradlew :share:test
```

预期：
- PASS

- [ ] **Step 6: Commit**

```bash
git add apps/android/share
git commit -m "test: strengthen local frame codec validation matrix"
```

### Task 7: Android 依赖、gateway 基础骨架与测试支撑

**Files:**
- Modify: `apps/android/gradle/libs.versions.toml`
- Modify: `apps/android/phone-app/build.gradle.kts`
- Modify: `apps/android/glasses-app/build.gradle.kts`
- Modify: `apps/android/phone-app/src/main/AndroidManifest.xml`
- Modify: `apps/android/glasses-app/src/main/AndroidManifest.xml`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneRuntimeStore.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/Clock.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLogStore.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayService.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesRuntimeStore.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/Clock.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppController.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesGatewayService.kt`
- Create: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/FakeClock.kt`
- Create: `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/FakeClock.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt`
- Test: `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppControllerTest.kt`

- [ ] **Step 1: 补 Android 依赖版本表**

```toml
# apps/android/gradle/libs.versions.toml
[versions]
kotlinxCoroutines = "1.9.0"
okhttp = "4.12.0"
androidxTestCore = "1.6.1"
robolectric = "4.14.1"

[libraries]
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
```

- [ ] **Step 2: 更新两个 app 的依赖**

```kotlin
// apps/android/phone-app/build.gradle.kts
dependencies {
    implementation(project(":share"))
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
```

```kotlin
// apps/android/glasses-app/build.gradle.kts
dependencies {
    implementation(project(":share"))
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: 写基础骨架的失败测试**

```kotlin
// apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAppControllerTest {
    @Test
    fun `runtime store starts disconnected and offline`() = runTest {
        val store = PhoneRuntimeStore()
        val snapshot = store.snapshot.value

        assertEquals(PhoneSetupState.UNINITIALIZED, snapshot.setupState)
        assertEquals(PhoneRuntimeState.DISCONNECTED, snapshot.runtimeState)
        assertEquals(PhoneUplinkState.OFFLINE, snapshot.uplinkState)
    }

    @Test
    fun `start without required config records startup error and does not run`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val logStore = PhoneLogStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = logStore,
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = null,
                    relayBaseUrl = null,
                    appVersion = "1.0",
                )
            },
        )

        controller.start(targetDeviceAddress = "00:11:22:33:44:55")

        assertEquals(GatewayRunState.ERROR, controller.runState.value)
        assertEquals("PHONE_CONFIG_INCOMPLETE", runtimeStore.snapshot.value.lastErrorCode)
        assertTrue(logStore.entries.value.any { it.message.contains("missing relay config") })
    }
}
```

```kotlin
// apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppControllerTest.kt
package cn.cutemc.rokidmcp.glasses.gateway

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesAppControllerTest {
    @Test
    fun `runtime store starts disconnected`() = runTest {
        val store = GlassesRuntimeStore()
        val snapshot = store.snapshot.value

        assertEquals(GlassesRuntimeState.DISCONNECTED, snapshot.runtimeState)
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest
```

预期：
- 失败
- 报错找不到 gateway 类

- [ ] **Step 5: 写最小基础骨架与测试时钟**

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/Clock.kt
package cn.cutemc.rokidmcp.phone.gateway

interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneRuntimeStore.kt
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PhoneSetupState {
    UNINITIALIZED,
    INITIALIZED,
}

enum class PhoneRuntimeState {
    DISCONNECTED,
    CONNECTING,
    READY,
    BUSY,
    ERROR,
}

enum class PhoneUplinkState {
    OFFLINE,
    CONNECTING,
    ONLINE,
    ERROR,
}

data class PhoneRuntimeSnapshot(
    val setupState: PhoneSetupState = PhoneSetupState.UNINITIALIZED,
    val runtimeState: PhoneRuntimeState = PhoneRuntimeState.DISCONNECTED,
    val uplinkState: PhoneUplinkState = PhoneUplinkState.OFFLINE,
    val activeCommandRequestId: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastUpdatedAt: Long = 0L,
)

class PhoneRuntimeStore {
    private val _snapshot = MutableStateFlow(PhoneRuntimeSnapshot())
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = _snapshot

    internal fun replace(next: PhoneRuntimeSnapshot) {
        _snapshot.value = next
    }
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PhoneGatewayConfig(
    val deviceId: String,
    val authToken: String?,
    val relayBaseUrl: String?,
    val appVersion: String,
)

enum class GatewayRunState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}

class PhoneAppController(
    private val runtimeStore: PhoneRuntimeStore,
    private val logStore: PhoneLogStore,
    private val loadConfig: () -> PhoneGatewayConfig,
) {
    private val _runState = MutableStateFlow(GatewayRunState.IDLE)
    val runState: StateFlow<GatewayRunState> = _runState
    val snapshot: StateFlow<PhoneRuntimeSnapshot> = runtimeStore.snapshot
    val logs: StateFlow<List<PhoneLogEntry>> = logStore.entries

    suspend fun start(targetDeviceAddress: String) {
        val config = loadConfig()
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.CONNECTING,
                uplinkState = PhoneUplinkState.OFFLINE,
            ),
        )

        if (config.authToken.isNullOrBlank() || config.relayBaseUrl.isNullOrBlank()) {
            _runState.value = GatewayRunState.ERROR
            logStore.append("controller", "missing relay config")
            runtimeStore.replace(
                runtimeStore.snapshot.value.copy(
                    runtimeState = PhoneRuntimeState.ERROR,
                    lastErrorCode = "PHONE_CONFIG_INCOMPLETE",
                    lastErrorMessage = "authToken or relayBaseUrl is missing",
                ),
            )
            return
        }

        _runState.value = GatewayRunState.STARTING
        logStore.append("controller", "start requested for $targetDeviceAddress")
    }

    suspend fun stop(reason: String) {
        _runState.value = GatewayRunState.STOPPING
        logStore.append("controller", "stop requested: $reason")
        _runState.value = GatewayRunState.STOPPED
    }

    fun clearLogs() {
        logStore.clear()
    }
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLogStore.kt
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PhoneLogEntry(
    val tag: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

class PhoneLogStore {
    private val _entries = MutableStateFlow<List<PhoneLogEntry>>(emptyList())
    val entries: StateFlow<List<PhoneLogEntry>> = _entries

    fun append(tag: String, message: String) {
        _entries.value = (_entries.value + PhoneLogEntry(tag = tag, message = message)).takeLast(200)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayService.kt
package cn.cutemc.rokidmcp.phone.gateway

import androidx.lifecycle.LifecycleService

class PhoneGatewayService : LifecycleService()
```

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/Clock.kt
package cn.cutemc.rokidmcp.glasses.gateway

interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
```

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesRuntimeStore.kt
package cn.cutemc.rokidmcp.glasses.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GlassesRuntimeState {
    DISCONNECTED,
    CONNECTING,
    READY,
    ERROR,
}

data class GlassesRuntimeSnapshot(
    val runtimeState: GlassesRuntimeState = GlassesRuntimeState.DISCONNECTED,
    val lastUpdatedAt: Long = 0L,
)

class GlassesRuntimeStore {
    private val _snapshot = MutableStateFlow(GlassesRuntimeSnapshot())
    val snapshot: StateFlow<GlassesRuntimeSnapshot> = _snapshot

    internal fun replace(next: GlassesRuntimeSnapshot) {
        _snapshot.value = next
    }
}
```

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppController.kt
package cn.cutemc.rokidmcp.glasses.gateway

class GlassesAppController(
    private val runtimeStore: GlassesRuntimeStore,
) {
    suspend fun start() = Unit
    suspend fun stop(reason: String) = Unit
}
```

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesGatewayService.kt
package cn.cutemc.rokidmcp.glasses.gateway

import androidx.lifecycle.LifecycleService

class GlassesGatewayService : LifecycleService()
```

```kotlin
// apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/FakeClock.kt
package cn.cutemc.rokidmcp.phone.gateway

class FakeClock(private var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}
```

```kotlin
// apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/FakeClock.kt
package cn.cutemc.rokidmcp.glasses.gateway

class FakeClock(private var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}
```

```xml
<!-- apps/android/phone-app/src/main/AndroidManifest.xml -->
<service
    android:name=".gateway.PhoneGatewayService"
    android:exported="false" />
```

```xml
<!-- apps/android/glasses-app/src/main/AndroidManifest.xml -->
<service
    android:name=".gateway.GlassesGatewayService"
    android:exported="false" />
```

- [ ] **Step 6: 运行测试与 Android 编译**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest :phone-app:assembleDebug :glasses-app:assembleDebug
```

预期：
- 通过

- [ ] **Step 7: Commit**

```bash
git add apps/android
git commit -m "feat: scaffold android gateway foundations"
```

### Task 8: Glasses transport 合约、RFCOMM skeleton 与本地会话

**Files:**
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/RfcommServerTransport.kt`
- Create: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesLocalLinkSession.kt`
- Modify: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppController.kt`
- Modify: `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesRuntimeStore.kt`
- Create: `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/FakeRfcommServerTransport.kt`
- Test: `apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesLocalLinkSessionTest.kt`

- [ ] **Step 1: 先写 event-driven session 的失败测试**

```kotlin
package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesLocalLinkSessionTest {
    @Test
    fun `replies hello ack after hello frame transport event`() = runTest {
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            codec = DefaultLocalFrameCodec(),
            clock = FakeClock(1_717_171_800L),
            this,
        )

        session.start()
        transport.emitClientAccepted("00:11:22:33:44:55")
        transport.emitFrame(
            DefaultLocalFrameCodec().encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO,
                    timestamp = 1_717_171_800L,
                    payload = HelloPayload(
                        deviceId = "phone-1",
                        appVersion = "0.1.0",
                        supportedActions = listOf(LocalAction.DISPLAY_TEXT),
                    ),
                ),
            ),
        )

        val sent = transport.sentFrames.single()
        @Suppress("UNCHECKED_CAST")
        val header = DefaultLocalFrameCodec().decode(sent).header as LocalFrameHeader<HelloAckPayload>

        assertEquals(LocalMessageType.HELLO_ACK, header.type)
        assertEquals(true, header.payload.accepted)
        assertEquals(LocalRuntimeState.READY, header.payload.runtimeState)
    }

    @Test
    fun `replies pong after ping frame transport event`() = runTest {
        val transport = FakeRfcommServerTransport()
        val session = GlassesLocalLinkSession(
            transport = transport,
            codec = DefaultLocalFrameCodec(),
            clock = FakeClock(1_717_171_801L),
            this,
        )

        session.start()
        transport.emitFrame(
            DefaultLocalFrameCodec().encode(
                LocalFrameHeader(
                    type = LocalMessageType.PING,
                    timestamp = 1_717_171_801L,
                    payload = PingPayload(seq = 1, nonce = "nonce-1"),
                ),
            ),
        )

        assertEquals(LocalMessageType.PONG, DefaultLocalFrameCodec().decode(transport.sentFrames.single()).header.type)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

运行：

```bash
cd apps/android && ./gradlew :glasses-app:testDebugUnitTest
```

预期：
- 失败
- 报错缺少 transport contract、session `start()` 或 fake transport

- [ ] **Step 3: 写 transport 合约和真实实现骨架**

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/RfcommServerTransport.kt
package cn.cutemc.rokidmcp.glasses.gateway

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class GlassesTransportState {
    LISTENING,
    CLIENT_CONNECTED,
    ERROR,
    CLOSED,
}

sealed interface GlassesTransportEvent {
    data object ListeningStarted : GlassesTransportEvent
    data class ClientAccepted(val deviceAddress: String) : GlassesTransportEvent
    data class FrameBytesReceived(val bytes: ByteArray) : GlassesTransportEvent
    data class Disconnected(val reason: String) : GlassesTransportEvent
    data class AcceptFailed(val error: Throwable) : GlassesTransportEvent
    data class ReadFailed(val error: Throwable) : GlassesTransportEvent
    data class SendFailed(val error: Throwable) : GlassesTransportEvent
}

interface RfcommServerTransport {
    val state: StateFlow<GlassesTransportState>
    val events: SharedFlow<GlassesTransportEvent>

    suspend fun startListening()
    suspend fun disconnectClient(reason: String)
    suspend fun stop(reason: String)
    suspend fun sendFrame(bytes: ByteArray)
}

class AndroidRfcommServerTransport(
    // BluetoothAdapter / UUID / CoroutineScope 等依赖在实现时注入
) : RfcommServerTransport {
    override val state: StateFlow<GlassesTransportState>
        get() = TODO("use MutableStateFlow internally")

    override val events: SharedFlow<GlassesTransportEvent>
        get() = TODO("use MutableSharedFlow internally")

    override suspend fun startListening() {
        TODO("listenUsingRfcommWithServiceRecord + accept loop")
    }

    override suspend fun disconnectClient(reason: String) {
        TODO("close accepted client socket and emit Disconnected")
    }

    override suspend fun stop(reason: String) {
        TODO("close server socket, client socket, and move state to CLOSED")
    }

    override suspend fun sendFrame(bytes: ByteArray) {
        TODO("write bytes to connected client output stream")
    }
}
```

- [ ] **Step 4: 用 fake transport 实现 session 主干**

```kotlin
// apps/android/glasses-app/src/test/java/cn/cutemc/rokidmcp/glasses/gateway/FakeRfcommServerTransport.kt
package cn.cutemc.rokidmcp.glasses.gateway

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeRfcommServerTransport : RfcommServerTransport {
    private val _state = MutableStateFlow(GlassesTransportState.CLOSED)
    override val state: StateFlow<GlassesTransportState> = _state

    private val _events = MutableSharedFlow<GlassesTransportEvent>()
    override val events: SharedFlow<GlassesTransportEvent> = _events

    val sentFrames = mutableListOf<ByteArray>()

    override suspend fun startListening() {
        _state.value = GlassesTransportState.LISTENING
        _events.emit(GlassesTransportEvent.ListeningStarted)
    }

    override suspend fun disconnectClient(reason: String) {
        _events.emit(GlassesTransportEvent.Disconnected(reason))
    }

    override suspend fun stop(reason: String) {
        _state.value = GlassesTransportState.CLOSED
        _events.emit(GlassesTransportEvent.Disconnected(reason))
    }

    override suspend fun sendFrame(bytes: ByteArray) {
        sentFrames += bytes
    }

    suspend fun emitClientAccepted(deviceAddress: String) {
        _state.value = GlassesTransportState.CLIENT_CONNECTED
        _events.emit(GlassesTransportEvent.ClientAccepted(deviceAddress))
    }

    suspend fun emitFrame(bytes: ByteArray) {
        _events.emit(GlassesTransportEvent.FrameBytesReceived(bytes))
    }
}
```

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesLocalLinkSession.kt
package cn.cutemc.rokidmcp.glasses.gateway

import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.GlassesInfo
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalRuntimeState
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import cn.cutemc.rokidmcp.share.protocol.PongPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GlassesLocalLinkSession(
    private val transport: RfcommServerTransport,
    private val codec: LocalFrameCodec = DefaultLocalFrameCodec(),
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            transport.events.collect { event ->
                if (event is GlassesTransportEvent.FrameBytesReceived) {
                    handleInboundFrame(event.bytes)
                }
            }
        }
    }

    private suspend fun handleInboundFrame(bytes: ByteArray) {
        val decoded = codec.decode(bytes)
        when (decoded.header.type) {
            LocalMessageType.HELLO -> replyHelloAck(decoded.header.payload as HelloPayload)
            LocalMessageType.PING -> replyPong(decoded.header.payload as PingPayload)
            else -> Unit
        }
    }

    private suspend fun replyHelloAck(payload: HelloPayload) {
        val ack = LocalFrameHeader(
            type = LocalMessageType.HELLO_ACK,
            timestamp = clock.nowMs(),
            payload = HelloAckPayload(
                accepted = true,
                glassesInfo = GlassesInfo(model = "Rokid", appVersion = "0.1.0"),
                capabilities = payload.supportedActions.ifEmpty {
                    listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO)
                },
                runtimeState = LocalRuntimeState.READY,
            ),
        )
        transport.sendFrame(codec.encode(ack))
    }

    private suspend fun replyPong(payload: PingPayload) {
        val pong = LocalFrameHeader(
            type = LocalMessageType.PONG,
            timestamp = clock.nowMs(),
            payload = PongPayload(seq = payload.seq, nonce = payload.nonce),
        )
        transport.sendFrame(codec.encode(pong))
    }
}
```

- [ ] **Step 5: 让 controller 成为唯一 store 写入者**

修改规则：
- `GlassesAppController` 监听 transport/session 事实后更新 `GlassesRuntimeStore`
- `LISTENING` 映射为 `CONNECTING`
- `hello_ack(accepted=true)` 完成后映射为 `READY`
- transport/session 失败映射为 `ERROR` 或 `DISCONNECTED`

最小实现示例：

```kotlin
// apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesAppController.kt
package cn.cutemc.rokidmcp.glasses.gateway

class GlassesAppController(
    private val runtimeStore: GlassesRuntimeStore,
) {
    fun onListening(nowMs: Long) {
        runtimeStore.replace(
            GlassesRuntimeSnapshot(
                runtimeState = GlassesRuntimeState.CONNECTING,
                lastUpdatedAt = nowMs,
            ),
        )
    }

    fun onSessionReady(nowMs: Long) {
        runtimeStore.replace(
            GlassesRuntimeSnapshot(
                runtimeState = GlassesRuntimeState.READY,
                lastUpdatedAt = nowMs,
            ),
        )
    }

    fun onSessionFailed(nowMs: Long) {
        runtimeStore.replace(
            GlassesRuntimeSnapshot(
                runtimeState = GlassesRuntimeState.ERROR,
                lastUpdatedAt = nowMs,
            ),
        )
    }

    suspend fun start() = Unit
    suspend fun stop(reason: String) = Unit
}
```

- [ ] **Step 6: 运行 JVM 测试**

运行：

```bash
cd apps/android && ./gradlew :glasses-app:testDebugUnitTest
```

预期：
- PASS

- [ ] **Step 7: 记录阶段边界（不做真机 RFCOMM 验收）**

说明：
- 阶段一仅要求 `RfcommServerTransport` 接口、事件流和 session 协作在 JVM fake transport 下可验证。
- 本阶段不把真机 `BluetoothServerSocket.accept()` 联调纳入必做项，不因缺设备阻塞交付。

执行：
- 无额外命令；在任务记录中标注“真实链路验证留到后续专项”。

预期：
- 本任务完成标准仍由 `:glasses-app:testDebugUnitTest` 结果判定。

- [ ] **Step 8: Commit**

```bash
git add apps/android/glasses-app
git commit -m "feat: implement glasses rfcomm transport and local session"
```

### Task 8.5: Phone 本地配置与主页面控制台

**Files:**
- Modify: `package.json`
- Create: `apps/android/package.json`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/MainActivity.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfig.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStore.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModel.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsScreen.kt`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLogStore.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStoreTest.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModelTest.kt`

- [ ] **Step 1: 先补 workspace 对齐**

修改根 `package.json`：

```json
{
  "workspaces": [
    "apps/relay-server",
    "apps/android",
    "packages/*"
  ]
}
```

新建最小 shim 文件：

```json
// apps/android/package.json
{
  "name": "@rokid-mcp/android-workspace",
  "private": true
}
```

- [ ] **Step 2: 写 store 的失败测试**

```kotlin
// apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStoreTest.kt
package cn.cutemc.rokidmcp.phone.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalConfigStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `load or create fills missing device id and app version`() {
        val store = PhoneLocalConfigStore(
            preferences = context.getSharedPreferences("phone-config-test-1", Context.MODE_PRIVATE),
            appVersionProvider = { "1.0" },
            randomDeviceIdProvider = { "k4z91m2q" },
        )

        val config = store.loadOrCreate()

        assertEquals("k4z91m2q", config.deviceId)
        assertNull(config.authToken)
        assertNull(config.relayBaseUrl)
        assertEquals("1.0", config.appVersion)
    }

    @Test
    fun `save trims nullable fields to null`() {
        val store = PhoneLocalConfigStore(
            preferences = context.getSharedPreferences("phone-config-test-2", Context.MODE_PRIVATE),
            appVersionProvider = { "1.0" },
            randomDeviceIdProvider = { "k4z91m2q" },
        )

        val config = store.save(
            EditablePhoneLocalConfig(
                deviceId = "abc12345",
                authToken = "   ",
                relayBaseUrl = "   ",
            ),
        )

        assertEquals("abc12345", config.deviceId)
        assertNull(config.authToken)
        assertNull(config.relayBaseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `save rejects invalid device id`() {
        val store = PhoneLocalConfigStore(
            preferences = context.getSharedPreferences("phone-config-test-3", Context.MODE_PRIVATE),
            appVersionProvider = { "1.0" },
            randomDeviceIdProvider = { "k4z91m2q" },
        )

        store.save(
            EditablePhoneLocalConfig(
                deviceId = "bad id with spaces",
                authToken = "token",
                relayBaseUrl = "http://localhost:3000",
            ),
        )
    }

    @Test
    fun `generated default device id stays in eight char lowercase base36 format`() {
        val store = PhoneLocalConfigStore(
            preferences = context.getSharedPreferences("phone-config-test-4", Context.MODE_PRIVATE),
            appVersionProvider = { "1.0" },
            randomDeviceIdProvider = { "1a2b3c4d" },
        )

        val config = store.loadOrCreate()

        assertTrue(config.deviceId.matches(Regex("^[0-9a-z]{8}$")))
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- 失败
- 报错缺少 `PhoneLocalConfigStore`、`PhoneLocalConfig` 或 `EditablePhoneLocalConfig`

- [ ] **Step 4: 写最小配置模型与存储实现**

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfig.kt
package cn.cutemc.rokidmcp.phone.config

data class PhoneLocalConfig(
    val deviceId: String,
    val authToken: String?,
    val relayBaseUrl: String?,
    val appVersion: String,
)

data class EditablePhoneLocalConfig(
    val deviceId: String,
    val authToken: String,
    val relayBaseUrl: String,
)
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStore.kt
package cn.cutemc.rokidmcp.phone.config

import android.content.SharedPreferences
import kotlin.random.Random

private const val KEY_DEVICE_ID = "device_id"
private const val KEY_AUTH_TOKEN = "auth_token"
private const val KEY_RELAY_BASE_URL = "relay_base_url"

class PhoneLocalConfigStore(
    private val preferences: SharedPreferences,
    private val appVersionProvider: () -> String,
    private val randomDeviceIdProvider: () -> String = {
        buildString {
            repeat(8) {
                append(('0'..'9').plus('a'..'z').random(Random))
            }
        }
    },
) {
    companion object {
        val DeviceIdRegex = Regex("^[a-zA-Z0-9._-]{3,64}$")
    }

    fun loadOrCreate(): PhoneLocalConfig {
        val existingDeviceId = preferences.getString(KEY_DEVICE_ID, null)
        val deviceId = if (existingDeviceId.isNullOrBlank()) {
            val generated = randomDeviceIdProvider()
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            generated
        } else {
            existingDeviceId
        }

        return PhoneLocalConfig(
            deviceId = deviceId,
            authToken = preferences.getString(KEY_AUTH_TOKEN, null),
            relayBaseUrl = preferences.getString(KEY_RELAY_BASE_URL, null),
            appVersion = appVersionProvider(),
        )
    }

    fun save(input: EditablePhoneLocalConfig): PhoneLocalConfig {
        val normalizedDeviceId = input.deviceId.trim()
        require(DeviceIdRegex.matches(normalizedDeviceId)) { "Invalid deviceId" }

        val authToken = input.authToken.trim().ifEmpty { null }
        val relayBaseUrl = input.relayBaseUrl.trim().ifEmpty { null }

        preferences.edit()
            .putString(KEY_DEVICE_ID, normalizedDeviceId)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putString(KEY_RELAY_BASE_URL, relayBaseUrl)
            .apply()

        return PhoneLocalConfig(
            deviceId = normalizedDeviceId,
            authToken = authToken,
            relayBaseUrl = relayBaseUrl,
            appVersion = appVersionProvider(),
        )
    }
}
```

- [ ] **Step 5: 写设置页状态的失败测试**

```kotlin
// apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModelTest.kt
package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.config.EditablePhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSettingsViewModelTest {
    @Test
    fun `invalid device id disables save`() {
        val viewModel = PhoneSettingsViewModel(
            initialConfig = PhoneLocalConfig(
                deviceId = "abc12345",
                authToken = null,
                relayBaseUrl = null,
                appVersion = "1.0",
            ),
            saveConfig = { error("not called") },
        )

        viewModel.onDeviceIdChanged("bad id")

        assertFalse(viewModel.uiState.value.canSave)
        assertEquals(true, viewModel.uiState.value.deviceIdError != null)
    }

    @Test
    fun `valid fields save and keep app version read only`() {
        var saved: EditablePhoneLocalConfig? = null
        val viewModel = PhoneSettingsViewModel(
            initialConfig = PhoneLocalConfig(
                deviceId = "abc12345",
                authToken = null,
                relayBaseUrl = null,
                appVersion = "1.0",
            ),
            saveConfig = {
                saved = it
                PhoneLocalConfig(
                    deviceId = it.deviceId,
                    authToken = it.authToken.ifBlank { null },
                    relayBaseUrl = it.relayBaseUrl.ifBlank { null },
                    appVersion = "1.0",
                )
            },
        )

        viewModel.onDeviceIdChanged("k4z91m2q")
        viewModel.onAuthTokenChanged("token")
        viewModel.onRelayBaseUrlChanged("http://10.0.2.2:3000")
        viewModel.save()

        assertTrue(viewModel.uiState.value.canSave)
        assertEquals("1.0", viewModel.uiState.value.appVersion)
        assertEquals("k4z91m2q", saved?.deviceId)
    }

    @Test
    fun `start and stop buttons reflect gateway run state`() {
        val viewModel = PhoneSettingsViewModel(
            initialConfig = PhoneLocalConfig(
                deviceId = "abc12345",
                authToken = "token",
                relayBaseUrl = "http://10.0.2.2:3000",
                appVersion = "1.0",
            ),
            runStateProvider = { GatewayRunState.RUNNING },
            snapshotProvider = { null },
            logsProvider = { emptyList() },
            saveConfig = { error("not called") },
            onStart = { },
            onStop = { },
            onClearLogs = { },
        )

        assertFalse(viewModel.uiState.value.startEnabled)
        assertTrue(viewModel.uiState.value.stopEnabled)
    }
}
```

- [ ] **Step 6: 写最小 ViewModel 与设置页**

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModel.kt
package cn.cutemc.rokidmcp.phone.ui.settings

import cn.cutemc.rokidmcp.phone.config.EditablePhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfig
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.gateway.GatewayRunState
import cn.cutemc.rokidmcp.phone.gateway.PhoneLogEntry
import cn.cutemc.rokidmcp.phone.gateway.PhoneRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PhoneSettingsUiState(
    val deviceId: String,
    val authToken: String,
    val relayBaseUrl: String,
    val appVersion: String,
    val runState: GatewayRunState,
    val runtimeSnapshot: PhoneRuntimeSnapshot?,
    val logs: List<PhoneLogEntry>,
    val deviceIdError: String? = null,
    val savedMessage: String? = null,
) {
    val canSave: Boolean = deviceIdError == null && deviceId.isNotBlank()
    val startEnabled: Boolean = canSave && (runState == GatewayRunState.IDLE || runState == GatewayRunState.STOPPED || runState == GatewayRunState.ERROR)
    val stopEnabled: Boolean = runState == GatewayRunState.STARTING || runState == GatewayRunState.RUNNING
}

class PhoneSettingsViewModel(
    initialConfig: PhoneLocalConfig,
    runStateProvider: () -> GatewayRunState,
    snapshotProvider: () -> PhoneRuntimeSnapshot?,
    logsProvider: () -> List<PhoneLogEntry>,
    private val saveConfig: (EditablePhoneLocalConfig) -> PhoneLocalConfig,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onClearLogs: () -> Unit,
) {
    private val _uiState = MutableStateFlow(
        PhoneSettingsUiState(
            deviceId = initialConfig.deviceId,
            authToken = initialConfig.authToken.orEmpty(),
            relayBaseUrl = initialConfig.relayBaseUrl.orEmpty(),
            appVersion = initialConfig.appVersion,
            runState = runStateProvider(),
            runtimeSnapshot = snapshotProvider(),
            logs = logsProvider(),
        ),
    )
    val uiState: StateFlow<PhoneSettingsUiState> = _uiState

    fun refreshRuntime(runState: GatewayRunState, snapshot: PhoneRuntimeSnapshot?, logs: List<PhoneLogEntry>) {
        _uiState.value = _uiState.value.copy(
            runState = runState,
            runtimeSnapshot = snapshot,
            logs = logs,
        )
    }

    fun onDeviceIdChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            deviceId = value,
            deviceIdError = if (PhoneLocalConfigStore.DeviceIdRegex.matches(value.trim())) null else "Device ID format is invalid",
            savedMessage = null,
        )
    }

    fun onAuthTokenChanged(value: String) {
        _uiState.value = _uiState.value.copy(authToken = value, savedMessage = null)
    }

    fun onRelayBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(relayBaseUrl = value, savedMessage = null)
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return

        val saved = saveConfig(
            EditablePhoneLocalConfig(
                deviceId = state.deviceId,
                authToken = state.authToken,
                relayBaseUrl = state.relayBaseUrl,
            ),
        )

        _uiState.value = _uiState.value.copy(
            deviceId = saved.deviceId,
            authToken = saved.authToken.orEmpty(),
            relayBaseUrl = saved.relayBaseUrl.orEmpty(),
            appVersion = saved.appVersion,
            deviceIdError = null,
            savedMessage = "Saved",
        )
    }

    fun start() {
        if (_uiState.value.startEnabled) onStart()
    }

    fun stop() {
        if (_uiState.value.stopEnabled) onStop()
    }

    fun clearLogs() {
        onClearLogs()
    }
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsScreen.kt
package cn.cutemc.rokidmcp.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PhoneSettingsScreen(
    viewModel: PhoneSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Phone Console", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = state.deviceId,
            onValueChange = viewModel::onDeviceIdChanged,
            label = { Text("deviceId") },
            isError = state.deviceIdError != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.deviceIdError != null) {
            Text(state.deviceIdError, color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = state.authToken,
            onValueChange = viewModel::onAuthTokenChanged,
            label = { Text("authToken") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.relayBaseUrl,
            onValueChange = viewModel::onRelayBaseUrlChanged,
            label = { Text("relayBaseUrl") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.appVersion,
            onValueChange = {},
            readOnly = true,
            label = { Text("appVersion") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = viewModel::save, enabled = state.canSave) {
            Text("Save")
        }

        Text("Run State: ${state.runState}")
        Text("Setup: ${state.runtimeSnapshot?.setupState ?: "UNINITIALIZED"}")
        Text("Runtime: ${state.runtimeSnapshot?.runtimeState ?: "DISCONNECTED"}")
        Text("Uplink: ${state.runtimeSnapshot?.uplinkState ?: "OFFLINE"}")
        Text("Last Error Code: ${state.runtimeSnapshot?.lastErrorCode ?: "-"}")
        Text("Last Error Message: ${state.runtimeSnapshot?.lastErrorMessage ?: "-"}")

        Button(onClick = viewModel::start, enabled = state.startEnabled) {
            Text("Start")
        }

        Button(onClick = viewModel::stop, enabled = state.stopEnabled) {
            Text("Stop")
        }

        Button(onClick = viewModel::clearLogs) {
            Text("Clear Logs")
        }

        Text("Logs", style = MaterialTheme.typography.titleMedium)
        state.logs.forEach { entry ->
            Text("[${entry.tag}] ${entry.message}")
        }

        if (state.savedMessage != null) {
            Text(state.savedMessage)
        }
    }
}
```

- [ ] **Step 7: 用设置页替换主页面**

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/MainActivity.kt
package cn.cutemc.rokidmcp.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStore
import cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsScreen
import cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsViewModel
import cn.cutemc.rokidmcp.phone.ui.theme.RokidMCPPhoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = PhoneLocalConfigStore(
            preferences = getSharedPreferences("phone-local-config", MODE_PRIVATE),
            appVersionProvider = { BuildConfig.VERSION_NAME },
        )
        val controller = PhoneAppController(
            runtimeStore = PhoneRuntimeStore(),
            logStore = cn.cutemc.rokidmcp.phone.gateway.PhoneLogStore(),
            loadConfig = {
                val config = store.loadOrCreate()
                cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfig(
                    deviceId = config.deviceId,
                    authToken = config.authToken,
                    relayBaseUrl = config.relayBaseUrl,
                    appVersion = config.appVersion,
                )
            },
        )

        val viewModel = PhoneSettingsViewModel(
            initialConfig = store.loadOrCreate(),
            runStateProvider = { controller.runState.value },
            snapshotProvider = { controller.snapshot.value },
            logsProvider = { controller.logs.value },
            saveConfig = store::save,
            onStart = { },
            onStop = { },
            onClearLogs = { controller.clearLogs() },
        )

        setContent {
            RokidMCPPhoneTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    PhoneSettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
```

- [ ] **Step 8: 运行测试与编译**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug
```

预期：
- 通过
- 主页面显示四个设置项、启动/停止按钮、状态指示和日志区域
- 首次启动时自动生成合法默认 `deviceId`
- 非法 `deviceId` 时保存按钮不可用

- [ ] **Step 9: Commit**

```bash
git add package.json apps/android/package.json apps/android/phone-app
git commit -m "feat: add phone local config and settings screen"
```

### Task 9: Phone transport、本地会话、保活与状态映射

**Files:**
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RfcommClientTransport.kt`
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLocalLinkSession.kt`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneRuntimeStore.kt`
- Create: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/FakeRfcommClientTransport.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLocalLinkSessionTest.kt`

冻结要求：
- `PhoneLocalLinkSession` 发 hello 所需的 `deviceId`、`appVersion`、`supportedActions` 必须通过配置注入，不得硬编码在 session 内。
- `PhoneAppController` 负责把本地配置装配进 local session。

- [ ] **Step 1: 写失败测试，先覆盖握手主链路**

```kotlin
package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.GlassesInfo
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.LocalRuntimeState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalLinkSessionTest {
    @Test
    fun `sends hello when transport becomes connected`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            codec = DefaultLocalFrameCodec(),
            clock = FakeClock(1_717_171_900L),
            helloConfig = PhoneLocalHelloConfig(
                deviceId = "abc12345",
                appVersion = "1.0",
                supportedActions = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
            ),
            this,
        )

        session.start()
        transport.emitConnected("00:11:22:33:44:55")

        assertTrue(transport.sentFrames.isNotEmpty())
        assertEquals(PhoneSessionState.HANDSHAKING, session.state.value)
    }

    @Test
    fun `enters ready after accepted hello ack`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            codec = DefaultLocalFrameCodec(),
            clock = FakeClock(1_717_171_901L),
            helloConfig = PhoneLocalHelloConfig(
                deviceId = "abc12345",
                appVersion = "1.0",
                supportedActions = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
            ),
            this,
        )

        session.start()
        transport.emitConnected("00:11:22:33:44:55")
        transport.emitFrame(
            DefaultLocalFrameCodec().encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_171_902L,
                    payload = HelloAckPayload(
                        accepted = true,
                        glassesInfo = GlassesInfo(model = "Rokid", appVersion = "0.1.0"),
                        capabilities = listOf(LocalAction.DISPLAY_TEXT),
                        runtimeState = LocalRuntimeState.READY,
                    ),
                ),
            ),
        )

        assertEquals(PhoneSessionState.READY, session.state.value)
    }

    @Test
    fun `emits hello rejected event when accepted is false`() = runTest {
        val transport = FakeRfcommClientTransport()
        val session = PhoneLocalLinkSession(
            transport = transport,
            codec = DefaultLocalFrameCodec(),
            clock = FakeClock(1_717_171_903L),
            helloConfig = PhoneLocalHelloConfig(
                deviceId = "abc12345",
                appVersion = "1.0",
                supportedActions = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
            ),
            this,
        )

        session.start()
        transport.emitConnected("00:11:22:33:44:55")
        transport.emitFrame(
            DefaultLocalFrameCodec().encode(
                LocalFrameHeader(
                    type = LocalMessageType.HELLO_ACK,
                    timestamp = 1_717_171_904L,
                    payload = HelloAckPayload(
                        accepted = false,
                        error = cn.cutemc.rokidmcp.share.protocol.HelloError(
                            code = "LOCAL_HELLO_REJECTED",
                            message = "bad role",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(PhoneSessionState.ERROR, session.state.value)
    }
}
```

- [ ] **Step 2: 再补保活测试**

追加两项测试：

```kotlin
@Test
fun `emits pong received without mutating runtime store directly`() = runTest {
    val transport = FakeRfcommClientTransport()
    val session = PhoneLocalLinkSession(
        transport = transport,
        codec = DefaultLocalFrameCodec(),
        clock = FakeClock(1_717_171_905L),
        helloConfig = PhoneLocalHelloConfig(
            deviceId = "abc12345",
            appVersion = "1.0",
            supportedActions = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
        ),
        this,
    )

    session.start()
    transport.emitFrame(
        DefaultLocalFrameCodec().encode(
            LocalFrameHeader(
                type = LocalMessageType.PONG,
                timestamp = 1_717_171_906L,
                payload = cn.cutemc.rokidmcp.share.protocol.PongPayload(seq = 3, nonce = "nonce-3"),
            ),
        ),
    )

    val event = session.lastEventForTest()
    assertEquals(PhoneSessionEvent.PongReceived(seq = 3), event)
}

@Test
fun `hello ack timeout emits session failed`() = runTest {
    val clock = FakeClock(1_717_171_907L)
    val transport = FakeRfcommClientTransport()
    val session = PhoneLocalLinkSession(
        transport = transport,
        codec = DefaultLocalFrameCodec(),
        clock = clock,
        helloConfig = PhoneLocalHelloConfig(
            deviceId = "abc12345",
            appVersion = "1.0",
            supportedActions = listOf(LocalAction.DISPLAY_TEXT, LocalAction.CAPTURE_PHOTO),
        ),
        this,
    )

    session.start()
    transport.emitConnected("00:11:22:33:44:55")
    clock.advanceBy(5_001)
    session.tick()

    assertEquals(PhoneSessionState.ERROR, session.state.value)
}
```

- [ ] **Step 3: 运行测试，确认失败**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- 失败
- 报错缺少 transport contract、session `start()`、`tick()` 或 fake transport

- [ ] **Step 4: 写 transport 合约和真实实现骨架**

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RfcommClientTransport.kt
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class BluetoothTransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

sealed interface TransportEvent {
    data class Connected(val deviceAddress: String) : TransportEvent
    data class Disconnected(val reason: String) : TransportEvent
    data class FrameBytesReceived(val bytes: ByteArray) : TransportEvent
    data class ReadFailed(val error: Throwable) : TransportEvent
    data class SendFailed(val error: Throwable) : TransportEvent
}

interface RfcommClientTransport {
    val state: StateFlow<BluetoothTransportState>
    val events: SharedFlow<TransportEvent>

    suspend fun connect(deviceAddress: String)
    suspend fun disconnect(reason: String)
    suspend fun sendFrame(bytes: ByteArray)
}

class AndroidRfcommClientTransport(
    // BluetoothAdapter / UUID / CoroutineScope 等依赖在实现时注入
) : RfcommClientTransport {
    override val state: StateFlow<BluetoothTransportState>
        get() = TODO("use MutableStateFlow internally")

    override val events: SharedFlow<TransportEvent>
        get() = TODO("use MutableSharedFlow internally")

    override suspend fun connect(deviceAddress: String) {
        TODO("createRfcommSocketToServiceRecord + connect + read loop")
    }

    override suspend fun disconnect(reason: String) {
        TODO("close socket and emit Disconnected")
    }

    override suspend fun sendFrame(bytes: ByteArray) {
        TODO("write bytes to output stream")
    }
}
```

- [ ] **Step 5: 用 fake transport 实现握手与保活主干**

```kotlin
// apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/FakeRfcommClientTransport.kt
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeRfcommClientTransport : RfcommClientTransport {
    private val _state = MutableStateFlow(BluetoothTransportState.DISCONNECTED)
    override val state: StateFlow<BluetoothTransportState> = _state

    private val _events = MutableSharedFlow<TransportEvent>()
    override val events: SharedFlow<TransportEvent> = _events

    val sentFrames = mutableListOf<ByteArray>()

    override suspend fun connect(deviceAddress: String) {
        _state.value = BluetoothTransportState.CONNECTING
    }

    override suspend fun disconnect(reason: String) {
        _state.value = BluetoothTransportState.DISCONNECTED
        _events.emit(TransportEvent.Disconnected(reason))
    }

    override suspend fun sendFrame(bytes: ByteArray) {
        sentFrames += bytes
    }

    suspend fun emitConnected(deviceAddress: String) {
        _state.value = BluetoothTransportState.CONNECTED
        _events.emit(TransportEvent.Connected(deviceAddress))
    }

    suspend fun emitFrame(bytes: ByteArray) {
        _events.emit(TransportEvent.FrameBytesReceived(bytes))
    }
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLocalLinkSession.kt
package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.HelloAckPayload
import cn.cutemc.rokidmcp.share.protocol.HelloError
import cn.cutemc.rokidmcp.share.protocol.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.LocalAction
import cn.cutemc.rokidmcp.share.protocol.LocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.PingPayload
import cn.cutemc.rokidmcp.share.protocol.PongPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class PhoneSessionState {
    IDLE,
    HANDSHAKING,
    READY,
    ERROR,
    CLOSED,
}

sealed interface PhoneSessionEvent {
    data class HelloAccepted(val payload: HelloAckPayload) : PhoneSessionEvent
    data class HelloRejected(val code: String, val message: String) : PhoneSessionEvent
    data class PongReceived(val seq: Long) : PhoneSessionEvent
    data class SessionFailed(val code: String, val cause: Throwable? = null) : PhoneSessionEvent
    data class SessionClosed(val reason: String) : PhoneSessionEvent
}

class PhoneLocalLinkSession(
    private val transport: RfcommClientTransport,
    private val codec: LocalFrameCodec = DefaultLocalFrameCodec(),
    private val clock: Clock,
    private val helloConfig: PhoneLocalHelloConfig,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PhoneSessionState.IDLE)
    val state: StateFlow<PhoneSessionState> = _state

    private val _events = MutableSharedFlow<PhoneSessionEvent>()
    val events: SharedFlow<PhoneSessionEvent> = _events

    private var handshakeStartedAt: Long? = null
    private var lastSeenAt: Long = 0L
    private var nextPingSeq = 0L
    private var lastEventForTest: PhoneSessionEvent? = null

    fun start() {
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> sendHello()
                    is TransportEvent.FrameBytesReceived -> handleInboundFrame(event.bytes)
                    is TransportEvent.Disconnected -> fail("BLUETOOTH_DISCONNECTED")
                    is TransportEvent.ReadFailed -> fail("BLUETOOTH_READ_FAILED", event.error)
                    is TransportEvent.SendFailed -> fail("BLUETOOTH_SEND_FAILED", event.error)
                }
            }
        }
    }

    suspend fun tick() {
        val now = clock.nowMs()
        val startedAt = handshakeStartedAt
        if (_state.value == PhoneSessionState.HANDSHAKING && startedAt != null && now - startedAt > 5_000) {
            fail("BLUETOOTH_HELLO_TIMEOUT")
            return
        }
        if (_state.value == PhoneSessionState.READY && now - lastSeenAt >= 5_000) {
            val ping = LocalFrameHeader(
                type = LocalMessageType.PING,
                timestamp = now,
                payload = PingPayload(seq = nextPingSeq++, nonce = "nonce-$now"),
            )
            transport.sendFrame(codec.encode(ping))
        }
    }

    fun lastEventForTest(): PhoneSessionEvent? = lastEventForTest

    private suspend fun sendHello() {
        _state.value = PhoneSessionState.HANDSHAKING
        handshakeStartedAt = clock.nowMs()
        val hello = LocalFrameHeader(
            type = LocalMessageType.HELLO,
            timestamp = clock.nowMs(),
            payload = HelloPayload(
                deviceId = helloConfig.deviceId,
                appVersion = helloConfig.appVersion,
                supportedActions = helloConfig.supportedActions,
            ),
        )
        transport.sendFrame(codec.encode(hello))
    }

    private suspend fun handleInboundFrame(bytes: ByteArray) {
        val decoded = codec.decode(bytes)
        lastSeenAt = clock.nowMs()

        when (decoded.header.type) {
            LocalMessageType.HELLO_ACK -> {
                val payload = decoded.header.payload as HelloAckPayload
                if (payload.accepted) {
                    _state.value = PhoneSessionState.READY
                    val event = PhoneSessionEvent.HelloAccepted(payload)
                    lastEventForTest = event
                    _events.emit(event)
                } else {
                    _state.value = PhoneSessionState.ERROR
                    val error = payload.error ?: HelloError(
                        code = "LOCAL_HELLO_REJECTED",
                        message = "unknown reject",
                    )
                    val event = PhoneSessionEvent.HelloRejected(error.code, error.message)
                    lastEventForTest = event
                    _events.emit(event)
                }
            }
            LocalMessageType.PONG -> {
                val payload = decoded.header.payload as PongPayload
                val event = PhoneSessionEvent.PongReceived(payload.seq)
                lastEventForTest = event
                _events.emit(event)
            }
            else -> Unit
        }
    }

    private suspend fun fail(code: String, cause: Throwable? = null) {
        _state.value = PhoneSessionState.ERROR
        val event = PhoneSessionEvent.SessionFailed(code, cause)
        lastEventForTest = event
        _events.emit(event)
    }
}

data class PhoneLocalHelloConfig(
    val deviceId: String,
    val appVersion: String,
    val supportedActions: List<LocalAction>,
)
```

- [ ] **Step 6: controller 负责唯一写 store，并实现状态映射**

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt
package cn.cutemc.rokidmcp.phone.gateway

class PhoneAppController(
    private val runtimeStore: PhoneRuntimeStore,
    private val logStore: PhoneLogStore,
    private val loadConfig: () -> PhoneGatewayConfig,
) {
    fun onRfcommConnecting(nowMs: Long) {
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.CONNECTING,
                uplinkState = PhoneUplinkState.OFFLINE,
                lastUpdatedAt = nowMs,
            ),
        )
    }

    fun onLocalHelloAccepted(nowMs: Long) {
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.READY,
                uplinkState = PhoneUplinkState.OFFLINE,
                lastUpdatedAt = nowMs,
            ),
        )
    }

    fun onLocalSessionFailed(code: String, message: String, nowMs: Long) {
        runtimeStore.replace(
            PhoneRuntimeSnapshot(
                setupState = PhoneSetupState.INITIALIZED,
                runtimeState = PhoneRuntimeState.DISCONNECTED,
                uplinkState = PhoneUplinkState.OFFLINE,
                lastErrorCode = code,
                lastErrorMessage = message,
                lastUpdatedAt = nowMs,
            ),
        )
    }

    suspend fun start(targetDeviceAddress: String) {
        val config = loadConfig()
        logStore.append("controller", "starting local link for ${config.deviceId}")
        // 后续 task 中在这里装配 PhoneLocalHelloConfig 并 connect RFCOMM transport
    }
    suspend fun stop(reason: String) = Unit
}
```

映射规则必须满足：
- transport `CONNECTING` -> `runtimeState = CONNECTING`
- `hello / hello_ack` 成功 -> `runtimeState = READY`
- `hello_ack.accepted = false` / `BLUETOOTH_HELLO_TIMEOUT` / `BLUETOOTH_PONG_TIMEOUT` -> `runtimeState = DISCONNECTED`
- `PongReceived` 只刷新 session freshness，不直接写 store

- [ ] **Step 7: 运行测试**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- PASS

- [ ] **Step 8: 记录阶段边界（不做真机 RFCOMM 验收）**

说明：
- 阶段一仅要求 `RfcommClientTransport` 接口、事件流和 `PhoneLocalLinkSession` 在 fake transport + loopback 下稳定通过。
- 本阶段不把真机 RFCOMM 建链与保活联调纳入必做项，不因缺设备阻塞交付。

执行：
- 无额外命令；在任务记录中标注“真实链路验证留到后续专项”。

预期：
- 本任务完成标准仍由 `:phone-app:testDebugUnitTest` 与 loopback 测试结果判定。

- [ ] **Step 9: Commit**

```bash
git add apps/android/phone-app
git commit -m "feat: implement phone rfcomm session and keepalive mapping"
```

### Task 10: Phone `RelaySessionClient` 与状态上报收口

**Files:**
- Create: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt`
- Modify: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`
- Create: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/FakeRelayWebSocket.kt`
- Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClientTest.kt`

冻结要求：
- `RelaySessionClient` 的 `hello` 所需 `deviceId`、`authToken`、`appVersion`、`relayBaseUrl` 必须来自 `PhoneGatewayConfig`，不得在 client 内硬编码。
- `PhoneAppController` 负责把本地配置装配进 Relay client，并在配置缺失时拒绝启动。

- [ ] **Step 1: 写失败测试**

```kotlin
package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySessionClientTest {
    @Test
    fun `sends heartbeat after hello ack using runtime snapshot`() = runTest {
        val webSocket = FakeRelayWebSocket()
        val runtimeStore = PhoneRuntimeStore()
        val config = PhoneGatewayConfig(
            deviceId = "abc12345",
            authToken = "token",
            relayBaseUrl = "http://10.0.2.2:3000",
            appVersion = "1.0",
        )
        val client = RelaySessionClient(
            webSocket = webSocket,
            runtimeStore = runtimeStore,
            clock = FakeClock(1_717_172_000L),
            config = config,
        )

        client.onConnected()
        client.onTextMessage(
            """
            {
              "version":"1.0",
              "type":"hello_ack",
              "deviceId":"rokid_glasses_01",
              "timestamp":1717172001,
              "payload":{
                "sessionId":"ses_abcdef",
                "serverTime":1717172001,
                "heartbeatIntervalMs":5000,
                "heartbeatTimeoutMs":15000,
                "limits":{
                  "maxPendingCommands":1,
                  "maxImageUploadSizeBytes":10485760,
                  "acceptedImageContentTypes":["image/jpeg"]
                }
              }
            }
            """.trimIndent(),
        )

        client.sendHeartbeat(runtimeStore.snapshot.value)

        assertTrue(webSocket.sentTexts.any { it.contains("\"type\":\"heartbeat\"") })
    }

    @Test
    fun `report if needed ignores lastUpdatedAt only changes`() = runTest {
        val runtimeStore = PhoneRuntimeStore()
        val controller = PhoneAppController(
            runtimeStore = runtimeStore,
            logStore = PhoneLogStore(),
            loadConfig = {
                PhoneGatewayConfig(
                    deviceId = "abc12345",
                    authToken = "token",
                    relayBaseUrl = "http://10.0.2.2:3000",
                    appVersion = "1.0",
                )
            },
        )

        val first = runtimeStore.snapshot.value.copy(lastUpdatedAt = 1)
        val second = runtimeStore.snapshot.value.copy(lastUpdatedAt = 2)

        assertFalse(controller.shouldReportSnapshotForTest(first, second))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- 失败

- [ ] **Step 3: 写最小实现**

```kotlin
// apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/FakeRelayWebSocket.kt
package cn.cutemc.rokidmcp.phone.gateway

class FakeRelayWebSocket : RelayWebSocket {
    val sentTexts = mutableListOf<String>()

    override fun sendText(text: String) {
        sentTexts += text
    }
}
```

```kotlin
// apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt
package cn.cutemc.rokidmcp.phone.gateway

import org.json.JSONObject

interface RelayWebSocket {
    fun sendText(text: String)
}

class RelaySessionClient(
    private val webSocket: RelayWebSocket,
    private val runtimeStore: PhoneRuntimeStore,
    private val clock: Clock,
    private val config: PhoneGatewayConfig,
) {
    private var sessionId: String? = null
    private var heartbeatSeq: Long = 0

    suspend fun onConnected() {
        val snapshot = runtimeStore.snapshot.value
        val authToken = requireNotNull(config.authToken)
        webSocket.sendText(
            JSONObject()
                .put("version", "1.0")
                .put("type", "hello")
                .put("deviceId", config.deviceId)
                .put("timestamp", clock.nowMs())
                .put(
                    "payload",
                    JSONObject()
                        .put("authToken", authToken)
                        .put("appVersion", config.appVersion)
                        .put("phoneInfo", JSONObject())
                        .put("setupState", snapshot.setupState.name)
                        .put("runtimeState", snapshot.runtimeState.name)
                        .put("uplinkState", PhoneUplinkState.CONNECTING.name)
                        .put("capabilities", listOf("display_text", "capture_photo")),
                )
                .toString(),
        )
    }

    suspend fun onTextMessage(text: String) {
        val payload = JSONObject(text).getJSONObject("payload")
        sessionId = payload.getString("sessionId")
    }

    suspend fun sendHeartbeat(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = requireNotNull(sessionId)
        require(
            snapshot.uplinkState == PhoneUplinkState.ONLINE ||
                snapshot.uplinkState == PhoneUplinkState.ERROR,
        ) { "Heartbeat requires ONLINE or ERROR uplink state" }

        webSocket.sendText(
            JSONObject()
                .put("version", "1.0")
                .put("type", "heartbeat")
                .put("deviceId", config.deviceId)
                .put("sessionId", currentSessionId)
                .put("timestamp", clock.nowMs())
                .put(
                    "payload",
                    JSONObject()
                        .put("seq", heartbeatSeq++)
                        .put("runtimeState", snapshot.runtimeState.name)
                        .put("uplinkState", snapshot.uplinkState.name)
                        .put("pendingCommandCount", 0)
                        .put("activeCommandRequestId", snapshot.activeCommandRequestId),
                )
                .toString(),
        )
    }

    suspend fun sendPhoneStateUpdate(snapshot: PhoneRuntimeSnapshot) {
        val currentSessionId = requireNotNull(sessionId)
        webSocket.sendText(
            JSONObject()
                .put("version", "1.0")
                .put("type", "phone_state_update")
                .put("deviceId", config.deviceId)
                .put("sessionId", currentSessionId)
                .put("timestamp", clock.nowMs())
                .put(
                    "payload",
                    JSONObject()
                        .put("setupState", snapshot.setupState.name)
                        .put("runtimeState", snapshot.runtimeState.name)
                        .put("uplinkState", snapshot.uplinkState.name)
                        .put("lastErrorCode", snapshot.lastErrorCode)
                        .put("lastErrorMessage", snapshot.lastErrorMessage)
                        .put("activeCommandRequestId", snapshot.activeCommandRequestId),
                )
                .toString(),
        )
    }
}
```

- [ ] **Step 4: controller 只在对外可见字段变化时发送 `phone_state_update`**

在 `PhoneAppController.kt` 增加字段级比较，而不是直接比较整个 snapshot：

```kotlin
private var lastReportedSnapshot: PhoneRuntimeSnapshot? = null

internal fun shouldReportSnapshotForTest(previous: PhoneRuntimeSnapshot?, next: PhoneRuntimeSnapshot): Boolean {
    if (previous == null) return true

    return previous.setupState != next.setupState ||
        previous.runtimeState != next.runtimeState ||
        previous.uplinkState != next.uplinkState ||
        previous.lastErrorCode != next.lastErrorCode ||
        previous.lastErrorMessage != next.lastErrorMessage ||
        previous.activeCommandRequestId != next.activeCommandRequestId
}

private suspend fun reportIfNeeded(next: PhoneRuntimeSnapshot) {
    if (!shouldReportSnapshotForTest(lastReportedSnapshot, next)) return
    relaySessionClient.sendPhoneStateUpdate(next)
    lastReportedSnapshot = next
}
```

冻结规则：
- `PongReceived` 不触发 `phone_state_update`
- `lastUpdatedAt` 单独变化不触发 `phone_state_update`
- `heartbeat` payload 必须来自 `PhoneRuntimeStore.snapshot`
- Relay hello/heartbeat/state_update 的 `deviceId` 必须来自本地配置，而不是硬编码常量

- [ ] **Step 5: 运行测试**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- PASS

- [ ] **Step 6: 用 JVM fake WebSocket 验证 Relay 心跳主链路**

在 `RelaySessionClientTest.kt` 追加断言，验证发送顺序：
- `hello`
- `hello_ack`（通过 `onTextMessage` 注入）
- `heartbeat`
- 状态变化时 `phone_state_update`

预期：
- `hello_ack.accepted=true` 后才开始 heartbeat
- `heartbeat` payload 来自 `PhoneRuntimeStore.snapshot`
- `phone_state_update` 只在对外字段变化时发送

- [ ] **Step 7: Commit**

```bash
git add apps/android/phone-app
git commit -m "feat: add phone relay heartbeat client"
```

### Task 10.5: Phone-Glasses loopback 集成测试

**Files:**
- Create: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGlassesLoopbackTest.kt`

- [ ] **Step 1: 写失败测试，打通 phone 和 glasses session 的内存对接**

```kotlin
package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.glasses.gateway.FakeClock as GlassesFakeClock
import cn.cutemc.rokidmcp.glasses.gateway.GlassesLocalLinkSession
import cn.cutemc.rokidmcp.glasses.gateway.RfcommServerTransport
import cn.cutemc.rokidmcp.share.protocol.DefaultLocalFrameCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneGlassesLoopbackTest {
    @Test
    fun `phone handshake reaches ready against glasses loopback transport`() = runTest {
        val pair = LoopbackRfcommPair()
        val phoneSession = PhoneLocalLinkSession(
            transport = pair.client,
            codec = DefaultLocalFrameCodec(),
            clock = FakeClock(1_717_172_100L),
            helloConfig = PhoneLocalHelloConfig(
                deviceId = "abc12345",
                appVersion = "1.0",
                supportedActions = listOf(
                    cn.cutemc.rokidmcp.share.protocol.LocalAction.DISPLAY_TEXT,
                    cn.cutemc.rokidmcp.share.protocol.LocalAction.CAPTURE_PHOTO,
                ),
            ),
            this,
        )
        val glassesSession = GlassesLocalLinkSession(
            transport = pair.server,
            codec = DefaultLocalFrameCodec(),
            clock = GlassesFakeClock(1_717_172_100L),
            this,
        )

        phoneSession.start()
        glassesSession.start()
        pair.connect("00:11:22:33:44:55")

        assertEquals(PhoneSessionState.READY, phoneSession.state.value)
    }
}

private class LoopbackRfcommPair {
    val client = LoopbackClientTransport()
    val server = LoopbackServerTransport(client)

    init {
        client.bind(server)
    }

    suspend fun connect(deviceAddress: String) {
        server.emitAccepted(deviceAddress)
        client.emitConnected(deviceAddress)
    }
}

private class LoopbackClientTransport : RfcommClientTransport {
    private val _state = MutableStateFlow(BluetoothTransportState.DISCONNECTED)
    override val state: StateFlow<BluetoothTransportState> = _state

    private val _events = MutableSharedFlow<TransportEvent>()
    override val events: SharedFlow<TransportEvent> = _events

    private lateinit var peer: LoopbackServerTransport

    fun bind(peer: LoopbackServerTransport) {
        this.peer = peer
    }

    override suspend fun connect(deviceAddress: String) = Unit
    override suspend fun disconnect(reason: String) = Unit

    override suspend fun sendFrame(bytes: ByteArray) {
        peer.emitFrame(bytes)
    }

    suspend fun emitConnected(deviceAddress: String) {
        _state.value = BluetoothTransportState.CONNECTED
        _events.emit(TransportEvent.Connected(deviceAddress))
    }

    suspend fun emitFrame(bytes: ByteArray) {
        _events.emit(TransportEvent.FrameBytesReceived(bytes))
    }
}

private class LoopbackServerTransport(
    private val client: LoopbackClientTransport,
) : RfcommServerTransport {
    private val _state = MutableStateFlow(cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState.CLOSED)
    override val state: StateFlow<cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState> = _state

    private val _events = MutableSharedFlow<cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent>()
    override val events: SharedFlow<cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent> = _events

    override suspend fun startListening() = Unit
    override suspend fun disconnectClient(reason: String) = Unit
    override suspend fun stop(reason: String) = Unit

    override suspend fun sendFrame(bytes: ByteArray) {
        client.emitFrame(bytes)
    }

    suspend fun emitAccepted(deviceAddress: String) {
        _state.value = cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportState.CLIENT_CONNECTED
        _events.emit(cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent.ClientAccepted(deviceAddress))
    }

    suspend fun emitFrame(bytes: ByteArray) {
        _events.emit(cn.cutemc.rokidmcp.glasses.gateway.GlassesTransportEvent.FrameBytesReceived(bytes))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- 失败
- 报错 loopback pair 接线缺失，或 phone/glasses session 尚未基于 transport events 正常协作

- [ ] **Step 3: 修正最小接线，让内存 loopback 跑通 hello / hello_ack**

只允许调整：
- `PhoneLocalLinkSession`
- `GlassesLocalLinkSession`
- 上述 loopback test 内部 helper

目标：
- phone transport 发出的 `hello` 能进入 glasses session
- glasses 回的 `hello_ack` 能回到 phone session
- `PhoneSessionState` 达到 `READY`

- [ ] **Step 4: 再补一个 ping / pong loopback 测试**

在同一测试文件追加：

```kotlin
@Test
fun `phone keepalive ping receives pong through loopback`() = runTest {
    val pair = LoopbackRfcommPair()
    val phoneClock = FakeClock(1_717_172_200L)
    val phoneSession = PhoneLocalLinkSession(
        transport = pair.client,
        codec = DefaultLocalFrameCodec(),
        clock = phoneClock,
        helloConfig = PhoneLocalHelloConfig(
            deviceId = "abc12345",
            appVersion = "1.0",
            supportedActions = listOf(
                cn.cutemc.rokidmcp.share.protocol.LocalAction.DISPLAY_TEXT,
                cn.cutemc.rokidmcp.share.protocol.LocalAction.CAPTURE_PHOTO,
            ),
        ),
        this,
    )
    val glassesSession = GlassesLocalLinkSession(
        transport = pair.server,
        codec = DefaultLocalFrameCodec(),
        clock = GlassesFakeClock(1_717_172_200L),
        this,
    )

    phoneSession.start()
    glassesSession.start()
    pair.connect("00:11:22:33:44:55")

    phoneClock.advanceBy(5_001)
    phoneSession.tick()

    assertEquals(PhoneSessionState.READY, phoneSession.state.value)
    assertEquals(PhoneSessionEvent.PongReceived(seq = 0), phoneSession.lastEventForTest())
}
```

- [ ] **Step 5: 运行测试**

运行：

```bash
cd apps/android && ./gradlew :phone-app:testDebugUnitTest
```

预期：
- PASS

- [ ] **Step 6: Commit**

```bash
git add apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGlassesLoopbackTest.kt apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway
git commit -m "test: add phone glasses local link loopback coverage"
```

### Task 11: MCP `rokid.get_device_status`

**Files:**
- Modify: `packages/mcp-server/package.json`
- Modify: `packages/mcp-server/src/index.ts`
- Modify: `packages/mcp-server/src/server.ts`
- Create: `packages/mcp-server/src/config/env.ts`
- Create: `packages/mcp-server/src/lib/errors.ts`
- Create: `packages/mcp-server/src/lib/logger.ts`
- Create: `packages/mcp-server/src/relay/relay-response-validator.ts`
- Create: `packages/mcp-server/src/relay/relay-client.ts`
- Create: `packages/mcp-server/src/mapper/result-mapper.ts`
- Create: `packages/mcp-server/src/tools/get-device-status.ts`
- Test: `packages/mcp-server/src/config/env.test.ts`
- Test: `packages/mcp-server/src/relay/relay-response-validator.test.ts`
- Test: `packages/mcp-server/src/relay/relay-client.test.ts`
- Test: `packages/mcp-server/src/tools/get-device-status.test.ts`

- [ ] **Step 1: 安装依赖**

运行：

```bash
bun add @sinclair/typebox @modelcontextprotocol/sdk --cwd packages/mcp-server && bun install --no-cache
```

预期：
- `packages/mcp-server/package.json` 更新

- [ ] **Step 2: 写 env、validator 和 relay-client 的失败测试**

```ts
// packages/mcp-server/src/config/env.test.ts
import { describe, expect, test } from "bun:test";
import { readMcpEnv } from "./env.js";

describe("readMcpEnv", () => {
  test("throws when RELAY_BASE_URL is missing", () => {
    expect(() => readMcpEnv({ ROKID_DEFAULT_DEVICE_ID: "rokid_glasses_01" }))
      .toThrow("MCP_CONFIG_INVALID");
  });
});
```

```ts
// packages/mcp-server/src/relay/relay-response-validator.test.ts
import { describe, expect, test } from "bun:test";
import { validateGetDeviceStatusResponse } from "./relay-response-validator.js";

describe("validateGetDeviceStatusResponse", () => {
  test("accepts valid response", () => {
    const result = validateGetDeviceStatusResponse({
      ok: true,
      device: {
        deviceId: "rokid_glasses_01",
        connected: false,
        sessionState: "OFFLINE",
        setupState: "UNINITIALIZED",
        runtimeState: "DISCONNECTED",
        uplinkState: "OFFLINE",
        capabilities: [],
        activeCommandRequestId: null,
        lastErrorCode: null,
        lastErrorMessage: null,
        lastSeenAt: null,
        sessionId: null
      },
      timestamp: 1710000000000
    });

    expect(result.ok).toBe(true);
  });
});
```

```ts
// packages/mcp-server/src/relay/relay-client.test.ts
import { afterEach, describe, expect, mock, test } from "bun:test";
import { RelayClient } from "./relay-client.js";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("RelayClient", () => {
  test("passes through relay error code", async () => {
    globalThis.fetch = mock(async () => new Response(JSON.stringify({
      ok: false,
      error: {
        code: "DEVICE_OFFLINE",
        message: "device is offline",
        retryable: true
      },
      timestamp: 1710000000000
    }), {
      status: 409,
      headers: { "content-type": "application/json" }
    })) as typeof fetch;

    const client = new RelayClient("http://relay.test", 1000);

    await expect(client.getDeviceStatus("rokid_glasses_01")).rejects.toThrow("DEVICE_OFFLINE");
  });

  test("maps transport failure to MCP_RELAY_REQUEST_FAILED", async () => {
    globalThis.fetch = mock(async () => {
      throw new Error("socket hang up");
    }) as typeof fetch;

    const client = new RelayClient("http://relay.test", 1000);

    await expect(client.getDeviceStatus("rokid_glasses_01")).rejects.toThrow("MCP_RELAY_REQUEST_FAILED");
  });
});
```

- [ ] **Step 3: 运行测试，确认失败**

运行：

```bash
bun test packages/mcp-server/src/config/env.test.ts packages/mcp-server/src/relay/relay-response-validator.test.ts packages/mcp-server/src/relay/relay-client.test.ts
```

预期：
- 失败

- [ ] **Step 4: 写最小实现**

```ts
// packages/mcp-server/src/config/env.ts
export type McpEnv = {
  relayBaseUrl: string;
  defaultDeviceId: string;
  requestTimeoutMs: number;
  relayApiToken?: string;
};

export function readMcpEnv(env: Record<string, string | undefined> = process.env): McpEnv {
  if (!env.RELAY_BASE_URL || !env.ROKID_DEFAULT_DEVICE_ID) {
    throw new Error("MCP_CONFIG_INVALID");
  }

  return {
    relayBaseUrl: env.RELAY_BASE_URL,
    defaultDeviceId: env.ROKID_DEFAULT_DEVICE_ID,
    requestTimeoutMs: Number(env.MCP_REQUEST_TIMEOUT_MS ?? 10000),
    relayApiToken: env.RELAY_API_TOKEN
  };
}
```

```ts
// packages/mcp-server/src/lib/errors.ts
export class McpAppError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
  }
}
```

```ts
// packages/mcp-server/src/lib/logger.ts
export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

export const consoleLogger: Logger = {
  info(message, meta) {
    console.info(message, meta ?? {});
  },
  error(message, meta) {
    console.error(message, meta ?? {});
  }
};
```

```ts
// packages/mcp-server/src/relay/relay-response-validator.ts
import { Value } from "@sinclair/typebox/value";
import {
  ErrorResponseSchema,
  GetDeviceStatusResponseSchema,
  type GetDeviceStatusResponse
} from "@rokid-mcp/protocol";

export function validateGetDeviceStatusResponse(input: unknown): GetDeviceStatusResponse {
  if (!Value.Check(GetDeviceStatusResponseSchema, input)) {
    throw new Error("MCP_RELAY_RESPONSE_INVALID");
  }
  return input;
}

export function isRelayErrorResponse(input: unknown) {
  return Value.Check(ErrorResponseSchema, input);
}
```

```ts
// packages/mcp-server/src/relay/relay-client.ts
import type { GetDeviceStatusResponse } from "@rokid-mcp/protocol";
import { isRelayErrorResponse, validateGetDeviceStatusResponse } from "./relay-response-validator.js";

export class RelayClient {
  constructor(
    private readonly relayBaseUrl: string,
    private readonly requestTimeoutMs: number,
    private readonly relayApiToken?: string
  ) {}

  async getDeviceStatus(deviceId: string): Promise<GetDeviceStatusResponse> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.requestTimeoutMs);

    try {
      const response = await fetch(`${this.relayBaseUrl}/api/v1/devices/${deviceId}/status`, {
        headers: {
          Accept: "application/json",
          ...(this.relayApiToken ? { Authorization: `Bearer ${this.relayApiToken}` } : {})
        },
        signal: controller.signal
      });

      const json = await response.json();

      if (isRelayErrorResponse(json)) {
        throw new Error(json.error.code);
      }

      return validateGetDeviceStatusResponse(json);
    } catch (error) {
      if (
        error instanceof Error &&
        error.message !== "MCP_RELAY_RESPONSE_INVALID" &&
        error.message !== "DEVICE_OFFLINE" &&
        error.message !== "DEVICE_NOT_INITIALIZED" &&
        error.message !== "DEVICE_BUSY" &&
        error.message !== "SESSION_STALE" &&
        error.message !== "SESSION_ALREADY_CLOSED"
      ) {
        throw new Error("MCP_RELAY_REQUEST_FAILED");
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
}
```

这里的冻结语义是：
- Relay 返回标准 `ErrorResponse` 时，MCP 必须透传 `error.code`。
- 只有 fetch 超时、网络异常、HTTP 连接失败、JSON 解析失败等请求层异常，才映射为 `MCP_RELAY_REQUEST_FAILED`。
- schema 校验失败固定映射为 `MCP_RELAY_RESPONSE_INVALID`。

```ts
// packages/mcp-server/src/mapper/result-mapper.ts
import type { GetDeviceStatusResponse } from "@rokid-mcp/protocol";

export type RokidGetDeviceStatusOutput = GetDeviceStatusResponse;

export class ResultMapper {
  toGetDeviceStatusOutput(response: GetDeviceStatusResponse): RokidGetDeviceStatusOutput {
    return response;
  }
}
```

```ts
// packages/mcp-server/src/tools/get-device-status.ts
import type { GetDeviceStatusResponse } from "@rokid-mcp/protocol";
import { ResultMapper } from "../mapper/result-mapper.js";
import { RelayClient } from "../relay/relay-client.js";

export class GetDeviceStatusTool {
  constructor(
    private readonly defaultDeviceId: string,
    private readonly relayClient: RelayClient,
    private readonly mapper: ResultMapper
  ) {}

  async handle(): Promise<GetDeviceStatusResponse> {
    const response = await this.relayClient.getDeviceStatus(this.defaultDeviceId);
    return this.mapper.toGetDeviceStatusOutput(response);
  }
}
```

- [ ] **Step 5: 改造 `server.ts` 和 `index.ts`**

```ts
// packages/mcp-server/src/server.ts
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { readMcpEnv } from "./config/env.js";
import { ResultMapper } from "./mapper/result-mapper.js";
import { RelayClient } from "./relay/relay-client.js";
import { GetDeviceStatusTool } from "./tools/get-device-status.js";

export function createMcpServer() {
  const env = readMcpEnv();
  const relayClient = new RelayClient(env.relayBaseUrl, env.requestTimeoutMs, env.relayApiToken);
  const mapper = new ResultMapper();
  const tool = new GetDeviceStatusTool(env.defaultDeviceId, relayClient, mapper);

  const server = new Server({
    name: "rokid-mcp-server",
    version: "0.1.0"
  }, {
    capabilities: {
      tools: {}
    }
  });

  server.setRequestHandler("tools/list", async () => ({
    tools: [{
      name: "rokid.get_device_status",
      description: "Return current device status from relay",
      inputSchema: {
        type: "object",
        properties: {}
      }
    }]
  }));

  server.setRequestHandler("tools/call", async (request: { params: { name: string } }) => {
    if (request.params.name !== "rokid.get_device_status") {
      throw new Error("MCP_TOOL_NOT_FOUND");
    }

    const result = await tool.handle();
    return {
      content: [{
        type: "text",
        text: JSON.stringify(result)
      }]
    };
  });

  return {
    server,
    start: async () => {
      const transport = new StdioServerTransport();
      await server.connect(transport);
    }
  };
}
```

```ts
// packages/mcp-server/src/index.ts
export { createMcpServer } from "./server.js";
```

- [ ] **Step 6: 写 tool 测试并运行**

```ts
// packages/mcp-server/src/tools/get-device-status.test.ts
import { describe, expect, test } from "bun:test";
import { GetDeviceStatusTool } from "./get-device-status.js";
import { ResultMapper } from "../mapper/result-mapper.js";

describe("GetDeviceStatusTool", () => {
  test("returns relay status for default device", async () => {
    const tool = new GetDeviceStatusTool(
      "rokid_glasses_01",
      {
        async getDeviceStatus(deviceId: string) {
          return {
            ok: true,
            device: {
              deviceId,
              connected: false,
              sessionState: "OFFLINE",
              setupState: "UNINITIALIZED",
              runtimeState: "DISCONNECTED",
              uplinkState: "OFFLINE",
              capabilities: [],
              activeCommandRequestId: null,
              lastErrorCode: null,
              lastErrorMessage: null,
              lastSeenAt: null,
              sessionId: null
            },
            timestamp: 1710000000000
          };
        }
      } as never,
      new ResultMapper()
    );

    const result = await tool.handle();
    expect(result.device.deviceId).toBe("rokid_glasses_01");
  });
});
```

运行：

```bash
bun test packages/mcp-server/src/config/env.test.ts packages/mcp-server/src/relay/relay-response-validator.test.ts packages/mcp-server/src/relay/relay-client.test.ts packages/mcp-server/src/tools/get-device-status.test.ts && bun run --cwd packages/mcp-server typecheck && bun run --cwd packages/mcp-server build
```

预期：
- 通过

- [ ] **Step 7: Commit**

```bash
git add packages/mcp-server package.json bun.lock
git commit -m "feat: add mcp get-device-status tool"
```

### Task 12: 全链路验证

**Files:**
- Modify: 仅在验证暴露真实问题时修改对应代码

- [ ] **Step 1: 运行 TypeScript 全仓类型检查**

运行：

```bash
bun run typecheck
```

预期：
- 通过

- [ ] **Step 2: 运行 TypeScript 全仓构建**

运行：

```bash
bun run build
```

预期：
- 通过

- [ ] **Step 3: 运行 TypeScript 侧测试**

运行：

```bash
bun test packages/protocol/src && bun test apps/relay-server/src && bun test packages/mcp-server/src
```

预期：
- 通过

- [ ] **Step 4: 运行 Android JVM 测试**

运行：

```bash
cd apps/android && ./gradlew :share:test :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest
```

预期：
- 通过

- [ ] **Step 5: 阶段一最小联调（本机可执行，无真机依赖）**

联调步骤：

```text
1. 启动 Relay：bun run --cwd apps/relay-server start
2. 运行 Android/JVM 侧关键测试：share + phone + glasses + loopback
3. 启动 MCP（stdio）并调用 rokid.get_device_status
4. 校验返回体字段与 Relay GetDeviceStatusResponse 一致
```

预期：
- `requestedDeviceId` 不命中时，Relay 返回合成离线响应而不是 `404`
- MCP 能稳定返回与 Relay DTO 一致的状态结果
- Android 本地链路行为由 JVM fake transport + loopback 覆盖保证，不要求真机握手验收

- [ ] **Step 6: Commit**

```bash
git add apps packages
git commit -m "feat: complete heartbeat phase1 status chain"
```

## 实施顺序冻结

1. Task 1：`packages/protocol` common
2. Task 2：`packages/protocol` relay ws/http
3. Task 3：Relay 基础骨架
4. Task 4：Relay manager 与 singleton stores
5. Task 5：Relay routes 与入口接线
6. Task 6：Android shared protocol codec 校验矩阵补强
7. Task 7：Android 依赖与基础骨架
8. Task 8：Glasses transport、本地会话与 RFCOMM skeleton
9. Task 8.5：Phone 本地配置与主页面控制台
10. Task 9：Phone transport、本地会话、保活与状态映射
11. Task 10：Phone `RelaySessionClient`
12. Task 10.5：Phone-Glasses loopback 集成测试
13. Task 11：MCP `rokid.get_device_status`
14. Task 12：全链路验证

## 风险控制

- 不在第一阶段进入 `command-service`、`image-manager`、`display_text`、`capture_photo` 实现。
- 不在 `Transport` 中理解业务协议。
- 不把 `RfcommClientTransport` / `RfcommServerTransport` 简化成只会 `sendFrame()` 的哑接口。
- 不在 route 中直接改 `lastSeenAt`、`sessionState` 或 runtime snapshot。
- 不使用客户端 `timestamp` 直接驱动 Relay freshness；`lastSeenAt` 与 stale 统一以服务端时钟为准。
- 不让 `PhoneRuntimeStore` 和 `GlassesRuntimeStore` 被 controller 以外的对象写入。
- 不让 `pong` 成功误触发 `phone_state_update`。
- 不在 `sendHeartbeat()` 内硬编码覆盖 snapshot 的 `uplinkState`。
- 不把 Relay 标准 `ErrorResponse` 误映射成 `MCP_RELAY_REQUEST_FAILED`。
- 不跳过 fake transport 和 loopback 覆盖，避免只靠真机联调发现接线问题。
- 阶段一不把真机 RFCOMM 联调作为阻塞门槛；真机链路问题进入后续专项。
- 不把 `requestedDeviceId` 不命中实现成 `404`。
- 不丢掉旧 socket 迟到 `close` 的保护测试。
- 不让 UI 直接操作 Relay client、transport、session；启动停止必须统一走 controller。
- 不把启动中、停止中、日志这些 UI 控制态塞进 `PhoneRuntimeStore`。

## 后续计划问题

- 根 workspace 与 Android 工程的更完整对齐策略另立小任务处理；当前只要求补最小声明，不改变 Android 仍由 Gradle 驱动的事实。

## 自检清单

- Spec 覆盖：Task 1-5 对应 Relay 与 protocol，Task 6-10.5 对应 Android，Task 11 对应 MCP，Task 12 对应第 33 节联调顺序。
- Placeholder 扫描：不得出现 `TBD`、`implement later`、`similar to task`；`AndroidRfcomm*Transport` 中的 `TODO(...)` 属于阶段二占位，允许保留。
- 类型一致性：`SetupState`、`RuntimeState`、`UplinkState`、`DeviceSessionState`、`GetDeviceStatusResponse`、`HelloRejected`、`sendHeartbeat`、`sendPhoneStateUpdate`、`RfcommClientTransport`、`RfcommServerTransport` 命名必须保持一致。
