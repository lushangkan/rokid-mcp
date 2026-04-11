# Phone Reconnect Delay Configuration

## TL;DR
> **Summary**: Add a single phone-side reconnect delay config (`reconnectDelayMs`, default `5000`) and make both phone→relay and phone→glasses retry paths wait before retrying instead of re-entering tight reconnect loops.
> **Deliverables**:
> - Persisted phone config + settings/service plumbing for `reconnectDelayMs`
> - Delayed relay-only reconnect flow for relay websocket failures/closes
> - Delayed full-session reconnect flow for phone→glasses transport/session failures
> - Regression coverage for config defaults, retry scheduling, cancellation, and no-regression startup behavior
> **Effort**: Medium
> **Parallel**: YES - 2 waves
> **Critical Path**: 1 → 3 → 4/5 → 6

## Context
### Original Request
- 修复自动重连没有等待时间、不断重连导致卡顿的问题。
- 覆盖 phone→relay 和 phone→glasses 两条连接。
- 把重连延迟加入 Config，默认 5 秒。

### Interview Summary
- Test strategy: **tests-after**.
- Use one shared reconnect-delay knob, not separate relay/local delays.
- Default value is stored as **milliseconds** (`5000`) to match existing `*Ms` naming in Android + relay config code.
- `PhoneSettingsScreen` is part of the config surface, so the new config must be editable there instead of remaining hidden/hardcoded.

### Metis Review (gaps addressed)
- Retry only on **unexpected disconnect/failure** paths; do **not** auto-retry manual stop, invalid startup config, or local `HelloRejected`.
- Keep **one pending reconnect job** at a time; new starts and manual stops must cancel it.
- Relay reconnect should be **relay-only** so a relay outage does not tear down a healthy Bluetooth/local session.
- Local reconnect should be a **full controller restart** because `RelayCommandBridge` is bound to the created `PhoneLocalLinkSession` instance.
- A closed/failed relay websocket must clear the stale socket reference before reconnecting.

## Work Objectives
### Core Objective
Introduce a configurable reconnect delay on the Android phone side so unexpected relay and local-link disconnects wait `reconnectDelayMs` before retrying, with a default of `5000` and no tight retry loops.

### Deliverables
- `PhoneLocalConfig` / `PhoneGatewayConfig` / service-intent plumbing include `reconnectDelayMs`.
- Settings UI loads, validates, saves, and starts with the reconnect delay field.
- `PhoneAppController` owns a single delayed reconnect scheduler with explicit cancellation rules.
- `RelaySessionClient` exposes enough lifecycle signal to distinguish unexpected remote close from manual disconnect and supports reconnecting after close/failure.
- Unit/integration tests cover defaulting, invalid values, delayed retries, retry cancellation, and no-regression startup behavior.

### Definition of Done (verifiable conditions with commands)
- `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStoreTest" --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfigStateTest" --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayServiceTest" --tests "cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsViewModelTest"`
- `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneAppControllerTest" --tests "cn.cutemc.rokidmcp.phone.gateway.RelaySessionClientTest"`
- `apps/android/gradlew -p apps/android :integration-tests:test --tests "cn.cutemc.rokidmcp.integration.PhoneGlassesLoopbackTest"`
- `apps/android/gradlew -p apps/android :phone-app:assembleDebug`

### Must Have
- One config field: `reconnectDelayMs: Long`, default `5000L`.
- SharedPreferences persistence + normalization for missing/invalid values.
- Settings field labeled **Reconnect Delay (ms)** and validation that rejects blank/non-numeric/`<= 0` values.
- Relay reconnect waits `reconnectDelayMs` before retrying and does not tear down a healthy local session.
- Local reconnect waits `reconnectDelayMs` before restarting the controller session.
- Manual `stop()` cancels any pending retry job.
- A fresh manual `start()` cancels/replaces any pending retry job.

### Must NOT Have (guardrails, AI slop patterns, scope boundaries)
- Must NOT add exponential backoff, jitter, or separate relay/local delay settings.
- Must NOT retry invalid startup config, transport-construction failures, or local `HelloRejected`.
- Must NOT spawn multiple overlapping retry jobs.
- Must NOT hide the new delay behind a hardcoded constant after adding it to config.
- Must NOT recreate the Bluetooth session just because the relay websocket briefly drops.

## Verification Strategy
> ZERO HUMAN INTERVENTION - all verification is agent-executed.
- Test decision: **tests-after** with existing Android JUnit/Robolectric + integration tests.
- QA policy: Every task below includes agent-executed scenarios.
- Evidence: `.sisyphus/evidence/task-{N}-{slug}.{ext}`

## Execution Strategy
### Parallel Execution Waves
Wave 1: Task 1 (config model/store/state), Task 2 (settings/service plumbing), Task 3 (controller retry foundation)

Wave 2: Task 4 (relay delayed reconnect), Task 5 (local delayed reconnect), Task 6 (targeted regression + assemble)

### Dependency Matrix (full, all tasks)
- Task 1 blocks Tasks 2-5.
- Task 2 depends on Task 1.
- Task 3 depends on Task 1.
- Task 4 depends on Task 3.
- Task 5 depends on Task 3.
- Task 6 depends on Tasks 1-5.
- Final Verification Wave depends on Tasks 1-6.

### Agent Dispatch Summary (wave → task count → categories)
- Wave 1 → 3 tasks → `quick`, `unspecified-low`
- Wave 2 → 3 tasks → `quick`, `unspecified-low`
- Final Verification → 4 tasks → `oracle`, `unspecified-high`, `deep`

## TODOs
> Implementation + Test = ONE task. Never separate.
> EVERY task MUST have: Agent Profile + Parallelization + QA Scenarios.

- [x] 1. Add persisted reconnect delay to phone config models

  **What to do**:
  - Extend `PhoneLocalConfig` with `reconnectDelayMs: Long`.
  - Default it to `5000L` inside `PhoneLocalConfig.default(...)`.
  - Persist it in `PhoneLocalConfigStore` under a dedicated `reconnectDelayMs` key.
  - Normalize missing, non-finite, or `<= 0` persisted values back to `5000L` during `load()`.
  - Extend `PhoneGatewayConfig` and `PhoneGatewayConfigState.load/update(...)` to round-trip the field unchanged.

  **Must NOT do**:
  - Do not introduce a seconds-based duplicate field.
  - Do not accept `0` or negative values into runtime config.
  - Do not create separate relay/local delay knobs.

  **Recommended Agent Profile**:
  - Category: `quick` - Reason: focused Kotlin model/store/state changes with nearby tests.
  - Skills: `[]` - No special skill required.
  - Omitted: `['/playwright']` - No browser/UI automation needed.

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: 2, 3, 4, 5 | Blocked By: none

  **References** (executor has NO interview context - be exhaustive):
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfig.kt:5-20` - Existing local persisted config shape + default factory.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStore.kt:8-38` - SharedPreferences load/save normalization flow.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayConfigState.kt:6-42` - Runtime config load/update adapter.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStoreTest.kt:28-92` - Store defaulting + invalid persisted data patterns.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayConfigStateTest.kt:25-83` - Gateway config round-trip assertions.
  - External: `apps/relay-server/src/config/env.ts:1-32` - Local convention for numeric timing defaults and fallback handling.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `PhoneLocalConfig.default()` yields `reconnectDelayMs == 5000L`.
  - [ ] `PhoneLocalConfigStore.load()` rewrites invalid/missing reconnect-delay persistence to `5000L`.
  - [ ] `PhoneGatewayConfigState.load/update(...)` preserves the reconnect delay in returned runtime config.

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Config default and persistence path
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.config.PhoneLocalConfigStoreTest" --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayConfigStateTest"`
    Expected: Gradle exits 0 and the new reconnect-delay default/persistence tests pass.
    Evidence: .sisyphus/evidence/task-1-config-models.log

  Scenario: Invalid persisted delay falls back to 5000
    Tool: Bash
    Steps: Re-run the same command and confirm the suite includes the invalid-persisted-value fallback coverage added for reconnect delay.
    Expected: No failing tests; fallback behavior is covered and green.
    Evidence: .sisyphus/evidence/task-1-config-models-fallback.log
  ```

  **Commit**: NO | Message: `fix(phone-app): add reconnect delay config` | Files: `apps/android/phone-app/src/main/java/.../config/*`, `.../gateway/PhoneGatewayConfigState.kt`, related tests

- [x] 2. Surface reconnect delay through service intents and Settings UI

  **What to do**:
  - Extend `PhoneGatewayIntentConfig`, service extras, and `toGatewayRuntimeConfig(...)` to carry `reconnectDelayMs`.
  - When `PhoneGatewayService` persists a provided config, include the reconnect delay.
  - Add `reconnectDelayMs` to `PhoneSettingsUiState` as a text field value (string form for Compose editing).
  - Add parsing/validation helpers in `PhoneSettingsViewModel`: valid only when numeric and `> 0`.
  - Load the persisted value as a decimal string, save it back as `Long`, and include it in `startGateway()` runtime config.
  - Add a new Settings field labeled `Reconnect Delay (ms)` below `Relay Base URL`.

  **Must NOT do**:
  - Do not create a new screen or separate dialog.
  - Do not silently ignore invalid input while leaving Start/Save enabled.
  - Do not convert to seconds in storage or runtime config.

  **Recommended Agent Profile**:
  - Category: `unspecified-low` - Reason: UI + service plumbing across a few Android files.
  - Skills: `[]` - No special skill required.
  - Omitted: `['/frontend-ui-ux']` - Existing Compose form pattern is sufficient.

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: 6 | Blocked By: 1

  **References** (executor has NO interview context - be exhaustive):
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayService.kt:16-152` - Intent extras, runtime config creation, and config persistence in service start flow.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModel.kt:23-219` - Settings state, validation, save/start plumbing.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsScreen.kt:27-142` - Existing Compose form layout.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayServiceTest.kt:22-75` - Intent config extraction tests.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModelTest.kt:32-217` - Settings load/save/start validation patterns.

  **Acceptance Criteria** (agent-executable only):
  - [ ] Service/runtime intent config includes `reconnectDelayMs` end-to-end.
  - [ ] Settings loads `5000` by default and saves user-entered positive millisecond values.
  - [ ] Save/Start become disabled when the reconnect-delay field is blank, non-numeric, or `<= 0`.

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Service and settings config plumbing
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneGatewayServiceTest" --tests "cn.cutemc.rokidmcp.phone.ui.settings.PhoneSettingsViewModelTest"`
    Expected: Gradle exits 0 and the reconnect-delay service/settings tests pass.
    Evidence: .sisyphus/evidence/task-2-settings-service.log

  Scenario: Debug build still compiles with new field
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :phone-app:assembleDebug`
    Expected: Debug APK assembles successfully; no compile errors from new config/UI field.
    Evidence: .sisyphus/evidence/task-2-settings-service-assemble.log
  ```

  **Commit**: NO | Message: `fix(phone-app): expose reconnect delay in settings` | Files: `PhoneGatewayService.kt`, `PhoneSettingsViewModel.kt`, `PhoneSettingsScreen.kt`, related tests

- [x] 3. Add a single reconnect scheduler to `PhoneAppController`

  **What to do**:
  - Extend controller state with:
    - cached `lastStartTargetDeviceAddress`
    - cached `lastEffectiveConfig`
    - exactly one `pendingReconnectJob`
  - Cache the effective target/config on every successful `start(...)` call before sessions begin.
  - Add private helpers that cancel any existing retry before scheduling a new one.
  - Add explicit cancellation from manual `stop(reason)` and from any fresh manual `start(...)`.
  - Keep retry execution using the cached target/config instead of reloading config mid-wait.
  - Log retry scheduling with delay and reason so debugging confirms the wait is intentional.

  **Must NOT do**:
  - Do not allow multiple pending retry jobs.
  - Do not schedule retries for startup validation errors or manual stops.
  - Do not reload config from disk during the pending retry window.

  **Recommended Agent Profile**:
  - Category: `quick` - Reason: centralized state-owner change with deterministic coroutine tests.
  - Skills: `[]` - No special skill required.
  - Omitted: `['/review-work']` - Final verification wave covers review separately.

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: 4, 5, 6 | Blocked By: 1

  **References** (executor has NO interview context - be exhaustive):
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:20-25` - `PhoneGatewayConfig` definition to extend.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:36-145` - Controller constructor and start path where cached config/target must be set.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:215-220` - Manual stop path that must cancel pending reconnects.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:362-391` - Active-session teardown helpers and cancellation/reset points.
  - Pattern: `packages/mcp-server/src/command/command-poller.ts:12-69` - Simple delay/wait loop style; use this as the model for explicit timed waiting, not backoff.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt:191-222` - Repeated-start replacement behavior.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt:421-495` - Restart-after-failure / restart-after-close behavior to extend.

  **Acceptance Criteria** (agent-executable only):
  - [ ] At most one reconnect job exists at any time.
  - [ ] Manual `stop()` cancels the pending reconnect job.
  - [ ] A fresh `start()` supersedes any pending reconnect and uses the newest target/config.

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Single-job scheduling and start override
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneAppControllerTest"`
    Expected: Gradle exits 0 and new controller tests prove one pending job plus manual-start override behavior.
    Evidence: .sisyphus/evidence/task-3-controller-foundation.log

  Scenario: Manual stop cancels pending retry
    Tool: Bash
    Steps: Re-run the same controller suite after adding a test that schedules a reconnect, calls `stop("manual")`, advances virtual time beyond 5000 ms, and asserts no restart occurs.
    Expected: No failing tests; no reconnect fires after manual stop.
    Evidence: .sisyphus/evidence/task-3-controller-foundation-cancel.log
  ```

  **Commit**: NO | Message: `fix(phone-app): centralize reconnect scheduling` | Files: `PhoneAppController.kt`, related controller tests

- [x] 4. Add delayed relay-only reconnect behavior

  **What to do**:
  - Extend `RelaySessionEvent` with a distinct unexpected-close signal (e.g. `ConnectionClosed(code, reason)`) instead of relying only on `UplinkStateChanged(OFFLINE)`.
  - In `RelaySessionClient.onClosed(...)` and `onFailure(...)`, clear `activeWebSocket` back to reconnectable state before emitting events.
  - Keep `disconnect(reason)` as the explicit/manual path: it should emit offline state but must **not** emit the unexpected-close event.
  - Update `PhoneAppController.handleRelaySessionEvent(...)` so `Failed` and unexpected close schedule `relaySessionClient.connect()` after `config.reconnectDelayMs`, without tearing down `localSession`.
  - Preserve current runtime projection updates (`uplinkState` / `runtimeState` / `lastErrorCode`) while waiting to retry.

  **Must NOT do**:
  - Do not restart the Bluetooth/local session for a relay-only outage.
  - Do not schedule reconnect on manual `disconnect()`.
  - Do not leave `activeWebSocket` pointing at a closed socket.

  **Recommended Agent Profile**:
  - Category: `quick` - Reason: contained relay lifecycle changes with direct tests.
  - Skills: `[]` - No special skill required.
  - Omitted: `['/playwright']` - No browser interaction.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 6 | Blocked By: 1, 3

  **References** (executor has NO interview context - be exhaustive):
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt:49-67` - Relay event contract to extend.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt:141-192` - Socket lifecycle, `connect()`, and manual `disconnect()` semantics.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt:249-256` - Existing failure/offline emission path for malformed HELLO_ACK.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClient.kt:319-332` - Current close/failure handlers that need reconnect readiness.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:328-359` - Relay event handling in controller.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClientTest.kt:122-159` - Websocket lifecycle logging/close/failure coverage.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClientTest.kt:388-460` - Event emission on bad HELLO_ACK / heartbeat lifecycle.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt:833-862` - Existing relay failure runtime projection.

  **Acceptance Criteria** (agent-executable only):
  - [ ] Unexpected relay close/failure schedules a reconnect only after `reconnectDelayMs`.
  - [ ] Manual controller/service stop does not reschedule relay reconnect.
  - [ ] A relay reconnect attempt can actually occur after close/failure because the stale socket reference is cleared.

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Relay failure waits before reconnecting
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.gateway.RelaySessionClientTest" --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneAppControllerTest"`
    Expected: Gradle exits 0 and the added tests prove no reconnect happens before 5000 ms, but one happens after virtual time passes the delay.
    Evidence: .sisyphus/evidence/task-4-relay-delay.log

  Scenario: Relay retry keeps local session intact
    Tool: Bash
    Steps: Re-run the same suites after adding a controller test that triggers relay failure while local session is ready and verifies the local session is not torn down during the waiting window.
    Expected: No failing tests; local session remains active until/unless local transport itself fails.
    Evidence: .sisyphus/evidence/task-4-relay-delay-local-intact.log
  ```

  **Commit**: NO | Message: `fix(phone-app): delay relay reconnect attempts` | Files: `RelaySessionClient.kt`, `PhoneAppController.kt`, relay/controller tests

- [x] 5. Add delayed full-session reconnect for phone→glasses failures

  **What to do**:
  - Treat these as retry-eligible local-link failures: `PhoneTransportEvent.Failure`, `PhoneTransportEvent.ConnectionClosed`, and `PhoneLocalSessionEvent.SessionFailed`.
  - After updating runtime/error state and failing any active command, tear down the active local/relay session and schedule a full `start(cachedTarget, cachedConfig)` after `reconnectDelayMs`.
  - Leave `PhoneLocalSessionEvent.HelloRejected` as **non-retryable**; keep the current stopped state so the user can resolve the underlying issue manually.
  - Ensure the scheduled full restart creates a fresh transport, fresh local session, and fresh `RelayCommandBridge` exactly once.
  - Keep the controller in a manual-override-friendly state while waiting (i.e. pending auto-retry may exist, but a manual Start must cancel/replace it).

  **Must NOT do**:
  - Do not retry `HelloRejected`.
  - Do not double-stop or double-terminate the existing session during teardown.
  - Do not fire the restart immediately; it must wait the configured delay.

  **Recommended Agent Profile**:
  - Category: `quick` - Reason: localized controller behavior change with extensive existing fake-transport tests.
  - Skills: `[]` - No special skill required.
  - Omitted: `['/git-master']` - No git work in this plan.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 6 | Blocked By: 1, 3

  **References** (executor has NO interview context - be exhaustive):
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:147-185` - Transport event handling and current teardown/state updates.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:268-320` - Local session failure handling; `HelloRejected` vs `SessionFailed` semantics.
  - Pattern: `apps/android/phone-app/src/main/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppController.kt:362-391` - `stopActiveSession`, `terminateActiveSession`, and `clearActiveSession` helpers.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt:226-351` - Transport failure / connection-closed / timeout-regression tests.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt:355-572` - Single-stop teardown and local-session failure coverage.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModelTest.kt:175-216` - Current UI expectation that recoverable local failures preserve retryability.

  **Acceptance Criteria** (agent-executable only):
  - [ ] Local transport/session failures wait `reconnectDelayMs` before restarting.
  - [ ] `HelloRejected` remains non-retryable.
  - [ ] The delayed restart creates a fresh transport/session exactly once and does not regress old timeout jobs after teardown.

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Local disconnect waits before full restart
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :phone-app:testDebugUnitTest --tests "cn.cutemc.rokidmcp.phone.gateway.PhoneAppControllerTest"`
    Expected: Gradle exits 0 and new controller tests prove no new transport/session starts before 5000 ms, then exactly one restart occurs after the delay.
    Evidence: .sisyphus/evidence/task-5-local-delay.log

  Scenario: HelloRejected remains manual-only
    Tool: Bash
    Steps: Re-run the same controller suite after adding a test that triggers `PhoneLocalSessionEvent.HelloRejected`, advances virtual time beyond the delay, and verifies no auto-retry occurs.
    Expected: No failing tests; rejection stays stopped/manual.
    Evidence: .sisyphus/evidence/task-5-local-delay-hello-rejected.log
  ```

  **Commit**: NO | Message: `fix(phone-app): delay local reconnect attempts` | Files: `PhoneAppController.kt`, controller tests

- [x] 6. Run the focused regression/build matrix for reconnect delay

  **What to do**:
  - Run the exact targeted unit test matrix from the Definition of Done.
  - Add or update any missing tests discovered while implementing Tasks 1-5 until the whole reconnect-delay surface is covered.
  - Run `:integration-tests:test` for `PhoneGlassesLoopbackTest` to confirm the local-link path still handshakes and pings cleanly.
  - Run `:phone-app:assembleDebug` last to catch compile/resource issues after the cross-file changes.
  - Store logs under `.sisyphus/evidence/` for each command.

  **Must NOT do**:
  - Do not skip the integration test.
  - Do not claim completion with only controller tests green.
  - Do not leave failing or flaky reconnect-delay tests quarantined.

  **Recommended Agent Profile**:
  - Category: `quick` - Reason: command execution + fast follow-up fixes if tests expose gaps.
  - Skills: `[]` - No special skill required.
  - Omitted: `['/review-work']` - Final verification wave handles review separately.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: Final Verification Wave | Blocked By: 2, 4, 5

  **References** (executor has NO interview context - be exhaustive):
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneAppControllerTest.kt:36-1001` - Main behavior regression suite for controller restarts/cancellation.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/RelaySessionClientTest.kt:26-677` - Relay lifecycle regression suite.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/config/PhoneLocalConfigStoreTest.kt:16-92` - Persisted config regression suite.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/gateway/PhoneGatewayServiceTest.kt:19-75` - Intent/service config regression suite.
  - Test: `apps/android/phone-app/src/test/java/cn/cutemc/rokidmcp/phone/ui/settings/PhoneSettingsViewModelTest.kt:23-217` - Settings validation regression suite.
  - Test: `apps/android/integration-tests/src/test/java/cn/cutemc/rokidmcp/integration/PhoneGlassesLoopbackTest.kt:43-242` - Loopback handshake / keepalive regression after reconnect-delay changes.

  **Acceptance Criteria** (agent-executable only):
  - [ ] All targeted `:phone-app:testDebugUnitTest` suites listed in Definition of Done pass.
  - [ ] `:integration-tests:test --tests "cn.cutemc.rokidmcp.integration.PhoneGlassesLoopbackTest"` passes.
  - [ ] `:phone-app:assembleDebug` passes.

  **QA Scenarios** (MANDATORY - task incomplete without these):
  ```
  Scenario: Focused Android regression matrix
    Tool: Bash
    Steps: Run the two `:phone-app:testDebugUnitTest` commands from Definition of Done.
    Expected: Both commands exit 0 with all reconnect-delay-related tests green.
    Evidence: .sisyphus/evidence/task-6-phone-regression.log

  Scenario: Integration and build verification
    Tool: Bash
    Steps: Run `apps/android/gradlew -p apps/android :integration-tests:test --tests "cn.cutemc.rokidmcp.integration.PhoneGlassesLoopbackTest"` and then `apps/android/gradlew -p apps/android :phone-app:assembleDebug`.
    Expected: Both commands exit 0; loopback handshake still passes and debug build compiles successfully.
    Evidence: .sisyphus/evidence/task-6-integration-build.log
  ```

  **Commit**: YES | Message: `fix(phone-app): delay reconnect retries` | Files: `apps/android/phone-app/src/main/java/...`, `apps/android/phone-app/src/test/...`, optional `apps/android/integration-tests/src/test/...`

## Final Verification Wave (MANDATORY — after ALL implementation tasks)
> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
> **Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.**
> **Never mark F1-F4 as checked before getting user's okay.** Rejection or user feedback -> fix -> re-run -> present again -> wait for okay.
- [x] F1. Plan Compliance Audit — oracle
- [x] F2. Code Quality Review — unspecified-high
- [x] F3. Real Manual QA — unspecified-high (+ playwright if UI)
- [x] F4. Scope Fidelity Check — deep

## Commit Strategy
- Make **one final commit** after Task 6 and before the Final Verification Wave.
- Commit message: `fix(phone-app): delay reconnect retries`
- Do not split config/UI/controller changes into separate commits for this work; the regression fix is one user-facing bugfix.

## Success Criteria
- Reconnect delay is configurable through phone config and defaults to `5000 ms`.
- Unexpected relay and local-link disconnects no longer retry immediately.
- Manual stop never triggers auto-reconnect.
- Local `HelloRejected` remains manual-only.
- Relay reconnect does not tear down a healthy local session.
- All targeted unit tests, loopback integration test, and debug build pass.
