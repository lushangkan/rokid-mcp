import { Value } from "@sinclair/typebox/value";
import { GetDeviceStatusParamsSchema, type GetDeviceStatusParams } from "../../../protocol/src/index.js";
import type { RelayRequestError } from "../lib/errors.js";
import { logger } from "../lib/logger.js";
import { mapGetDeviceStatusResult, type McpToolResult } from "../mapper/result-mapper.js";
import type { RelayClient } from "../relay/relay-client.js";

export const GET_DEVICE_STATUS_TOOL_NAME = "rokid.get_device_status";

export type GetDeviceStatusTool = {
  name: string;
  description: string;
  inputSchema: unknown;
  handler(input: unknown): Promise<McpToolResult>;
};

function toErrorResult(error: RelayRequestError): McpToolResult {
  return {
    isError: true,
    content: [
      {
        type: "text",
        text: error.message,
      },
    ],
    error: {
      code: error.code,
      message: error.message,
      retryable: error.retryable,
      details: error.details,
    },
  };
}

export function createGetDeviceStatusTool(deps: { relayClient: RelayClient }): GetDeviceStatusTool {
  return {
    name: GET_DEVICE_STATUS_TOOL_NAME,
    description: "Get current relay status for a device",
    inputSchema: GetDeviceStatusParamsSchema,
    async handler(input: unknown): Promise<McpToolResult> {
      if (!Value.Check(GetDeviceStatusParamsSchema, input)) {
        return {
          isError: true,
          content: [
            {
              type: "text",
              text: "Invalid input",
            },
          ],
          error: {
            code: "MCP_INVALID_PARAMS",
            message: "Invalid input",
            retryable: false,
          },
        };
      }

      try {
        const result = await deps.relayClient.getDeviceStatus(input as GetDeviceStatusParams);
        return mapGetDeviceStatusResult(result);
      } catch (error) {
        logger.error("get-device-status tool failed", {
          error: error instanceof Error ? error.message : String(error),
        });

        return toErrorResult(error as RelayRequestError);
      }
    },
  };
}
