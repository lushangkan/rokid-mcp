import { describe, expect, test } from "bun:test";

import { ACTIVE_MCP_TOOL_NAMES, createMcpServer } from "./server.js";

describe("createMcpServer", () => {
  test("registers only the active cutover tools", () => {
    const server = createMcpServer({
      env: {
        relayBaseUrl: "http://localhost:3000",
        relayHttpAuthToken: "relay-http-token",
        requestTimeoutMs: 5_000,
        defaultDeviceId: "rokid_glasses_01",
        commandTimeoutMs: 30_000,
        commandPollIntervalMs: 250,
      },
    });

    const registeredToolNames = Object.keys((server as unknown as { _registeredTools: Record<string, unknown> })._registeredTools).sort();

    expect(registeredToolNames).toEqual([...ACTIVE_MCP_TOOL_NAMES].sort());
  });
});
