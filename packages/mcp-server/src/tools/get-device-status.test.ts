import { describe, expect, test } from "bun:test";

import { RelayRequestError } from "../lib/errors.js";
import { createGetDeviceStatusTool, GET_DEVICE_STATUS_TOOL_NAME } from "./get-device-status.js";

describe("createGetDeviceStatusTool", () => {
  test("returns mapped result for successful relay response", async () => {
    const tool = createGetDeviceStatusTool({
      relayClient: {
        async getDeviceStatus() {
          return {
            ok: true,
            device: {
              deviceId: "rokid_glasses_01",
              connected: true,
              sessionState: "ONLINE",
              setupState: "INITIALIZED",
              runtimeState: "READY",
              uplinkState: "ONLINE",
              capabilities: ["display_text"],
              activeCommandRequestId: null,
              lastErrorCode: null,
              lastErrorMessage: null,
              lastSeenAt: 1710000000000,
              sessionId: "ses_abcdef",
            },
            timestamp: 1710000000001,
          };
        },
      },
    });

    const result = await tool.handler({ deviceId: "rokid_glasses_01" });

    expect(tool.name).toBe(GET_DEVICE_STATUS_TOOL_NAME);
    expect(result.isError).toBeUndefined();
    expect(result.structuredContent?.device).toBeDefined();
  });

  test("returns MCP_INVALID_PARAMS for invalid input", async () => {
    const tool = createGetDeviceStatusTool({
      relayClient: {
        async getDeviceStatus() {
          throw new Error("should not be called");
        },
      },
    });

    const result = await tool.handler({});
    expect(result.isError).toBe(true);
    expect(result.error?.code).toBe("MCP_INVALID_PARAMS");
  });

  test("returns relay error payload when relay client throws", async () => {
    const tool = createGetDeviceStatusTool({
      relayClient: {
        async getDeviceStatus() {
          throw new RelayRequestError("MCP_RELAY_REQUEST_FAILED", "relay request failed", true);
        },
      },
    });

    const result = await tool.handler({ deviceId: "rokid_glasses_01" });
    expect(result.isError).toBe(true);
    expect(result.error?.code).toBe("MCP_RELAY_REQUEST_FAILED");
  });
});
