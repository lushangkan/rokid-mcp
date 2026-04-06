# Android Logging Design

## 1. Goal

This document defines a unified Android logging design for `phone-app` and `glasses-app`.

The goal is to replace ad-hoc in-app logging with a standard logging wheel while preserving a rolling log view in the app UI.

The design must satisfy these constraints:

- Use `Timber` as the logging entry point.
- Both `phone-app` and `glasses-app` follow the same logging pattern.
- Logging infrastructure is not placed in `apps/android/share` or any other shared library.
- Logs must continue to go to Android Logcat.
- `phone-app` must also expose logs to a UI-facing rolling buffer for display on the main screen.
- `glasses-app` should adopt the same infrastructure now, but its rolling UI display can come later.

## 2. Scope

This document covers:

- Android app logging infrastructure in `apps/android/phone-app`
- Android app logging infrastructure in `apps/android/glasses-app`
- Migration of controller-side logging to `Timber`
- UI-facing rolling log storage for `phone-app`
- App startup wiring for logging trees

This document does not cover:

- File logging
- Remote log upload
- Cross-process log sharing
- Moving logging code into `apps/android/share`
- Relay server logging
- JVM or Bun logging outside Android apps

## 3. Design Principles

1. One logging API in app code: application code should write logs through `Timber`, not through bespoke store append calls.
2. Multiple sinks behind one API: a single log call should fan out to both Logcat and the in-memory UI log buffer when configured.
3. Per-app ownership: each Android app owns its own logging package and bootstrap code.
4. Same structure, separate code: `phone-app` and `glasses-app` should mirror each other structurally, but no shared Android logging implementation is extracted at this stage.
5. UI log buffer is a sink, not the source of truth: the buffer exists for display purposes and should not become the primary logging interface.
6. Bounded memory only: rolling logs stay in memory with a fixed-capacity ring behavior.

## 4. Recommended Approach

We will use `Timber` with a per-app custom `UiLogTree`.

Each app will plant:

- a Logcat tree for normal Android logging
- a UI tree that converts log events into `LogEntry` objects and appends them into an in-memory rolling store

Application code will call `Timber.tag(...).d/i/w/e(...)`.

This approach is preferred over building a custom logger abstraction because:

- it uses a proven Android logging library
- it keeps app code simple
- it naturally supports multiple sinks
- it minimizes custom framework code while preserving future extensibility

## 5. Per-App Logging Architecture

Each app gets its own logging package. The package names may differ slightly by app, but the structure should be the same.

Recommended shape for each app:

```text
phone-app/.../logging/
  LogEntry.kt
  UiLogStore.kt
  UiLogTree.kt
  LoggerBootstrap.kt

glasses-app/.../logging/
  LogEntry.kt
  UiLogStore.kt
  UiLogTree.kt
  LoggerBootstrap.kt
```

Responsibilities:

- `LogEntry`: immutable UI-friendly log record
- `UiLogStore`: bounded in-memory rolling buffer exposed as `StateFlow<List<LogEntry>>`
- `UiLogTree`: `Timber.Tree` implementation that forwards logs into `UiLogStore`
- `LoggerBootstrap`: app-local bootstrap that plants trees once during app startup

This is intentionally duplicated by structure, not by shared implementation, to keep Android app ownership boundaries clean.

## 6. Data Model

Each app-local `LogEntry` should include at minimum:

- `level`
- `tag`
- `message`
- `timestampMs`
- `throwableSummary` as optional text

Recommended `level` set:

- `VERBOSE`
- `DEBUG`
- `INFO`
- `WARN`
- `ERROR`

The UI buffer should keep a fixed number of recent entries. Initial default should be `200` entries.

When the cap is exceeded, the oldest entries are dropped.

## 7. Logging Flow

The runtime flow is:

```text
Controller / service / future transport / session code
  -> Timber
      -> Logcat tree
      -> UiLogTree
          -> UiLogStore
              -> StateFlow<List<LogEntry>>
                  -> phone-app main screen rolling log UI
```

Important rule:

- business code must not call `UiLogStore.append(...)` directly
- business code should only use `Timber`

This keeps Logcat output and UI-visible logs aligned.

## 8. phone-app Design

### 8.1 Logging Infrastructure

`phone-app` will adopt the full design immediately:

- `Timber` dependency
- app-local logging package
- `UiLogStore`
- `UiLogTree`
- bootstrap code that plants both trees

### 8.2 Migration of Existing Logging

Current `PhoneLogStore` is a hand-written rolling log buffer.

It should be evolved into a UI sink-oriented store instead of remaining the primary logging API.

Two acceptable implementation choices:

1. Rename it to `PhoneUiLogStore` or `UiLogStore`
2. Keep the file name temporarily but narrow its role to UI log storage only

The preferred option is to rename it toward `UiLogStore` terminology so the architecture is clearer.

`PhoneAppController` should stop calling bespoke `append()` logging methods directly and instead use `Timber`.

### 8.3 UI Exposure

`phone-app` main screen should subscribe to the rolling log flow and display a scrollable log area.

The first iteration only needs:

- newest logs visible in a scrolling list
- timestamp, level, tag, and message shown in compact form
- a clear action to wipe the in-memory buffer

The main screen is allowed to be a local debugging console during this phase.

## 9. glasses-app Design

`glasses-app` should adopt the same logging package structure and bootstrap logic now.

For this phase:

- logs go to Logcat through `Timber`
- logs also go to an in-memory `UiLogStore`
- the main screen does not yet need to render the rolling log buffer

This keeps the app structure symmetric and avoids redesign later when a debug page is added.

## 10. Bootstrap Rules

Each app must bootstrap logging only once during app startup.

Rules:

1. Tree planting must happen in one clearly owned startup path.
2. Repeated planting on recomposition or screen recreation is forbidden.
3. UI store instances should be long-lived for the app process, not recreated per screen.

Acceptable ownership examples:

- an `Application` subclass per app
- a top-level app container created once by `MainActivity`

The design does not require DI framework adoption.

## 11. Error Handling and Formatting

`UiLogTree` should preserve enough information for debugging without reproducing full Logcat formatting.

Rules:

1. If a throwable is present, include a short textual summary in `throwableSummary`.
2. UI entries should remain compact; full stack traces are not required in the rolling list.
3. Null or blank tags should be normalized to an app-defined fallback such as `app`.

## 12. Testing Strategy

Testing should focus on custom code, not on Timber internals.

Required tests:

1. `UiLogStore` keeps only the most recent N entries.
2. `UiLogTree` converts a Timber event into a `LogEntry` and appends it to the store.
3. `PhoneAppController` logging reaches the UI log store through the Timber pipeline.
4. Clearing the rolling log buffer removes all visible entries.

Not required:

- asserting Android Logcat output in tests

## 13. Migration Rules

During migration:

1. New code should use `Timber` only.
2. Existing direct log-store append calls in app logic should be migrated to `Timber`.
3. The rolling UI buffer must remain available to the screen layer after migration.
4. No logging code should be moved into `apps/android/share`.

## 14. Out of Scope For This Change

The following are explicitly deferred:

- file persistence of logs
- exporting logs from the device
- relay upload of logs
- log filtering by module in UI
- search in UI log view
- structured JSON logging
- a shared Android logging library module

## 15. Implementation Summary

The implementation should deliver the following end state:

1. `phone-app` and `glasses-app` both use `Timber`.
2. Both apps have app-local `LogEntry`, `UiLogStore`, `UiLogTree`, and bootstrap wiring.
3. `phone-app` main screen displays a rolling log list backed by `UiLogStore`.
4. Controller-side logging in `phone-app` is migrated from direct store writes to `Timber`.
5. Logcat output and UI-visible logs stay aligned because they come from the same `Timber` calls.
