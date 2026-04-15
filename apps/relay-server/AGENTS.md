# RELAY SERVER GUIDE

## OVERVIEW
`apps/relay-server` is the Bun/Elysia runtime that owns device session state, HTTP status routes, and WebSocket device ingress.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Process entry | `src/main.ts` | reads env and starts listening |
| App assembly | `src/app.ts` | wires env, manager, HTTP route, WS route |
| Env/config | `src/config/env.ts` | relay host/port and heartbeat timing |
| Device state owner | `src/modules/device/device-session-manager.ts` | central runtime/session lifecycle |
| HTTP status route | `src/routes/http-devices.ts` | status endpoint adapter |
| WS device route | `src/routes/ws-device.ts` | protocol adapter for device messages |
| Guardrail test | `src/import-paths.test.ts` | forbids local relative `.js` imports |

## CONVENTIONS
- `main.ts` is thin; keep runtime boot logic in `app.ts` factories.
- `DeviceSessionManager` is the main state authority. Routes should adapt request/protocol input and call manager methods rather than mutate stores directly.
- Use the protocol package for DTO/state types instead of redefining relay shapes locally.
- Tests are co-located `*.test.ts` files run with `bun:test`.
- Local relative source imports omit `.js` extensions; this is enforced by test.

## ANTI-PATTERNS
- Do not bypass `DeviceSessionManager` to patch session/runtime records from route code.
- Do not add local relative `.js` import extensions in `src/*.ts`.
- Do not let `main.ts` accumulate route/business logic; keep it as a boot file.

## NOTES
- `src/modules/device/` is the highest-signal area for status, heartbeat, stale-session, and online/offline behavior.
- `createDefaultApp` and `DeviceSessionManager` are the most central symbols in this package.
- Build/typecheck use `tsc -b`; dev/start use Bun directly.
