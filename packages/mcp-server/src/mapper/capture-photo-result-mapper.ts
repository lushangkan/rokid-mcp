import type { CommandRecord, TerminalError } from "@rokid-mcp/protocol";

import type { VerifiedDownloadedImage } from "../image/image-verifier.js";
import type { McpToolResult } from "./mcp-result-mapper.js";

export type RokidCapturePhotoOutput = {
  requestId: string;
  action: "capture_photo";
  completedAt: number;
  image: {
    imageId: string;
    mimeType: string;
    size: number;
    width: number;
    height: number;
    sha256?: string;
    dataBase64: string;
  };
};

export type CapturePhotoResultMapper = {
  toSuccessResult(input: {
    requestId: string;
    completedAt: number;
    image: VerifiedDownloadedImage;
    dataBase64: string;
  }): McpToolResult;
  toTerminalFailureResult(command: CommandRecord): McpToolResult;
};

export function createCapturePhotoResultMapper(): CapturePhotoResultMapper {
  return {
    toSuccessResult({ requestId, completedAt, image, dataBase64 }) {
      const output: RokidCapturePhotoOutput = {
        requestId,
        action: "capture_photo",
        completedAt,
        image: {
          imageId: image.imageId,
          mimeType: image.mimeType,
          size: image.size,
          width: image.width,
          height: image.height,
          ...(image.sha256 ? { sha256: image.sha256 } : {}),
          dataBase64,
        },
      };

      return {
        content: [
          {
            type: "text",
            text: `Captured a photo (${image.width}×${image.height}, ${image.size} bytes).`,
          },
        ],
        structuredContent: output,
      };
    },

    toTerminalFailureResult(command) {
      if (command.action !== "capture_photo") {
        return createMapperErrorResult(
          "MCP_RESULT_MAPPING_INVALID",
          "Capture photo mapper received a non-capture_photo command.",
        );
      }

      const message = toFailureMessage(command.status, command.error);

      return {
        isError: true,
        content: [
          {
            type: "text",
            text: message,
          },
        ],
        structuredContent: {
          requestId: command.requestId,
          action: "capture_photo",
          status: command.status,
          ...(command.completedAt ? { completedAt: command.completedAt } : {}),
          ...(command.image
            ? {
                image: {
                  imageId: command.image.imageId,
                  mimeType: command.image.mimeType,
                  ...(command.image.size ? { size: command.image.size } : {}),
                  ...(command.image.width ? { width: command.image.width } : {}),
                  ...(command.image.height ? { height: command.image.height } : {}),
                  ...(command.image.sha256 ? { sha256: command.image.sha256 } : {}),
                },
              }
            : {}),
        },
        error: {
          code: command.error?.code ?? command.status,
          message,
          retryable: command.error?.retryable ?? command.status !== "CANCELLED",
          ...(command.error?.details ? { details: command.error.details } : {}),
        },
      };
    },
  };
}

function createMapperErrorResult(code: string, message: string): McpToolResult {
  return {
    isError: true,
    content: [
      {
        type: "text",
        text: message,
      },
    ],
    error: {
      code,
      message,
      retryable: false,
    },
  };
}

function toFailureMessage(status: CommandRecord["status"], error: TerminalError | null): string {
  if (error) {
    return `Capture photo command ${status.toLowerCase()}: ${error.message}`;
  }

  switch (status) {
    case "FAILED":
      return "Capture photo command failed.";
    case "TIMEOUT":
      return "Capture photo command timed out before the device finished it.";
    case "CANCELLED":
      return "Capture photo command was cancelled before completion.";
    default:
      return `Capture photo command ended with status ${status}.`;
  }
}
