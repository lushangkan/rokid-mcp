import type { GetDeviceStatusResponse } from "@rokid-mcp/protocol";
import type { McpToolResult } from "./mcp-result-mapper.js";

export type { McpToolResult } from "./mcp-result-mapper.js";

export function mapGetDeviceStatusResult(result: GetDeviceStatusResponse): McpToolResult {
  return {
    content: [
      {
        type: "text",
        text: `Device ${result.device.deviceId} is ${result.device.connected ? "connected" : "disconnected"} with runtime state ${result.device.runtimeState}.`,
      },
    ],
    structuredContent: {
      device: result.device,
      timestamp: result.timestamp,
    },
  };
}
