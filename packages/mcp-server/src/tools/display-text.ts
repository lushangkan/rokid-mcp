import { MAX_DISPLAY_TEXT_DURATION_MS, MAX_DISPLAY_TEXT_LENGTH, type DeviceId } from "@rokid-mcp/protocol";
import { z } from "zod";

import { RelayRequestError } from "../lib/errors.js";
import { logger } from "../lib/logger.js";
import type { McpToolResult } from "../mapper/mcp-result-mapper.js";
import type { DisplayTextUseCase } from "../usecases/display-text-usecase.js";

export const DISPLAY_TEXT_TOOL_NAME = "rokid.display_text";

export type DisplayTextTool = {
  name: string;
  description: string;
  inputSchema: unknown;
  handler(input: unknown): Promise<McpToolResult>;
};

export const displayTextMcpInputSchema = {
  text: z.string().trim().min(1).max(MAX_DISPLAY_TEXT_LENGTH),
  durationMs: z.number().int().min(1).max(MAX_DISPLAY_TEXT_DURATION_MS),
};

const displayTextInputParser = z.object(displayTextMcpInputSchema);

function toInvalidParamsResult(): McpToolResult {
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

function normalizeRelayError(error: unknown): RelayRequestError {
  if (error instanceof RelayRequestError) {
    return error;
  }

  return new RelayRequestError(
    "MCP_RELAY_REQUEST_FAILED",
    "Relay request failed",
    true,
    {
      cause: error instanceof Error ? error.message : String(error),
    },
  );
}

export function createDisplayTextTool(deps: { useCase: DisplayTextUseCase; defaultDeviceId: DeviceId }): DisplayTextTool {
  return {
    name: DISPLAY_TEXT_TOOL_NAME,
    description: "Display text on the default Rokid glasses device and wait for completion",
    inputSchema: displayTextInputParser,
    async handler(input: unknown): Promise<McpToolResult> {
      const parsedInput = displayTextInputParser.safeParse(input);
      if (!parsedInput.success) {
        return toInvalidParamsResult();
      }

      try {
        return await deps.useCase.execute({
          deviceId: deps.defaultDeviceId,
          text: parsedInput.data.text,
          durationMs: parsedInput.data.durationMs,
        });
      } catch (error) {
        const relayError = normalizeRelayError(error);
        logger.error("display-text tool failed", {
          error: relayError.message,
          code: relayError.code,
        });

        return toErrorResult(relayError);
      }
    },
  };
}
