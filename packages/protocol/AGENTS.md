# PROTOCOL PACKAGE GUIDE

## OVERVIEW
`packages/protocol` is the TypeScript source of truth for relay-facing schemas, state enums, and DTO exports shared by relay-server and mcp-server.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Shared scalar/state types | `src/common/` | ids, timestamps, enums, error schema |
| Relay HTTP DTOs | `src/relay/http.ts` | `GetDeviceStatus*` schemas + types |
| Relay WS DTOs | `src/relay/ws.ts` | hello/heartbeat/state-update messages |
| Public exports | `src/index.ts`, `src/common/index.ts`, `src/relay/index.ts` | package surfaces |
| Test coverage | `src/**/*.test.ts`, `tsconfig.test.json` | bun:test + noEmit test config |

## CONVENTIONS
- Define runtime schemas and static TS types together; `Static<typeof Schema>` is the normal pairing.
- Prefer `additionalProperties: false` on external protocol shapes unless looseness is intentional.
- Export new protocol shapes through the nearest `index.ts` barrel so downstream packages stay on package-level imports.
- Keep tests next to the schema files they protect.

## ANTI-PATTERNS
- Do not add relay DTOs only in downstream packages; protocol changes start here.
- Do not change protocol shapes without updating corresponding `*.test.ts` coverage.
- Do not forget the Android mirror: local-protocol or relay-shape changes may require matching Kotlin updates under `apps/android/share`.

## NOTES
- This package has no meaningful internal workspace dependencies; it is the shared contract layer.
- `GetDeviceStatusResponseSchema` is one of the highest-signal exported schemas because both relay-server and mcp-server rely on it.
