import type { GetDeviceStatusResponse } from "../../../protocol/src/index.js";

export type McpToolResult = {
  content: Array<{
    type: "text";
    text: string;
  }>;
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
  error?: {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
  };
};

export function mapGetDeviceStatusResult(result: GetDeviceStatusResponse): McpToolResult {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(result),
      },
    ],
    structuredContent: {
      device: result.device,
      timestamp: result.timestamp,
    },
  };
}
