# ANDROID WORKSPACE GUIDE

## OVERVIEW
`apps/android` is a Gradle workspace with four semantic modules: `phone-app`, `glasses-app`, `share`, and `integration-tests`.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Workspace/module wiring | `settings.gradle.kts`, `build.gradle.kts` | module list and shared plugin setup |
| Shared local protocol | `share/src/main/kotlin/.../protocol/` | codec, protocol constants, shared Kotlin DTOs |
| Phone gateway/runtime | `phone-app/src/main/java/.../phone/gateway/` | Bluetooth client, relay session, controller |
| Phone UI/config | `phone-app/src/main/java/.../phone/ui/`, `.../config/` | Compose screens and persisted local config |
| Glasses gateway/runtime | `glasses-app/src/main/java/.../glasses/gateway/` | Bluetooth server/session/controller |
| Android tests | `*/src/test/`, `*/src/androidTest/`, `integration-tests/` | JUnit, Robolectric, instrumentation |

## STRUCTURE
```text
apps/android/
├── phone-app/          # phone-side Compose app + gateway service
├── glasses-app/        # glasses-side Compose app + gateway service
├── share/              # shared Kotlin/JVM protocol code
└── integration-tests/  # cross-module loopback/integration coverage
```

## CONVENTIONS
- Run builds/tests with Gradle from `apps/android`; root Bun scripts only wrap selected Android commands.
- Treat Gradle modules as the primary ownership boundary. The deep `src/main/java/cn/cutemc/rokidmcp/...` path is Android/Java package nesting, not a cue for more AGENTS files.
- `share` is the common local-protocol layer; `phone-app` and `glasses-app` already depend on it and should not fork protocol constants casually.
- `phone-app` is the richer control surface: gateway, config, logging, settings UI, and relay uplink all live there.
- Unit tests live under `src/test`, instrumentation tests under `src/androidTest`, and cross-app tests under `integration-tests`.

## ANTI-PATTERNS
- Do not document or organize work by Java package depth alone; use Gradle modules and feature folders (`gateway`, `logging`, `ui`, `config`, `protocol`).
- Do not change the local frame protocol in app modules without updating `share/src/main/kotlin/.../LocalFrameCodec.kt` and its tests.
- Do not replace Gradle-native test/build flows with Bun-native assumptions.

## NOTES
- `phone-app` and `glasses-app` both use Compose and lifecycle/service dependencies.
- `share` is plain Kotlin/JVM with JUnit, so protocol work there is faster to test than full Android app changes.
- `integration-tests` depends on `share`, `phone-app`, and `glasses-app`; use it when behavior crosses module boundaries.
