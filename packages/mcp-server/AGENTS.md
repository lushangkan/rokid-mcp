# MCP SERVER PACKAGE GUIDE

## OVERVIEW
`packages/mcp-server` is a library package that wraps relay APIs as MCP tools; it is not the relay runtime itself.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Public surface | `src/index.ts` | package entry exports |
| Server assembly | `src/server.ts` | creates MCP server and registers tools |
| Env parsing | `src/config/env.ts` | relay base URL + timeout validation |
| Relay transport | `src/relay/` | HTTP client + response validation |
| Tool handlers | `src/tools/` | MCP tool names, input validation, handler logic |
| Result shaping | `src/mapper/result-mapper.ts` | relay DTO → MCP-friendly output |
| Tests | `src/**/*.test.ts` | bun:test coverage alongside source |

## CONVENTIONS
- Keep `server.ts` focused on dependency assembly and tool registration.
- Keep relay HTTP concerns in `src/relay/`; keep MCP-facing output mapping in `src/mapper/`.
- Tool handlers validate input, call relay clients, normalize relay errors, and return MCP-shaped results.
- A new tool is not complete until it is registered in `src/server.ts` and covered by a nearby `*.test.ts`.
- The package depends on `packages/protocol` for shared TS-side schema/DTO definitions.

## ANTI-PATTERNS
- Do not fold relay HTTP logic directly into `server.ts` or individual tool registration blocks.
- Do not skip relay response validation when adding new relay endpoints.
- Do not rename tool identifiers casually; MCP consumers depend on stable names like `rokid.get_device_status`.

## NOTES
- `createMcpServer` is the package entrypoint; `createGetDeviceStatusTool` is the clearest existing pattern for future tools.
- This package is thin by design: relay client, validation, tool handler, and mapper should stay separated.
