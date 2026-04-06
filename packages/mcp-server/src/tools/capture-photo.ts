import { CapturePhotoQualitySchema, type CapturePhotoQuality, type DeviceId } from "@rokid-mcp/protocol";
import { z } from "zod";

import { RelayRequestError } from "../lib/errors.js";
import { logger } from "../lib/logger.js";
import type { McpToolResult } from "../mapper/mcp-result-mapper.js";
import type { CapturePhotoUseCase } from "../usecases/capture-photo-usecase.js";

export const CAPTURE_PHOTO_TOOL_NAME = "rokid.capture_photo";

export type CapturePhotoTool = {
  name: string;
  description: string;
  inputSchema: unknown;
  handler(input: unknown): Promise<McpToolResult>;
};

const capturePhotoQualityOptions = getCapturePhotoQualityOptions();

export const capturePhotoMcpInputSchema = {
  quality: z.enum(capturePhotoQualityOptions).optional(),
};

const capturePhotoInputParser = z.object(capturePhotoMcpInputSchema);

export function createCapturePhotoTool(deps: {
  defaultDeviceId: DeviceId;
  useCase: CapturePhotoUseCase;
}): CapturePhotoTool {
  return {
    name: CAPTURE_PHOTO_TOOL_NAME,
    description: "Capture a JPEG photo on the default Rokid glasses device and return base64 image data",
    inputSchema: capturePhotoInputParser,
    async handler(input: unknown): Promise<McpToolResult> {
      const parsedInput = capturePhotoInputParser.safeParse(input);
      if (!parsedInput.success) {
        return invalidInputResult();
      }

      try {
        return await deps.useCase.execute({
          deviceId: deps.defaultDeviceId,
          quality: parsedInput.data.quality,
        });
      } catch (error) {
        const relayError = normalizeRelayError(error);
        logger.error("capture-photo tool failed", {
          toolName: CAPTURE_PHOTO_TOOL_NAME,
          deviceId: deps.defaultDeviceId,
          error: relayError.message,
          code: relayError.code,
        });

        return toErrorResult(relayError);
      }
    },
  };
}

function invalidInputResult(): McpToolResult {
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

  return new RelayRequestError("MCP_RELAY_REQUEST_FAILED", "Capture photo workflow failed", true, {
    cause: error instanceof Error ? error.message : String(error),
  });
}

function getCapturePhotoQualityOptions(): [CapturePhotoQuality, ...CapturePhotoQuality[]] {
  const options = (CapturePhotoQualitySchema as { anyOf?: Array<{ const?: CapturePhotoQuality }> }).anyOf
    ?.map((schema) => schema.const)
    .filter((value): value is CapturePhotoQuality => typeof value === "string");

  if (!options || options.length === 0) {
    return ["low", "medium", "high"];
  }

  return options as [CapturePhotoQuality, ...CapturePhotoQuality[]];
}
