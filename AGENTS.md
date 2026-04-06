# PROJECT KNOWLEDGE BASE

**Generated:** 2026-04-05
**Commit:** ef7f155
**Branch:** dev

## OVERVIEW
Rokid MCP is a Bun workspace for a TypeScript relay server, a TypeScript MCP server, shared protocol schemas, and a separate Android Gradle workspace for phone/glasses apps.
The important split is stack-level: Bun/TypeScript lives under `apps/relay-server` and `packages/*`; Android/Kotlin lives under `apps/android` and follows Gradle rules instead.

## STRUCTURE
```text
rokid-mcp/
├── apps/
│   ├── android/          # Gradle workspace: phone-app, glasses-app, share, integration-tests
│   └── relay-server/     # Bun/Elysia relay server runtime
├── packages/
│   ├── mcp-server/       # MCP tool wrapper around relay APIs
│   ├── protocol/         # TypeBox schemas and shared TS protocol DTOs
│   └── shared-types/     # tiny flat package; no child AGENTS needed
├── .github/workflows/    # Bun CI + Android CI
├── docs/                 # design docs
├── package.json          # workspace scripts and package manager pin
└── tsconfig*.json        # TS project references + shared compiler settings
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Relay runtime boot | `apps/relay-server/src/main.ts`, `src/app.ts` | `main.ts` listens; `app.ts` assembles routes and manager |
| Device session logic | `apps/relay-server/src/modules/device/` | `DeviceSessionManager` is the main state owner |
| Relay HTTP/WS protocol | `packages/protocol/src/relay/` | source of truth for TS-side DTOs |
| MCP tool wiring | `packages/mcp-server/src/server.ts`, `src/tools/` | thin wrapper over relay APIs |
| Android phone/glasses runtime | `apps/android/phone-app/`, `apps/android/glasses-app/` | Compose apps + gateway services |
| Android shared local protocol | `apps/android/share/src/main/kotlin/.../protocol/` | local frame codec and shared Kotlin models |
| CI/build behavior | `.github/workflows/` | TS pipeline and Android pipeline differ |
| Architecture intent | `docs/*module-design.md`, `docs/*protocol*.md` | |

## CODE MAP
| Symbol | Type | Location | Refs | Role |
|--------|------|----------|------|------|
| `createDefaultApp` | function | `apps/relay-server/src/app.ts` | 5 | relay server assembly used by runtime entry/tests |
| `DeviceSessionManager` | class | `apps/relay-server/src/modules/device/device-session-manager.ts` | 21 | relay-side device/session state owner |
| `createMcpServer` | function | `packages/mcp-server/src/server.ts` | 2 | MCP package public constructor |
| `GetDeviceStatusResponseSchema` | schema | `packages/protocol/src/relay/http.ts` | 2 | TS source of truth for relay status response |
| `createGetDeviceStatusTool` | function | `packages/mcp-server/src/tools/get-device-status.ts` | local | tool handler + error normalization |

## CONVENTIONS
- Use Bun as the package manager. Install with `bun install --no-cache`.
- CI is stricter than local dev: `.github/workflows/build-modules.yml` uses `bun install --frozen-lockfile --no-cache`.
- TypeScript uses project references and `tsc -b`; root `tsconfig.json` is the workspace entrypoint.
- TypeScript is ESM/NodeNext with `strict`, `isolatedModules`, and `verbatimModuleSyntax`; prefer explicit `import type` / `export type` when appropriate.
- Workspace dependencies use `workspace:*`.
- Bun tests are co-located as `*.test.ts`; there is no root `test` script.
- Android builds and tests run through Gradle, not Bun.
- `.rgrc` sets `--no-ignore-vcs`; if you use `rg`, expect it to search paths Git would normally hide.
- Always write clean code: keep naming clear, responsibilities focused, and control flow easy to read.
- Always prefer best-practice implementations that match the local module style instead of quick but brittle shortcuts.
- User suggestions are inputs, not automatic decisions: during planning and implementation, always evaluate whether the proposed approach is actually the best option. If it is not, explain the recommended approach and why it is better before proceeding.
- Add appropriate comments where intent, constraints, or non-obvious behavior would otherwise be unclear.
- When possible, add doc comments for methods and classes, especially for public APIs, state owners, protocol boundaries, and tricky logic.

## ANTI-PATTERNS (THIS PROJECT)
- Do not assume deep Android package paths are ownership boundaries; the real boundaries are Gradle modules and feature folders.
- Do not edit generated/build output under Android `build/`, root `dist/`, or `*.tsbuildinfo` files as source-of-truth code.
- Do not introduce local relative `.js` import extensions in `apps/relay-server/src/*.ts`; `import-paths.test.ts` enforces this.
- Do not copy protocol shapes ad hoc across TS packages; update `packages/protocol` first, then sync Android Kotlin mirrors if the protocol changed.

## UNIQUE STYLES
- The repo has two parallel protocol implementations: TypeBox schemas in `packages/protocol` and Kotlin models/codecs in `apps/android/share`.
- `apps/relay-server` and `packages/mcp-server` are intentionally thin around protocol DTOs; protocol-first changes usually start in `packages/protocol`.
- The Android workspace mixes app modules and a shared JVM module (`share`) inside one Gradle root.

## COMMANDS
```bash
bun install --no-cache
bun run typecheck
bun run build
bun run dev:relay
bun test apps/relay-server/src packages/protocol/src packages/mcp-server/src
apps/android/gradlew -p apps/android :share:test
apps/android/gradlew -p apps/android :phone-app:assembleDebug :glasses-app:assembleDebug
```

## NOTES
- Start each task by reading the relevant docs and the nearest AGENTS.md in the directory tree.
- When implementing a new feature, create a local branch named `feature/feature-name` first.
- Commit messages must use one of: `feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `test:`, `chore:`.
- `packages/shared-types` is currently small and flat; keep guidance at the root unless that package grows materially.
