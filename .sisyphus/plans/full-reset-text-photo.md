# 完整重置：单设备文本 + 照片功能

## TL;DR（摘要）
> **概要**：将 phase1 仅心跳模式替换为干净的单设备端到端架构，实现 `display_text` 和 `capture_photo` 功能在真实硬件上的全链路流转：MCP → relay → phone → glasses。
> **交付物**：
> - 在 `packages/protocol` 中冻结 relay 命令/图片协议
> - 在 `apps/android/share` 中冻结 phone↔glasses 命令/图片协议
> - 新的 relay 命令/图片生命周期模块和路由
> - 新的 MCP `display_text` 和 `capture_photo` 工具
> - 手机桥接器和眼镜端执行器的新实现
> - 脚本化的真实设备验证和明确的从 phase1 功能存根的切换
    > **工作量**：XL（超大）
    > **并行性**：YES - 3 个波次（波次内包含 8 个阶段）
    > **关键路径**（长度 8，非唯一）：1 → 3 → 4 → 6 → 10 → 11 → 13 → 14
    > 等长的替代路径：通过任务 5 代替 4，和/或通过任务 12 代替 11。

## 上下文
### 原始需求
- 审阅 `docs/` 和当前代码。
- 停止使用分阶段捷径思维。
- 为两个真实设备功能提供干净、从零开始的实现方向：显示文本和拍照。
- 将当前 phase1 功能/运行时代码仅作为参考。
- 不要用 TDD 或大型测试代码示例来驱动工作。

### 访谈摘要
- v1 范围仅为**单设备**：一部手机 + 一副眼镜。
- 迁移方式是**并行重建后切换**；旧的 phase1 路径不是长期兼容性目标。
- `capture_photo` 必须从 MCP 返回 **`image.dataBase64` 加上图片元数据**。
- 旧代码可以指导协议/模式，但不能用作文本/照片功能运行时的实现基础。
- 验证必须是明确的且可由代理执行，但不是 TDD 优先。

### Metis 审查（已解决的差距）
- v1 照片格式默认为 **仅 JPEG**。
- 并发性默认为**全局一个活动命令**的单设备栈。
- `display_text` 行为默认为**立即替换当前显示**；无队列，无覆盖。
- 将**命令生命周期**与**图片产物生命周期**分离。
- 添加了产物所有权、超时所有权、终端错误映射和切换门控的防护措施。

## 工作目标
### 核心目标
交付一个干净的、协议优先的、单设备的 Rokid MVP，其中 MCP 可以发出 `display_text` 和 `capture_photo` 命令，relay 拥有命令/图片状态，手机桥接 relay↔glasses，眼镜执行操作，MCP 接收最终结构化的成功/错误结果，而不依赖于旧的 phase1 功能路径。

### 交付物
- Relay 端命令提交/状态/图片上传/图片下载协议和实现。
- Android 共享本地命令/结果/分块协议，仅使用序列化器构建负载。
- 手机运行时桥接器，用于 relay 命令接收、本地转发、活动命令跟踪、图片上传和结果报告。
- 眼镜运行时执行器，用于文本显示和带分块传输的 JPEG 照片捕获。
- MCP 工具 `rokid.display_text` 和 `rokid.capture_photo`。
- 切换布线，保持心跳/状态存活，但移除旧功能运行时的相关性。

### 完成的定义（可验证的条件和命令）
- `bun run typecheck`
- `bun run build`
- `bun test apps/relay-server/src packages/protocol/src packages/mcp-server/src`
- `apps/android/gradlew -p apps/android :share:test :integration-tests:test :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest`
- `bash .sisyphus/verification/run-real-device-text-photo.sh --phone-serial "$PHONE_SERIAL" --glasses-serial "$GLASSES_SERIAL" --relay-base-url "$RELAY_BASE_URL" --mcp-base-url "$MCP_BASE_URL"`

### 必须具备
- 跨 MCP、relay、手机和眼镜共享的单一活动命令状态机。
- 用于 `capture_photo` 的独立图片产物状态机。
- v1 仅 JPEG 捕获协议。
- 从 MCP 返回 Base64 照片，包含 `image.dataBase64` 加上 `image.imageId`、`image.mimeType`、`image.size`、`image.width`、`image.height` 和可选的 `image.sha256`。
- 协议版本、限制和超时的集中常量/配置。
- 序列化器支持的 DTO 处理；功能流程中无硬编码 JSON 负载。
- `apps/relay-server` 仅消费 `packages/protocol` 中定义的 relay HTTP/WS schema、type、状态枚举与错误码；relay 运行时代码不重新定义协议形状。
- 对旧 phase1 代码的明确保留者/仅参考边界。

### 禁止具备（防护措施、AI 反模式、范围边界）
- 无多设备路由或设备选择。
- 无命令队列、重试队列、图库/历史 API、可恢复上传、流媒体预览或通用文件传输平台。
- 不将旧的手机/眼镜功能存根作为运行时基础重用。
- 无命令/图片负载的临时 JSON 字符串。
- 不在 `apps/relay-server` 中本地定义 relay HTTP DTO、WS payload、状态枚举或错误码常量，除非只是直接消费 `packages/protocol` 导出的类型/验证器。
- 无对不支持状态的隐藏回退行为。
- 在自动化检查和真实设备验收通过之前不进行切换。

## 验证策略
> 零人工干预 - 所有验证均由代理执行。
- 测试决策：**测试后/沿途验证**，不是 TDD 优先。
- 框架：Bun `bun:test`、TypeBox 验证、Android JUnit4、Robolectric、Gradle 集成测试、adb 驱动的真实设备脚本。
- QA 策略：每个任务包含快乐路径和失败路径场景。
- 证据：`.sisyphus/evidence/task-{N}-{slug}.{ext}`

## Clean Code 与架构防护栏
- 模块只能承担一种核心职责：`协议适配`、`业务状态推进`、`状态存储`、`副作用执行`、`结果映射` 不得在同一类/文件中混合。
- 依赖方向必须稳定：`route/ws/tool/driver adapter -> application service/use case -> store/port/infra adapter`；业务核心不得反向依赖 transport、UI、session 或 runtime framework 对象。
- DTO/schema/enum/error code/协议常量只能定义在 `packages/protocol` 或 `apps/android/share`；业务模块只允许消费，不允许复制、变体化或匿名内联重复定义。
- 禁止继续扩张无边界命名的 `*Manager`、`*Controller`、`*Util`、`*Helper`；如必须存在，只能作为组合根/装配器，且不承载业务规则。
- 单个函数若同时包含“协议解析 + 状态分支 + 副作用调用 + 错误映射”，必须拆分；优先用小型 mapper、判别联合和结果对象降低分支复杂度。
- 每个新增核心类/模块都要有简短 doc comment：写明其所有权、输入、输出、允许的副作用与禁止承担的职责。

## 架构所有权矩阵
| 关注点 | 所有者 | 说明 |
|---|---|---|
| `requestId` 分配 | Relay | 在命令提交时创建，并在下游/上游传播中保持不变。 |
| `imageId` 分配 | Relay | 在 `capture_photo` 分派前预留；每次捕获请求唯一。 |
| 上传令牌签发/验证 | Relay | 手机是唯一上传者；眼镜不直接与 relay 通信。 |
| 活动命令并发性 | Relay + Glasses 调度器 | Relay 全局拒绝第二个命令；glasses 守卫强制执行运行时排他性。 |
| 传输保活 | Phone↔Relay 会话、Phone↔Glasses 会话 | 传输超时是本地链路/会话关注点，不是 MCP 关注点。 |
| 命令超时 | Relay | Relay 拥有业务命令的终端超时转换。 |
| MCP 轮询超时 | MCP 轮询器 | 仅外部客户端可见超时；不得直接改变 relay 命令状态。 |
| 图片临时文件所有权 | 上传前为 Phone，上传后为 Relay | 手机组装并上传；relay 存储并提供；清理必须在两侧明确进行。 |
| 终端错误代码映射 | Relay → MCP 映射器 | Android/运行时失败首先规范化为 relay 错误代码，然后是 MCP 可见错误。 |
| 校验和权限 | Glasses 生成字节、Phone 验证传输、Relay 持久化元数据、MCP 重新验证下载 | 成功需要最终 MCP 校验和匹配。 |

## 保留模式 vs 仅参考代码
### 安全的保留模式
- `packages/protocol/src/relay/http.ts` 和 `packages/protocol/src/relay/ws.ts` - 协议风格和模式所有权。
- `apps/relay-server/src/modules/device/device-session-manager.ts` - 状态所有者形状和清理规范。
- `packages/mcp-server/src/tools/get-device-status.ts` - MCP 工具构建模式。
- `apps/android/share/src/main/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodec.kt` - 本地帧编解码器和二进制帧基线。

### 仅参考 / 不要在其上构建
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt`
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLocalLinkSession.kt`
- `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt`
- `apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesLocalLinkSession.kt`
- `apps/relay-server/src/modules/device/single-device-session-store.ts`
- `apps/relay-server/src/modules/device/single-device-runtime-store.ts`
- `packages/mcp-server/src/mapper/result-mapper.ts`

## 执行策略
### 并行执行波次
> 目标：每波次 5-8 个任务。每波次 <3 个（除最终波次外）= 拆分不足。
> 将共享依赖提取为 Wave-1 任务以实现最大并行性。

波次 1：协议冻结 + relay 基础（任务 1-5）
- 阶段 1：任务 1（协议合同 - 其他所有任务都依赖于此）
- 阶段 2：任务 2、3（并行 - Android 共享协议 + relay 命令核心）
- 阶段 3：任务 4、5（并行 - relay 命令 API + relay 图片 API）

波次 2：relay 交付适配器 + MCP 工具 + 手机桥接器（任务 6-10）
- 阶段 4：任务 6、7（并行 - relay WS 分派 + MCP relay 客户端/轮询器）
- 阶段 5：任务 8、9、10（并行 - MCP display_text + MCP capture_photo + 手机桥接器）

波次 3：眼镜执行器 + 集成 + 切换（任务 11-14）
- 阶段 6：任务 11、12（并行 - 眼镜显示执行器 + 眼镜捕获执行器）
- 阶段 7：任务 13（端到端集成 + 真实设备验证）
- 阶段 8：任务 14（切换）

### 依赖矩阵（完整，所有任务）
| 任务 | 依赖于 | 阻塞（仅直接） |
|---|---|---|
| 1 | - | 2,3,4,5,7 |
| 2 | 1 | 10,11,12 |
| 3 | 1 | 4,5,6 |
| 4 | 1,3 | 6,7,8,10 |
| 5 | 1,3 | 6,7,9,10,12 |
| 6 | 3,4,5 | 8,9,10,14 |
| 7 | 1,4,5 | 8,9 |
| 8 | 4,6,7 | 13,14 |
| 9 | 5,6,7 | 13,14 |
| 10 | 2,4,5,6 | 11,12,13,14 |
| 11 | 2,10 | 13,14 |
| 12 | 2,5,10 | 13,14 |
| 13 | 8,9,10,11,12 | 14 |
| 14 | 6,8,9,10,11,12,13 | F1-F4 |

### 代理分派摘要（波次 → 任务数 → 类别）
- 波次 1 → 5 个任务 → deep ×4、unspecified-high ×1
- 波次 2 → 5 个任务 → deep ×2、unspecified-high ×3
- 波次 3 → 4 个任务 → unspecified-high ×3、deep ×1

## TODO（任务清单）
> 实现 + 测试 = 一个任务。永不分离。
> 每个任务必须包含：代理配置 + 并行化 + QA 场景。

- [ ] 1. 在 `packages/protocol` 中冻结 relay 命令、图片与 relay↔phone WS 协议

  **做什么**：添加 v1 单设备协议集，用于命令提交/状态/图片处理，并把 relay↔phone 的 websocket 消息协议也作为 `packages/protocol` 的 source of truth 冻结下来。定义 `display_text` 和 `capture_photo` 的 relay HTTP DTO、relay↔phone WS 消息 schema/判别联合（至少覆盖命令下发、`command_ack`、`command_status`、`command_result`、`command_error`、以及图片上传前后需要的关联字段）、命令/图片状态枚举、终端错误代码，以及 MCP、relay、phone 将共享的状态/结果负载。明确要求：relay 运行时和 phone bridge 只能消费 `packages/protocol/src/relay/http.ts` 与 `packages/protocol/src/relay/ws.ts` 中定义的 schema/type，不得在各自模块内重复发明 WS 负载结构。将协议按职责拆开：HTTP 形状只放 `http.ts`，WS 消息只放 `ws.ts`，共享状态/错误码只放 `common/*`。锁定默认值：仅 JPEG 照片、全局一个活动命令、`display_text` 立即替换当前显示。
  **禁止做什么**：不要添加多设备字段、通用文件 API、重试队列、仅 URL 的照片结果，且不要把 relay↔phone WS 消息 shape 散落定义在 `apps/relay-server` 或 `apps/android/phone-app` 中；不要在协议文件里混入流程辅助逻辑或模糊命名的泛型 payload。

  **推荐代理配置**：
  - 类别：`deep` - 原因：此任务冻结了后续每个任务都依赖的跨栈协议。
  - 技能：`[]` - 仓库本地文档和协议模式已足够。
  - 省略：[`/playwright`] - 无浏览器界面。

  **并行化**：可并行：NO | 波次 1（阶段 1）| 阻塞：2,3,4,5,7 | 被阻塞于：无

  **参考资料**（执行者没有访谈上下文 - 请详尽）：
  - 模式：`packages/protocol/src/relay/http.ts` - 现有 relay HTTP 模式风格。
  - 模式：`packages/protocol/src/relay/ws.ts` - relay↔phone websocket 消息的唯一 schema/type 归属位置；沿用现有 TypeBox 联合/消息风格。
  - 模式：`packages/protocol/src/common/states.ts` - 集中枚举组织。
  - 文档：`docs/mcp-interface-schemas.md` - MCP 返回形状期望。
  - 文档：`docs/relay-protocol-schemas.md` - relay 命令/图片字段。
  - 文档：`docs/relay-protocol-sequences.md` - 规范的成功/失败顺序。

  **验收标准**：
  - [ ] `bun test packages/protocol/src` 通过，覆盖 HTTP 提交/轮询/上传/下载 DTO 验证，以及 relay↔phone WS 消息 schema 的有效负载通过与非法消息拒绝。
  - [ ] `bun run typecheck` 通过，不在 `packages/protocol` 外重复协议形状。
  - [ ] `packages/protocol/src/relay/ws.ts` 明确导出 relay→phone 与 phone→relay 的消息 schema/type，至少覆盖命令下发、ACK、运行中状态、终态结果、终态错误及图片关联字段，并可被 relay 与 phone 直接复用。
  - [ ] 协议包含明确的命令状态、图片状态和 relay 可见的错误代码，用于超时、蓝牙不可用、上传失败、校验和不匹配和不支持的操作。
  - [ ] 协议类型命名体现角色与方向，例如 `SubmitCommandRequest`、`PhoneCommandResultMessage`；不存在含糊的 `Data`、`Info`、`CommonPayload` 式命名。

  **QA 场景**：
  ```
  场景：Relay 协议接受有效的 HTTP 与 relay↔phone WS 负载
    工具：Bash
    步骤：运行 `bun test packages/protocol/src --test-name-pattern "relay command|relay image|relay ws"`；捕获输出。
    预期：测试通过，包括有效的提交/状态/结果/图片负载，以及有效的 relay→phone/phone→relay WS 消息；仅 JPEG 捕获元数据。
    证据：.sisyphus/evidence/task-1-relay-contracts.txt

  场景：Relay 协议拒绝无效的操作、图片组合与 WS 消息
    工具：Bash
    步骤：运行相同的协议套件部分，用非 JPEG mime 类型提供 `capture_photo`，在 `display_text` 中存在图片字段，并发送缺少 `requestId`/带未知判别字段的 WS 消息样例。
    预期：验证失败，具有确定性的模式断言；无回退接受；非法 WS 消息不会被视为可消费协议。
    证据：.sisyphus/evidence/task-1-relay-contracts-error.txt
  ```

  **提交**：YES | 消息：`feat(protocol): freeze relay http and ws contracts` | 文件：`packages/protocol/src/common/*`, `packages/protocol/src/relay/*`

- [ ] 2. 在 `apps/android/share` 中冻结 Android 共享 HTTP DTO、WS 协议、本地命令和图片协议

  **做什么**：使 `apps/android/share` 成为 Android 侧所有协议 dataclass / DTO / 枚举 / 常量 的唯一归属层，覆盖三类内容：① relay HTTP DTO（供 phone 侧 relay API 客户端使用）；② relay↔phone WS 协议模型（供 phone 侧 websocket 客户端使用）；③ phone↔glasses 本地命令/结果/分块模型与序列化器。明确职责分离：`packages/protocol` 仍是 TS 侧 relay HTTP/WS 协议 source of truth，而 `apps/android/share` 是 Android 侧对这些协议的镜像/消费层与本地协议归属层。将所有协议常量、限制值、超时、错误码映射所需的 dataclass/enum/value object 统一下沉到 `apps/android/share`，让 `phone-app` 与 `glasses-app` 只保留业务编排与执行逻辑，不再承载协议定义。显式区分 `relay mirror` 与 `phone↔glasses local protocol` 两个命名空间，避免把 `share` 做成共享杂物层。规范化共享运行时枚举，移除临时负载构建，保留 `LocalFrameCodec` 作为保留者基础设施，并将仅 JPEG 照片元数据加上分块传输不变式编纂成文。
  **禁止做什么**：不要在手机/眼镜功能模块中保留重复的运行时枚举、HTTP DTO、WS payload dataclass 或协议常量；不要在网关代码中硬编码 JSON 主体；若非必要，不要让 Android 业务模块落入任何协议定义；不要新增 `ProtocolManager`、`ProtocolUtils`、`CommonModels` 之类无边界容器。

  **推荐代理配置**：
  - 类别：`deep` - 原因：这为两个 Android 应用锁定了本地协议和序列化器规范。
  - 技能：`[]` - 本地 Android 协议模式已在仓库中。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 1（阶段 2，与任务 3 一起）| 阻塞：10,11,12 | 被阻塞于：1

  **参考资料**：
  - 模式：`apps/android/share/src/main/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodec.kt` - 保留者帧实现。
  - 模式：`apps/android/share/src/main/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalProtocolModels.kt` - 本地协议 dataclass/enum 命名空间。
  - 模式：`apps/android/share/src/test/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodecTest.kt` - 编解码器测试风格。
  - 模式：`packages/protocol/src/relay/http.ts` - Android 侧 HTTP DTO 镜像需要对齐的 TS source of truth。
  - 模式：`packages/protocol/src/relay/ws.ts` - Android 侧 WS DTO/enum 镜像需要对齐的 TS source of truth。
  - 文档：`docs/phone-glasses-protocol-schemas.md` - 所需的帧和负载语义。
  - 文档：`docs/phone-glasses-protocol-constants.md` - 超时、大小、分块不变式。
  - 文档：`docs/relay-protocol-schemas.md` - Android 侧 relay HTTP/WS DTO 字段语义。

  **验收标准**：
  - [ ] `apps/android/gradlew -p apps/android :share:test` 通过，覆盖 relay HTTP DTO、relay↔phone WS DTO，以及 `DISPLAY_TEXT`、`CAPTURE_PHOTO`、`COMMAND_ACK`、`COMMAND_RESULT` 和分块帧的序列化器/编解码器。
  - [ ] `apps/android/share` 明确提供 Android 侧统一协议层：HTTP DTO、WS payload dataclass、共享 enum、常量与 value object；`phone-app` / `glasses-app` 不再本地重新声明这些协议形状。
  - [ ] 共享协议为捕获结果公开明确的校验和、`transferId`、`imageId` 和 JPEG 元数据结构，并集中定义相关超时、大小、版本与限制常量。
  - [ ] Android 侧 relay HTTP/WS DTO 与 `packages/protocol` 中的 TS 契约保持字段级一致，不允许无依据漂移。
  - [ ] `phone-app` / `glasses-app` 仅依赖 `share` 暴露的协议模型与 codec；`share` 不反向依赖应用模块，也不承载 session、bridge、executor 等运行时编排逻辑。

  **QA 场景**：
  ```
  场景：`share` 包统一承载 Android 侧 HTTP DTO、WS 协议与本地协议
    工具：Bash
    步骤：运行 `apps/android/gradlew -p apps/android :share:test --tests "*LocalFrameCodec*" --tests "*Relay*Protocol*"`；存储控制台输出。
    预期：HTTP DTO、relay↔phone WS payload、命令/结果/分块往返测试通过，并准确解码原始负载值；协议常量由 `share` 集中导出。
    证据：.sisyphus/evidence/task-2-android-share.txt

  场景：`share` 包拒绝漂移或损坏的协议消息
    工具：Bash
    步骤：运行覆盖 CRC/校验和不匹配、格式错误的分块排序、缺字段 WS payload、以及与 `packages/protocol` 字段不一致的 Android DTO 样例的共享测试子集。
    预期：解码器/DTO 校验引发确定性失败；格式错误或漂移的协议对象永远不会显示为有效负载；业务模块无须自行兜底协议分支。
    证据：.sisyphus/evidence/task-2-android-share-error.txt
  ```

  **提交**：YES | 消息：`feat(android-share): centralize android protocol contracts` | 文件：`apps/android/share/src/main/kotlin/**`, `apps/android/share/src/test/kotlin/**`

- [ ] 3. 构建 relay 单设备命令生命周期基础

  **做什么**：在进入实现前，先深入规范 relay 侧的模块设计、数据流动方向和流动方式，再落地单设备命令生命周期基础。先明确模块边界（例如：命令状态 owner、超时 owner、ID 生成器、存储层、HTTP 适配层、WS 适配层、错误归一化层）以及数据只允许如何流动：HTTP/WS 入口 → 协议验证/适配 → 命令应用服务/状态机 → 存储/计时器/图片协作模块 → 终态结果回传；禁止适配层绕过服务层直接写状态，禁止横向模块隐式共享可变状态。然后再添加集中限制/配置、请求/图片 ID 生成、命令存储、超时管理器和所有权规则。重用 `DeviceSessionManager` 所有权模式，但不要从旧功能存根继续。明确协议边界：`apps/relay-server` 只能消费 `packages/protocol` 中定义的 relay HTTP/WS schema、type、状态枚举与错误码；命令生命周期核心不得在 relay 模块内再次定义 DTO 或协议常量。明确 clean code 要求：命名清晰、控制流易读、职责单一、依赖方向稳定、避免 god-object、避免把协议解析/业务状态变更/副作用执行混在同一类或同一函数中。
  **禁止做什么**：不要泛化到多设备映射、后台重试或长期持久化；不要在 `apps/relay-server` 本地发明协议 shape；不要在未先收敛模块设计和数据流规则的情况下直接堆实现；不要把状态机、存储、计时器、协议适配、错误映射塞进一个大模块。

  **推荐代理配置**：
  - 类别：`deep` - 原因：这是整个栈的核心状态所有者。
  - 技能：`[]` - 仓库本地 relay 模式已足够。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 1（阶段 2，与任务 2 一起）| 阻塞：4,5,6 | 被阻塞于：1

  **参考资料**：
  - 模式：`apps/relay-server/src/modules/device/device-session-manager.ts` - 值得遵循的所有权和清理风格。
  - 模式：`apps/relay-server/src/modules/device/device-session-manager.test.ts` - relay 状态管理器测试风格。
  - 反模式：`apps/relay-server/src/modules/device/single-device-session-store.ts` - 仅参考的单会话存储实现。
  - 模式：`apps/relay-server/src/app.ts` - 适配层与组装入口边界位置。
  - 文档：`docs/relay-server-module-design.md` - 命令服务职责。
  - 文档：`docs/relay-protocol-constants.md` - 状态机指导。

  **验收标准**：
  - [ ] `bun test apps/relay-server/src --test-name-pattern "command service|timeout|single active command"` 通过。
  - [ ] Relay 在一个命令处于活动状态时拒绝第二个命令，并带有定义的错误代码。
  - [ ] 超时所有权是明确的：relay 处理命令超时，不是 MCP 或 Android 运行时代码。
  - [ ] `apps/relay-server` 不本地重定义 relay DTO、WS payload、状态枚举或错误码常量；这些协议类型统一来自 `packages/protocol`。
  - [ ] relay 命令核心在代码结构上明确分离协议适配、应用服务、状态存储、计时器/超时和错误归一化职责；不存在承担多种核心职责的单一 god-object。
  - [ ] 数据流方向在实现中保持单向且可追踪：入口适配层不直接修改底层状态，状态变更统一经命令服务/状态机执行。
  - [ ] 新增模块/类/函数满足 clean code 要求：命名可读、职责单一、函数长度和分支复杂度受控，阅读代码即可看出状态流转与副作用边界。

  **QA 场景**：
  ```
  场景：Relay 基础接受一个命令并跟踪超时所有权
    工具：Bash
    步骤：运行 `bun test apps/relay-server/src --test-name-pattern "single active command|command timeout|command service"`，并保存与命令服务/状态机相关的测试输出。
    预期：测试显示一个活动命令、确定性超时状态和终端错误映射；状态变更通过命令服务/状态机统一发生，而不是由适配层直接写入。
    证据：.sisyphus/evidence/task-3-relay-command-core.txt

  场景：Relay 拒绝重叠的命令提交
    工具：Bash
    步骤：运行覆盖同一单设备运行时的背靠背提交的 relay 测试子集，并检查是否存在适配层绕过服务层直接修改状态的测试失败/断言。
    预期：第二次提交被拒绝，并带有记录的繁忙/并发错误，第一个命令保持不变；模块边界清晰，没有跨层写状态的隐式捷径。
    证据：.sisyphus/evidence/task-3-relay-command-core-error.txt
  ```

  **提交**：YES | 消息：`feat(relay): design clean single-device command core` | 文件：`apps/relay-server/src/modules/command/**`, `apps/relay-server/src/config/**`, `apps/relay-server/src/**/*.test.ts`

- [ ] 4. 实现 relay 命令提交和状态 API

  **做什么**：添加 `POST /api/v1/commands` 和 `GET /api/v1/commands/:requestId` 以及它们所需的命令服务集成。提交必须验证负载、拒绝不支持/重叠的命令、持久化 relay 拥有的命令状态，并为 MCP 轮询公开终端结果/错误。明确职责分离：HTTP route 只负责请求解析、协议验证、调用应用服务、返回标准化响应；所有命令状态变更、并发判断、超时规则和错误归一化必须发生在服务/状态机层，而不是路由层。明确协议边界：命令 API 路由与验证器直接消费 `packages/protocol/src/relay/http.ts` 中的 schema/type，不在 `apps/relay-server` 中重复定义 HTTP 请求/响应 DTO。
  **禁止做什么**：不要在命令记录存在之前返回成功，也不要将图片字节直接嵌入命令状态响应中；不要在 route/service 层复制 HTTP 协议定义；不要把业务状态变更、并发控制或超时处理塞进 HTTP route handler。

  **推荐代理配置**：
  - 类别：`deep` - 原因：HTTP 命令 API 定义了 MCP 消耗的外部 relay 协议。
  - 技能：`[]` - relay 路由模式在仓库中。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 1（阶段 3，与任务 5 一起）| 阻塞：6,7,8,10 | 被阻塞于：1,3

  **参考资料**：
  - 模式：`apps/relay-server/src/routes/http-devices.ts` - 路由和验证风格。
  - 模式：`apps/relay-server/src/app.ts` - 路由注册模式。
  - 模式：`packages/protocol/src/relay/http.ts` - DTO 所有权。
  - 文档：`docs/relay-server-module-design.md` - 命令路由行为。
  - 文档：`docs/relay-protocol-sequences.md` - 预期的 ACK/RUNNING/COMPLETED 流程。

  **验收标准**：
  - [ ] `bun test apps/relay-server/src --test-name-pattern "http commands|command status route"` 通过。
  - [ ] `curl -s -X POST "$RELAY_BASE_URL/api/v1/commands" -H 'content-type: application/json' -d '{"deviceId":"rokid_glasses_01","action":"display_text","payload":{"text":"Hello from MCP","durationMs":3000}}'` 返回 `202` 和一个非空的 `requestId`。
  - [ ] `curl -s "$RELAY_BASE_URL/api/v1/commands/$REQUEST_ID"` 为所有生命周期状态返回有效的 relay 状态形状。
  - [ ] 命令提交/状态路由不在 `apps/relay-server` 本地重定义 HTTP DTO；请求/响应 schema 统一来自 `packages/protocol`。
  - [ ] HTTP route handler 保持适配层职责：不直接修改命令状态，不本地实现并发/超时/错误映射规则，相关行为统一委托给命令服务/状态机。

  **QA 场景**：
  ```
  场景：Relay 命令 API 创建和轮询 display_text 请求
    工具：Bash
    步骤：启动 relay 测试应用，提交上面确切的 `display_text` curl，提取 `requestId`，然后轮询 `/api/v1/commands/$requestId`。
    预期：提交返回 `202`；轮询返回有效的命令记录，带有 `CREATED`/后续状态和原始输入在元数据安全形式中回显；路由仅表现为适配层入口，不承担状态变更逻辑。
    证据：.sisyphus/evidence/task-4-relay-command-api.txt

  场景：Relay 命令 API 拒绝不支持的操作和重复的活动命令
    工具：Bash
    步骤：提交 `{"action":"unknown"}`，然后在第一个命令处于活动状态时背靠背提交两个有效命令。
    预期：不支持的操作返回定义的验证错误；第二个有效命令返回记录的繁忙错误；并发拒绝来自命令服务规则而非 route 内联分支。
    证据：.sisyphus/evidence/task-4-relay-command-api-error.txt
  ```

  **提交**：YES | 消息：`feat(relay): add command submit and status routes` | 文件：`apps/relay-server/src/routes/**`, `apps/relay-server/src/app.ts`, `apps/relay-server/src/**/*.test.ts`

- [ ] 5. 实现 relay 图片管理器和上传/下载路由

  **做什么**：添加 relay 拥有的图片预留、上传令牌验证、元数据持久化、文件存储和 `PUT/GET /api/v1/images/:imageId` 路由。图片生命周期必须与命令完成保持分离：命令成功不可能在图片上传完成和元数据提交之前实现。明确协议边界：图片上传/下载相关 HTTP DTO、元数据 shape 与错误码常量必须直接消费 `packages/protocol`，不在 relay 图片模块本地再定义一套协议模型。将图片能力至少拆成预留、上传令牌校验、元数据持久化、文件存储四类角色；命令服务只能通过图片服务公开的状态/查询接口判断图片是否完成。
  **禁止做什么**：不要让眼镜直接上传到 relay，不要允许一个 `imageId` 有多次上传，也不要在失败后保留孤立的临时文件；不要在 `apps/relay-server` 中复制图片 HTTP 协议定义；不要把 token 校验、文件 IO、metadata 构造、清理逻辑揉进一个大而全的 `ImageManager`。

  **推荐代理配置**：
  - 类别：`deep` - 原因：照片成功依赖于干净的产物生命周期，而不仅仅是命令状态。
  - 技能：`[]` - 仓库本地 relay 模式已足够。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 1（阶段 3，与任务 4 一起）| 阻塞：6,7,9,10,12 | 被阻塞于：1,3

  **参考资料**：
  - 文档：`docs/relay-server-module-design.md` - 图片管理器职责。
  - 文档：`docs/relay-protocol-schemas.md` - 上传/下载 DTO 和元数据。
  - 文档：`docs/relay-protocol-sequences.md` - `capture_photo` 的上传排序。
  - 模式：`apps/relay-server/src/modules/device/device-session-manager.ts` - 清理计时器所有权风格。

  **验收标准**：
  - [ ] `bun test apps/relay-server/src --test-name-pattern "image manager|image upload|image download"` 通过。
  - [ ] 有效的单次使用上传令牌接受一次 JPEG 上传并持久化元数据加校验和。
  - [ ] 失败的上传或校验和不匹配不会留下可上传/开放的临时产物。
  - [ ] 图片上传/下载路由与图片管理器不本地重定义图片 HTTP DTO、元数据 shape 或错误码常量；这些协议类型统一来自 `packages/protocol`。
  - [ ] 存在明确的“上传前临时态 / 上传后持久态”边界，且清理路径只由图片服务统一触发，不散落在路由/回调中。

  **QA 场景**：
  ```
  场景：Relay 图片 API 接受一次 JPEG 上传并提供返回
    工具：Bash
    步骤：运行 relay 图片测试，然后使用 fixture JPEG 字节 `PUT /api/v1/images/$imageId?uploadToken=$uploadToken`，然后 `GET /api/v1/images/$imageId`。
    预期：PUT 成功一次，GET 返回上传的字节，元数据校验和匹配。
    证据：.sisyphus/evidence/task-5-relay-image-api.txt

  场景：Relay 图片 API 拒绝过期令牌或第二次上传尝试
    工具：Bash
    步骤：对第二次 PUT 重用相同的上传令牌，并尝试使用无效令牌的 PUT。
    预期：两个请求都失败，具有确定性的错误代码；文件存储状态保持干净。
    证据：.sisyphus/evidence/task-5-relay-image-api-error.txt
  ```

  **提交**：YES | 消息：`feat(relay): add image manager and upload download routes` | 文件：`apps/relay-server/src/modules/image/**`, `apps/relay-server/src/routes/**`, `apps/relay-server/src/**/*.test.ts`

- [ ] 6. 交付 relay websocket 命令分派和手机结果接收

  **做什么**：扩展设备 websocket 路径，使 relay 可以将命令消息推送到连接的手机，并接收回 `command_ack`、`command_status`、`command_result` 和 `command_error` 消息。将这些消息接入命令/图片服务，保留心跳/状态行为，并保持命令/结果排序确定性。明确职责分离：`ws-device` 只负责连接生命周期、消息解包/验证、调用应用服务、发送标准化下行消息；所有命令状态推进、图片状态协作、错误归一化与终态判定都必须在服务/状态机层执行，而不是写在 websocket handler 里。明确协议边界：`ws-device` 路由、消息适配器与 relay 侧处理逻辑只能消费 `packages/protocol/src/relay/ws.ts` 中定义的 websocket schema/type，不得在 `apps/relay-server` 中重定义消息联合、payload shape 或错误码。
  **禁止做什么**：不要将原始 JSON 解析逻辑混合到业务规则中，也不要让命令完成先于图片上传完成；不要在 relay websocket 代码中本地发明 WS 协议形状；不要把状态推进、错误映射或图片完成判定内联到 websocket route handler 中。

  **推荐代理配置**：
  - 类别：`deep` - 原因：这是 relay 状态和手机执行之间的实时桥梁。
  - 技能：`[]` - 路由、状态和协议模式已在本地存在。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 2（阶段 4，与任务 7 一起）| 阻塞：8,9,10,14 | 被阻塞于：3,4,5

  **参考资料**：
  - 模式：`apps/relay-server/src/routes/ws-device.ts` - 要演进的现有 hello/heartbeat 路由适配器。
  - 模式：`packages/protocol/src/relay/ws.ts` - websocket 消息联合和验证风格。
  - 文档：`docs/relay-server-module-design.md` - ws-device 适配器职责。
  - 文档：`docs/relay-protocol-sequences.md` - 命令确认/结果排序。

  **验收标准**：
  - [ ] `bun test apps/relay-server/src --test-name-pattern "ws device command|command result ingestion"` 通过。
  - [ ] Relay 可以在 `hello_ack` 后将排队命令推送到活动手机会话，并通过 `DISPATCHED_TO_PHONE`、`ACKNOWLEDGED_BY_PHONE`、`RUNNING` 和终端状态移动命令。
  - [ ] Relay 拒绝引用未知或过期 `requestId` 值的结果/错误消息。
  - [ ] `apps/relay-server/src/routes/ws-device.ts` 与相关处理器不本地重定义 WS payload、判别联合、状态枚举或错误码；这些协议类型统一来自 `packages/protocol/src/relay/ws.ts`。
  - [ ] websocket route handler 保持适配层职责：只做连接/消息适配与服务调用，不直接推进命令状态、不内联图片完成规则、不本地实现错误归一化。

  **QA 场景**：
  ```
  场景：Relay websocket 将 display_text 命令分派到模拟手机会话
    工具：Bash
    步骤：运行 relay websocket 集成测试，连接模拟手机客户端、提交 relay 命令、接收推送的 websocket 命令，并回发确认/状态/结果帧。
    预期：Relay 状态转换遵循记录顺序，终端状态变为 `COMPLETED`；状态推进由命令服务统一执行，而不是 websocket handler 直接写状态。
    证据：.sisyphus/evidence/task-6-relay-ws-dispatch.txt

  场景：Relay 忽略过期的 requestId 结果并记录确定性错误
    工具：Bash
    步骤：运行 websocket 测试子集，为未知/过期的 `requestId` 发送 `command_result`。
    预期：Relay 拒绝该帧，保持当前活动命令不变，并记录定义的协议错误路径；错误处理经服务层归一化，而不是 handler 内联散落实现。
    证据：.sisyphus/evidence/task-6-relay-ws-dispatch-error.txt
  ```

  **提交**：YES | 消息：`feat(relay): dispatch commands over device websocket` | 文件：`apps/relay-server/src/routes/ws-device.ts`, `apps/relay-server/src/modules/**`, `apps/relay-server/src/**/*.test.ts`

- [ ] 7. 扩展 MCP relay 客户端、轮询器和结果映射以支持命令工作流

  **做什么**：添加 MCP 端 relay 客户端方法，用于命令提交/状态/图片下载，添加具有显式超时所有权的命令轮询器，并将原始 `JSON.stringify` 结果映射替换为结构化的 MCP 内容生成。映射器必须为 `display_text` 和 `capture_photo` 生成稳定的成功/错误内容。显式拆分 `RelayCommandClient`、`CommandPoller`、`McpResultMapper`；其中 mapper 必须保持纯函数，不访问网络、时钟或环境。明确协议边界：MCP 侧只允许消费 `packages/protocol` 中定义的 relay HTTP/WS schema/type 与其派生验证器，不得在 `packages/mcp-server` 中重新定义 relay DTO、WS payload shape、状态枚举或错误码常量。
  **禁止做什么**：不要直接返回 relay 内部信息，不要保留原始 JSON 字符串 blob 作为唯一的用户可见输出，也不要让 MCP 拥有 relay 命令状态；不要在 `packages/mcp-server` 中本地发明协议 shape；不要让 poller 负责生成用户可见文本，也不要让 mapper 发 HTTP 请求。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：工作本地化但协议敏感。
  - 技能：`[]` - 当前 MCP 包提供了所需的模式。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 2（阶段 4，与任务 6 一起）| 阻塞：8,9 | 被阻塞于：1,4,5

  **参考资料**：
  - 模式：`packages/protocol/src/relay/http.ts` - MCP 必须直接消费的 relay HTTP source of truth。
  - 模式：`packages/protocol/src/relay/ws.ts` - MCP 若需理解 relay WS 终态语义时必须对齐的 source of truth。
  - 模式：`packages/mcp-server/src/relay/relay-client.ts` - 现有 relay 客户端风格。
  - 模式：`packages/mcp-server/src/relay/relay-response-validator.ts` - 要保留的验证方法。
  - 反模式：`packages/mcp-server/src/mapper/result-mapper.ts` - 要替换的仅参考原始 JSON stringify 行为。
  - 文档：`docs/mcp-server-module-design.md` - 轮询器、下载器、映射器模块边界。
  - 文档：`docs/mcp-interface-sequences.md` - MCP 轮询和终端返回行为。

  **验收标准**：
  - [ ] `bun test packages/mcp-server/src --test-name-pattern "relay client|command poller|result mapper"` 通过。
  - [ ] MCP 轮询器在终端 relay 状态停止，并发出具有 MCP 可见上下文的超时错误。
  - [ ] 结构化的 MCP 输出存在于成功和失败两种情况，没有仅 JSON 的文本 blob。
  - [ ] `packages/mcp-server` 不本地重定义 relay DTO / WS payload / 状态枚举 / 错误码常量，协议消费边界清晰地指向 `packages/protocol`。
  - [ ] `packages/mcp-server` 中不存在同时承担“请求 relay + 轮询 + 结果格式化”的单一模块。

  **QA 场景**：
  ```
  场景：MCP 轮询器在终端 relay 完成时停止
    工具：Bash
    步骤：运行 `bun test packages/mcp-server/src --test-name-pattern "command poller"`。
    预期：轮询器提交、轮询、在 `COMPLETED`/`FAILED` 停止，并将 relay 错误映射到稳定的 MCP 输出。
    证据：.sisyphus/evidence/task-7-mcp-core.txt

  场景：MCP 轮询器在 relay 从未达到终端状态时超时
    工具：Bash
    步骤：运行 MCP 轮询器超时测试，使用模拟 relay 永不离开 `RUNNING`。
    预期：MCP 返回记录的超时错误形状并停止轮询。
    证据：.sisyphus/evidence/task-7-mcp-core-error.txt
  ```

  **提交**：YES | 消息：`feat(mcp): add relay polling and result mapping for commands` | 文件：`packages/mcp-server/src/relay/**`, `packages/mcp-server/src/command/**`, `packages/mcp-server/src/mapper/**`, `packages/mcp-server/src/**/*.test.ts`

- [ ] 8. 实现 MCP `rokid.display_text`

  **做什么**：使用新的提交/轮询/结果映射管道添加生产级 `display_text` 工具。工具输入应该最小化和明确（`text`、`durationMs`），提交到 relay，轮询直到终端状态，并在眼镜端完成通过 relay 报告后返回结构化确认。将实现收敛为 `DisplayTextTool -> DisplayTextUseCase`：tool 只做 MCP 输入/输出适配，use case 组合提交、轮询和结果映射。
  **禁止做什么**：不要在 relay 提交成功时短路，也不要将 relay 特定的内部信息作为主要工具响应暴露；不要在 tool handler 内联轮询循环、relay 状态判断和错误文本模板。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：本地化的 MCP 工具工作，使用严格的协议。
  - 技能：`[]` - 现有工具模式已足够。
  - 省略：[`/playwright`] - 无浏览器 UI。

  **并行化**：可并行：YES | 波次 2（阶段 5，与任务 9、10 一起）| 阻塞：13,14 | 被阻塞于：4,6,7

  **参考资料**：
  - 模式：`packages/mcp-server/src/tools/get-device-status.ts` - 保留者工具构建模式。
  - 模式：`packages/mcp-server/src/tools/get-device-status.test.ts` - 工具级测试风格。
  - 文档：`docs/mcp-interface-schemas.md` - `display_text` 输入/输出协议。
  - 文档：`docs/mcp-interface-sequences.md` - 提交/轮询/返回序列。

  **验收标准**：
  - [ ] `bun test packages/mcp-server/src --test-name-pattern "display text tool"` 通过。
  - [ ] 工具提交 relay 命令，轮询到终端成功，并返回确认显示文本/持续时间的结构化内容。
  - [ ] 工具为 relay 拒绝、超时和终端失败返回确定性的错误内容。
  - [ ] 工具文件不直接依赖底层 HTTP 细节；它只依赖 use case 和 MCP 输出模型。

  **QA 场景**：
  ```
  场景：MCP display_text 工具在 relay 完成后返回成功
    工具：Bash
    步骤：针对模拟的 CREATED→RUNNING→COMPLETED relay 响应运行 `bun test packages/mcp-server/src --test-name-pattern "display text tool"`。
    预期：工具输出仅在终端完成后指示显示成功。
    证据：.sisyphus/evidence/task-8-mcp-display-text.txt

  场景：MCP display_text 工具干净地显示终端失败
    工具：Bash
    步骤：使用模拟的 relay `FAILED` 和超时响应运行相同的工具测试子集。
    预期：工具返回定义的 MCP 错误形状，没有误导性的成功文本。
    证据：.sisyphus/evidence/task-8-mcp-display-text-error.txt
  ```

  **提交**：YES | 消息：`feat(mcp): implement display text tool` | 文件：`packages/mcp-server/src/tools/**`, `packages/mcp-server/src/server.ts`, `packages/mcp-server/src/**/*.test.ts`

- [ ] 9. 实现带有 base64 下载管道的 MCP `rokid.capture_photo`

  **做什么**：添加生产级 `capture_photo` 工具，该工具提交命令、轮询 relay 状态、等待 relay 图片状态达到 `UPLOADED`、下载 JPEG 字节、验证校验和/元数据、将字节转换为 base64，并返回记录的 MCP 输出形状：顶层 `requestId`、`action`、`completedAt`，以及嵌套的 `image.imageId`、`image.mimeType`、`image.size`、`image.width`、`image.height`、可选的 `image.sha256` 和 `image.dataBase64`。将照片工具拆为 `CapturePhotoUseCase`、`ImageDownloader`、`DownloadedImageVerifier`、`CapturePhotoResultMapper`；base64 编码作为独立步骤，不嵌在下载器里。
  **禁止做什么**：不要返回仅 URL 的结果，不要跳过校验和验证，不要在图片上传/下载验证完成之前将命令完成视为成功；不要写一个方法同时完成轮询、下载、校验和验证、base64 编码和结果组装。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：本地化的 MCP 工作，具有图片/结果处理复杂性。
  - 技能：`[]` - 仓库本地 MCP 设计已足够。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 2（阶段 5，与任务 8、10 一起）| 阻塞：13,14 | 被阻塞于：5,6,7

  **参考资料**：
  - 文档：`docs/mcp-interface-schemas.md` - 所需的 `capture_photo` MCP 输出形状。
  - 文档：`docs/mcp-interface-sequences.md` - 轮询/下载/返回顺序。
  - 文档：`docs/relay-protocol-sequences.md` - 终端成功前的上传完成。
  - 模式：`packages/mcp-server/src/relay/relay-client.ts` - HTTP 获取/错误模式。

  **验收标准**：
  - [ ] `bun test packages/mcp-server/src --test-name-pattern "capture photo tool|image downloader|image parser"` 通过。
  - [ ] 工具仅在下载校验和与 relay 元数据匹配后返回记录的 `RokidCapturePhotoOutput` 形状。
  - [ ] 工具为上传失败、校验和不匹配、下载失败或 relay 超时返回确定性的错误内容。
  - [ ] 任一步失败都返回失败结果，不生成部分成功 payload 或半填充 `image` 对象。

  **QA 场景**：
  ```
  场景：MCP capture_photo 工具在成功上传/下载后返回 base64 和元数据
    工具：Bash
    步骤：使用模拟的 relay 命令和图片响应运行 `bun test packages/mcp-server/src --test-name-pattern "capture photo tool|image downloader|image parser"`。
    预期：工具输出包含非空的 `image.dataBase64` 加上 `image.imageId`、`image.mimeType`、`image.size`、`image.width`、`image.height` 和可选的 `image.sha256`。
    证据：.sisyphus/evidence/task-9-mcp-capture-photo.txt

  场景：MCP capture_photo 工具拒绝校验和不匹配
    工具：Bash
    步骤：使用校验和与 relay 元数据不同的下载 JPEG 运行相同的 MCP 测试子集。
    预期：工具返回记录的校验和错误，没有成功负载。
    证据：.sisyphus/evidence/task-9-mcp-capture-photo-error.txt
  ```

  **提交**：YES | 消息：`feat(mcp): implement capture photo tool` | 文件：`packages/mcp-server/src/tools/**`, `packages/mcp-server/src/image/**`, `packages/mcp-server/src/server.ts`, `packages/mcp-server/src/**/*.test.ts`

- [ ] 10. 构建手机 relay 命令桥接器和上传感知运行时

  **做什么**：将仅心跳的手机运行时路径替换为桥接器，该桥接器消耗 relay websocket 命令、将本地命令转发到眼镜、跟踪单个活动命令、组装从眼镜接收的照片字节、上传到 relay，并仅在上传成功后发送终端结果。将 relay 会话和本地链路职责分离到专注的组件中。至少拆成 `RelayCommandBridge`、`ActiveCommandRegistry`、`LocalCommandForwarder`、`IncomingImageAssembler`、`RelayImageUploader` 五类协作者。明确协议边界：`phone-app` 只能消费 `apps/android/share` 中提供的 Android 协议 dataclass / DTO / enum / 常量，以及它们对齐 `packages/protocol` 的镜像；不得在 `phone-app` 内再次定义 relay HTTP DTO、relay↔phone WS payload 或 phone↔glasses 本地协议 shape。
  **禁止做什么**：不要将旧控制器保留为上帝对象，不要硬编码 relay/本地 JSON 负载，也不要允许手机在上传完成之前报告捕获成功；不要在 `apps/android/phone-app` 中落入协议定义，除非只是组装/消费 `share` 已提供的类型；不要把 websocket 消息处理、本地帧处理、图片缓存、上传成功判定和错误回传串成一个回调巨链。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：集中的 Android 编排，有多个活动部件。
  - 技能：`[]` - Android 模块模式和文档是本地的。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 2（阶段 5，与任务 8、9 一起）| 阻塞：11,12,13,14 | 被阻塞于：2,4,5,6

  **参考资料**：
  - 模式：`apps/android/share/src/main/kotlin/**` - `phone-app` 必须消费的统一 Android 协议层。
  - 反模式：`apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt` - 要分解的仅参考整体控制器。
  - 反模式：`apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneLocalLinkSession.kt` - 当前仅保活。
  - 反模式：`apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt` - 当前仅心跳且包含硬编码协议值。
  - 文档：`docs/phone-glasses-module-design.md` - `RelayCommandBridge`、`ActiveCommandRegistry`、`IncomingImageAssembler`、`RelayImageUploader` 职责。
  - 模式：`apps/android/integration-tests/src/test/java/cn/cutemc/rokidmcp/integration/PhoneGlassesLoopbackTest.kt` - 环回集成风格。

  **验收标准**：
  - [ ] `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest :integration-tests:test` 通过，包含新的 relay 桥接器和上传测试。
  - [ ] 手机运行时可以消费推送的 relay 命令，并使用相同的 `requestId` 通过本地链路转发。
  - [ ] 手机仅在预留的 `imageId` 上传返回成功后向 relay 发送捕获终端成功。
  - [ ] `apps/android/phone-app` 不本地重定义 relay HTTP DTO、relay↔phone WS payload、phone↔glasses 本地协议 dataclass、协议常量或共享 enum；这些类型统一来自 `apps/android/share`。
  - [ ] 没有任何单个 phone 模块同时持有 relay session、本地 session、图片缓冲和命令终态上报四类状态。

  **QA 场景**：
  ```
  场景：手机桥接器将 display_text 从 relay 转发到本地链路
    工具：Bash
    步骤：运行 `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest :integration-tests:test --tests "*RelayCommandBridge*" --tests "*PhoneGlassesLoopback*"`。
    预期：推送的 relay 命令使用相同的 `requestId` 转发到环回眼镜对等端，终端结果向上游报告。
    证据：.sisyphus/evidence/task-10-phone-bridge.txt

  场景：手机桥接器在 relay 上传失败时抑制捕获成功
    工具：Bash
    步骤：运行强制 relay 图片上传客户端在照片字节组装后失败的集成/单元子集。
    预期：手机向 relay 报告定义的上传失败错误，不会留下活动命令或临时图片状态。
    证据：.sisyphus/evidence/task-10-phone-bridge-error.txt
  ```

  **提交**：YES | 消息：`feat(phone): bridge relay commands to local link` | 文件：`apps/android/phone-app/src/main/java/**`, `apps/android/phone-app/src/test/**`, `apps/android/integration-tests/src/test/**`

- [ ] 11. 实现眼镜命令调度器和 `display_text` 执行器

  **做什么**：添加眼镜端命令调度器、执行守卫和 `display_text` 执行器。调度器必须接受本地 `COMMAND` 帧、验证活动命令规则、发出 `COMMAND_ACK`/`COMMAND_STATUS`/`COMMAND_RESULT`，并使用替换而非覆盖语义在应用端显示表面渲染文本。`CommandDispatcher` 只负责协议驱动、互斥和执行编排；`DisplayTextExecutor` 只负责显示动作；渲染设备能力通过独立 adapter/port 注入。明确协议边界：`glasses-app` 只能消费 `apps/android/share` 提供的本地协议 dataclass / enum / 常量 / codec，不得在眼镜模块内重新定义命令帧、状态帧、结果帧或错误码结构。
  **禁止做什么**：不要排队多个显示命令，不要隐藏失败于静默无操作，不要将命令终端所有权留在调度器之外；不要在 `apps/android/glasses-app` 中本地发明协议 shape；不要让 dispatcher 直接操作 UI/渲染组件，也不要让 executor 知道 ACK/STATUS/RESULT 发包细节。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：本地化的 Android 运行时工作，具有设备行为影响。
  - 技能：`[]` - 文档和本地模式已足够。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 3（阶段 6，与任务 12 一起）| 阻塞：13,14 | 被阻塞于：2,10

  **参考资料**：
  - 模式：`apps/android/share/src/main/kotlin/**` - `glasses-app` 必须消费的统一本地协议层。
  - 反模式：`apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/GlassesLocalLinkSession.kt` - 当前忽略命令帧。
  - 文档：`docs/phone-glasses-module-design.md` - `CommandDispatcher`、`ExclusiveExecutionGuard`、`DisplayTextExecutor` 角色。
  - 文档：`docs/phone-glasses-protocol-schemas.md` - 本地命令/结果/状态负载期望。

  **验收标准**：
  - [ ] `apps/android/gradlew -p apps/android :glasses-app:testDebugUnitTest :integration-tests:test` 通过，包含调度器/显示执行器覆盖。
  - [ ] `display_text` 按记录顺序发出 ACK、running/displaying 状态和终端结果。
  - [ ] 当另一个命令处于活动状态时，第二个显示请求被拒绝，并带有定义的繁忙错误。
  - [ ] `apps/android/glasses-app` 不本地重定义本地协议 dataclass、状态 enum、错误码或协议常量；这些类型统一来自 `apps/android/share`。
  - [ ] 本地协议编解码与显示执行解耦；替换显示语义由 executor/renderer 明确实现，而非 dispatcher 内联分支拼接。

  **QA 场景**：
  ```
  场景：眼镜调度器执行 display_text 并按顺序发出状态
    工具：Bash
    步骤：运行 `apps/android/gradlew -p apps/android :glasses-app:testDebugUnitTest :integration-tests:test --tests "*DisplayTextExecutor*" --tests "*CommandDispatcher*"`。
    预期：测试显示 ACK → RUNNING/DISPLAYING → RESULT 排序，渲染文本适配器接收确切的输入字符串和持续时间。
    证据：.sisyphus/evidence/task-11-glasses-display.txt

  场景：眼镜调度器拒绝重叠的显示命令
    工具：Bash
    步骤：运行调度器测试子集，在第一个完成之前注入第二个 `display_text` 命令。
    预期：第二个命令返回定义的繁忙错误，第一个命令生命周期保持一致。
    证据：.sisyphus/evidence/task-11-glasses-display-error.txt
  ```

  **提交**：YES | 消息：`feat(glasses): implement display text executor` | 文件：`apps/android/glasses-app/src/main/java/**`, `apps/android/glasses-app/src/test/**`, `apps/android/integration-tests/src/test/**`

- [ ] 12. 实现眼镜 `capture_photo` 执行器和分块发送器

  **做什么**：添加眼镜端 JPEG 捕获执行器、分块发送器和终端结果/错误流程。执行器必须捕获一个 JPEG，生成确定性元数据，使用校验和和 `transferId` 流式传输 `chunk_start`/`chunk_data`/`chunk_end`，并在字节完全交给手机端会话后发出终端成功。显式拆分 `CapturePhotoExecutor`、`CameraAdapter`、`ChecksumCalculator`、`ImageChunkSender`；executor 产出图片对象，sender 只负责分块传输。明确协议边界：捕获相关的 chunk DTO、metadata dataclass、错误码、限制常量与状态 enum 必须来自 `apps/android/share`，`glasses-app` 只负责消费这些类型并驱动设备能力。
  **禁止做什么**：不要流式传输非 JPEG 格式，不要在分块完成之前发出终端成功，不要让相机路径绕过调度器/守卫；不要在 `apps/android/glasses-app` 中单独定义 capture/chunk 协议模型；不要把相机调用、JPEG 元数据生成、校验和计算、chunk framing 和终态上报写进一个协程/方法。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：本地化的设备运行时和二进制传输工作。
  - 技能：`[]` - 仓库本地 Android 和协议文档已足够。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：YES | 波次 3（阶段 6，与任务 11 一起）| 阻塞：13,14 | 被阻塞于：2,5,10

  **参考资料**：
  - 模式：`apps/android/share/src/main/kotlin/**` - capture/chunk 协议 dataclass、enum、常量的统一归属层。
  - 文档：`docs/phone-glasses-module-design.md` - `CapturePhotoExecutor` 和 `OutgoingImageSender` 角色。
  - 文档：`docs/phone-glasses-protocol-schemas.md` - 分块负载语义和元数据。
  - 模式：`apps/android/share/src/main/kotlin/cn/cutemc/rokidmcp/share/protocol/LocalFrameCodec.kt` - 帧/分块序列化保留者。

  **验收标准**：
  - [ ] `apps/android/gradlew -p apps/android :glasses-app:testDebugUnitTest :integration-tests:test` 通过，包含捕获/分块发送器覆盖。
  - [ ] 捕获路径发出 JPEG 元数据、确定性校验和和与单个 `transferId` 绑定的有序分块帧。
  - [ ] 第一个分块之前的捕获失败产生终端错误，无部分成功结果。
  - [ ] `apps/android/glasses-app` 不本地重定义 capture/chunk DTO、元数据 dataclass、协议常量或状态 enum；这些类型统一来自 `apps/android/share`。
  - [ ] `ImageChunkSender` 不接触相机 API；`CapturePhotoExecutor` 不直接构造 chunk 帧。

  **QA 场景**：
  ```
  场景：眼镜捕获执行器发出 JPEG 分块流和终端结果
    工具：Bash
    步骤：运行 `apps/android/gradlew -p apps/android :glasses-app:testDebugUnitTest :integration-tests:test --tests "*CapturePhotoExecutor*" --tests "*OutgoingImageSender*"`。
    预期：测试显示 JPEG 元数据加上有序的 `chunk_start`、多个 `chunk_data`、`chunk_end` 和一个 `requestId` 的终端结果。
    证据：.sisyphus/evidence/task-12-glasses-capture.txt

  场景：眼镜捕获执行器处理分块传输前的相机失败
    工具：Bash
    步骤：运行相机适配器在图像字节生成之前抛出异常的捕获测试子集。
    预期：调度器发出定义的捕获失败错误，不发送分块帧。
    证据：.sisyphus/evidence/task-12-glasses-capture-error.txt
  ```

  **提交**：YES | 消息：`feat(glasses): implement capture photo executor and chunk sender` | 文件：`apps/android/glasses-app/src/main/java/**`, `apps/android/glasses-app/src/test/**`, `apps/android/integration-tests/src/test/**`

- [ ] 13. 集成端到端文本/照片运行时并添加脚本化的真实设备验证

  **做什么**：将新的 MCP、relay、手机和眼镜切片连接到一个活动的端到端流程中，在非切换分支路径中，然后添加脚本化的验证资源，用于在真实硬件上执行 `display_text`、`capture_photo` 和所需的失败案例。脚本必须在 `.sisyphus/evidence/` 下收集日志、命令 ID、图片 ID 和下载的产物证据。将验证脚本按场景拆成独立步骤/函数：`display_text success`、`capture_photo success`、`bluetooth failure`、`upload failure`、`checksum mismatch`；证据采集使用独立 helper。
  **禁止做什么**：不要仅依赖人工观察作为唯一证据，不要仅模拟/环回测试通过就标记功能就绪；不要在生产代码里加入只为验证脚本服务的特殊路径或隐藏开关。

  **推荐代理配置**：
  - 类别：`deep` - 原因：此任务验证跨栈语义并产生切换门控。
  - 技能：`[]` - 仓库本地栈知识已足够。
  - 省略：[`/playwright`] - 无浏览器 UI。

  **并行化**：可并行：NO | 波次 3（阶段 7）| 阻塞：14 | 被阻塞于：8,9,10,11,12

  **参考资料**：
  - 文档：`docs/mvp-technical-implementation.md` - 端到端 MVP 意图。
  - 文档：`docs/relay-protocol-sequences.md` - 端到端 relay 流程。
  - 文档：`docs/mcp-interface-sequences.md` - MCP 端完成协议。
  - 模式：`apps/android/integration-tests/src/test/java/cn/cutemc/rokidmcp/integration/PhoneGlassesLoopbackTest.kt` - 集成工具风格。

  **验收标准**：
  - [ ] 在新的预切换路径中启用所有新布线后，`bun run typecheck && bun run build` 通过。
  - [ ] `bun test apps/relay-server/src packages/protocol/src packages/mcp-server/src` 和 `apps/android/gradlew -p apps/android :share:test :integration-tests:test :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest` 都通过。
  - [ ] `bash .sisyphus/verification/run-real-device-text-photo.sh --phone-serial "$PHONE_SERIAL" --glasses-serial "$GLASSES_SERIAL" --relay-base-url "$RELAY_BASE_URL" --mcp-base-url "$MCP_BASE_URL"` 为两个功能生成成功产物，为蓝牙关闭/上传失败/校验和不匹配演练生成失败产物。
  - [ ] 验证脚本与生产运行时边界清晰；脚本失败不会要求修改业务分层来“迁就测试”。

  **QA 场景**：
  ```
  场景：真实设备 display_text 和 capture_photo 都成功端到端
    工具：Bash
    步骤：运行 `bash .sisyphus/verification/run-real-device-text-photo.sh --phone-serial "$PHONE_SERIAL" --glasses-serial "$GLASSES_SERIAL" --relay-base-url "$RELAY_BASE_URL" --mcp-base-url "$MCP_BASE_URL" --text "Hello from MCP" --duration-ms 3000 --quality default`。
    预期：脚本存储命令日志、终端 MCP 成功负载和两个功能的下载 base64/照片元数据证据。
    证据：.sisyphus/evidence/task-13-e2e-success.txt

  场景：真实设备演练捕获确定性失败处理
    工具：Bash
    步骤：使用蓝牙禁用、上传端点失败和校验和不匹配 fixture 注入的失败标志重新运行相同的脚本。
    预期：每次演练产生记录的 relay + MCP 错误代码，无泄漏的活动命令，无孤立的可上传图片。
    证据：.sisyphus/evidence/task-13-e2e-error.txt
  ```

  **提交**：YES | 消息：`feat(integration): script real-device text and photo verification` | 文件：`packages/**`, `apps/**`, `.sisyphus/verification/**`

- [ ] 14. 切换活动功能布线并隔离 phase1 功能存根

  **做什么**：将活动运行时/工具布线切换到新命令路径，保持心跳/状态支持不变，并明确隔离或移除旧的 phase1 显示/照片相邻运行时路径，使它们不能被意外调用。确保路由/工具注册和 Android 服务组合仅指向新架构进行功能执行。将切换严格限制在 composition root：`server.ts`、`app.ts`、Android service wiring；若保留旧源码，仅允许作为非活动参考实现存在，不得继续被新 wiring 注入。
  **禁止做什么**：不要在生产布线中混合新旧功能路径，不要将此切换与不相关的清理/重构耦合；不要用 feature flag 同时保留两套活动实现，也不要留下静默 fallback 到 phase1。

  **推荐代理配置**：
  - 类别：`unspecified-high` - 原因：布线聚焦的最终集成和风险控制的切换。
  - 技能：`[]` - 仓库本地布线模式已足够。
  - 省略：[`/playwright`] - 不适用。

  **并行化**：可并行：NO | 波次 3（阶段 8）| 阻塞：F1-F4 | 被阻塞于：6,8,9,10,11,12,13

  **参考资料**：
  - 模式：`packages/mcp-server/src/server.ts` - MCP 工具注册点。
  - 模式：`apps/relay-server/src/app.ts` - relay 路由组装点。
  - 反模式：`apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/**` - 旧功能相邻路径不得意外保持活动。
  - 反模式：`apps/android/glasses-app/src/main/java/cn/cutemc/rokidmcp/glasses/gateway/**` - 仅保活存根仅供参考。

  **验收标准**：
  - [ ] 活动 MCP 服务器仅针对新运行时路径注册 `rokid.get_device_status`、`rokid.display_text` 和 `rokid.capture_photo`。
  - [ ] Relay 应用暴露心跳/状态加上新的命令/图片路由，活动执行路径上没有旧功能存根路径。
  - [ ] 切换后在切换构建上运行真实设备验证脚本仍然通过，无需切换回退标志。
  - [ ] 通过注册点和可达调用链检查可证明：文本/照片执行路径只有新架构一条，不存在双路径并存。

  **QA 场景**：
  ```
  场景：切换仅留下新功能路径活动
    工具：Bash
    步骤：切换后启动完整栈，运行 `bun run build`，运行完整的 TS + Android 验证命令，然后再次运行真实设备脚本。
    预期：所有检查通过，日志仅显示新命令/图片模块处理功能执行。
    证据：.sisyphus/evidence/task-14-cutover.txt

  场景：旧 phase1 功能存根无法被意外访问
    工具：Bash
    步骤：运行有针对性的测试或启动断言，如果旧显示/照片运行时处理程序仍被布线则会失败，然后检查切换证据日志。
    预期：断言确认旧存根不在活动路径上；日志中没有混合路径执行。
    证据：.sisyphus/evidence/task-14-cutover-error.txt
  ```

  **提交**：YES | 消息：`feat(cutover): switch active feature path to new architecture` | 文件：`packages/mcp-server/src/server.ts`, `apps/relay-server/src/app.ts`, `apps/android/**`

## 最终验证波次（强制性 — 在所有实现任务之后）
> 4 个审查代理并行运行。全部必须批准。向用户呈现综合结果并在完成前获得明确的"确认"。
> **验证后不要自动继续。等待用户明确批准后再标记工作完成。**
> **在获得用户确认之前，永远不要将 F1-F4 标记为已检查。** 拒绝或用户反馈 → 修复 → 重新运行 → 再次呈现 → 等待确认。
- [ ] F1. 计划合规审计 — oracle

  **做什么**：针对已完成的分支和此计划文件运行 oracle 审查，以验证每个实现的切片是否与冻结的命令/图片协议、所有权矩阵和切换规则匹配。
  **工具**：`task(subagent_type="oracle")`
  **步骤**：
  1. 一起审查 `.sisyphus/plans/full-reset-text-photo.md` 和分支差异。
  2. 检查任务 1-14 是否在没有绕过防护措施的情况下完成。
  3. 仅在没有协议漂移或所有权漂移存在时发出 `APPROVE`。
     **预期**：Oracle 返回批准，零关键协议偏差。
     **证据**：`.sisyphus/evidence/f1-plan-compliance.md`

- [ ] F2. 代码质量审查 — unspecified-high

  **做什么**：运行专注于干净架构边界、移除临时 JSON 处理、不重用旧 phase1 功能路径，以及检查协议归属是否正确的代码质量审查。
  **工具**：`task(category="unspecified-high")`
  **步骤**：
  1. 检查 MCP、relay、手机、眼镜和共享协议中触及的文件。
  2. 确认使用序列化器支持的 DTO，仅参考文件不在活动路径上。
  3. 检查 `apps/relay-server`、`apps/android/phone-app`、`apps/android/glasses-app` 中是否残留协议定义（DTO、payload、enum、错误码、协议常量、dataclass）；若发现，确认它们已被移动到 `packages/protocol` 或 `apps/android/share`，且业务模块仅消费这些类型。
  4. 仅在没有主要代码异味、分层违规或协议归属违规存在时发出 `APPROVE`。
      **预期**：审查员返回批准，零关键干净代码或分层问题。
      **证据**：`.sisyphus/evidence/f2-code-quality.md`

- [ ] F3. 真实手动 QA — unspecified-high（如果有 UI 则加上 playwright）

  **做什么**：在切换构建上最后一次执行脚本化的真实设备验证，并确认成功和所需的失败演练仍然完全按指定行为。
  **工具**：`Bash`
  **步骤**：
  1. 运行 `bash .sisyphus/verification/run-real-device-text-photo.sh --phone-serial "$PHONE_SERIAL" --glasses-serial "$GLASSES_SERIAL" --relay-base-url "$RELAY_BASE_URL" --mcp-base-url "$MCP_BASE_URL" --text "Hello from MCP" --duration-ms 3000 --quality default`。
  2. 使用蓝牙禁用、上传端点失败和校验和不匹配的失败标志重新运行。
  3. 保存日志、MCP 输出和产物摘要。
     **预期**：成功运行通过两个功能；失败演练产生记录的 relay/MCP 错误代码和干净的清理行为。
     **证据**：`.sisyphus/evidence/f3-real-device-qa.txt`

- [ ] F4. 范围保真度检查 — deep

  **做什么**：运行最终深度审查，确保分支没有扩展到商定的 v1 范围之外，并确认最终协议归属边界没有被破坏。
  **工具**：`task(category="deep")`
  **步骤**：
  1. 将已完成的分支与计划的进/出范围进行比较。
  2. 检查意外的多设备支持、排队、图库/历史 API、可恢复上传或通用文件传输添加。
  3. 检查是否在 `apps/relay-server`、`apps/android/phone-app`、`apps/android/glasses-app` 内发现新的协议定义；若发现，确认这些定义已迁移到 `packages/protocol` 或 `apps/android/share`，而非残留在业务模块内。
  4. 仅当分支严格保持在商定范围内，且协议归属边界未被破坏时发出 `APPROVE`。
      **预期**：审查员返回批准，零关键范围蔓延发现。
      **证据**：`.sisyphus/evidence/f4-scope-fidelity.md`

## 提交策略
- `feat(protocol): freeze relay command and image contracts`
- `feat(android-share): freeze local command and image contracts`
- `feat(relay): add single-device command lifecycle core`
- `feat(relay): add image manager and upload download routes`
- `feat(relay): dispatch commands over device websocket`
- `feat(mcp): add relay polling and result mapping for commands`
- `feat(mcp): implement display text tool`
- `feat(mcp): implement capture photo tool`
- `feat(phone): bridge relay commands to local link`
- `feat(glasses): implement display text executor`
- `feat(glasses): implement capture photo executor and chunk sender`
- `feat(integration): script real-device text and photo verification`
- `feat(cutover): switch active feature path to new architecture`

## 成功标准
- `rokid.display_text` 仅在眼镜执行完成且 relay 终端状态为 `COMPLETED` 时返回成功。
- `rokid.capture_photo` 仅在 relay 图片状态为 `UPLOADED`、下载校验和与元数据匹配且命令状态为 `COMPLETED` 时返回 base64 + 元数据。
- 蓝牙不可用、上传失败、校验和不匹配和超时路径产生确定性的 relay + MCP 错误代码和清理结果。
- 旧 phase1 功能存根不再在文本/照片的活动执行路径上。
- 在 `apps/relay-server`、`apps/android/phone-app`、`apps/android/glasses-app` 中不残留协议定义；若实现过程中发现协议定义，最终必须迁移到 `packages/protocol` 或 `apps/android/share`。
