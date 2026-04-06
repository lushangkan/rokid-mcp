import type { CommandRecord, DisplayTextCommandResult, CapturePhotoCommandResult, TerminalError } from "@rokid-mcp/protocol";

import type { DownloadedRelayImage } from "../relay/relay-command-client.js";

export type McpToolContent =
  | {
      type: "text";
      text: string;
    }
  | {
      type: "image";
      data: string;
      mimeType: string;
    };

export type McpToolResult = {
  content: McpToolContent[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
  error?: {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>;
  };
};

export function mapDisplayTextResult(command: CommandRecord): McpToolResult {
  if (command.action !== "display_text") {
    return createMapperErrorResult("MCP_RESULT_MAPPING_INVALID", "Display text mapper received a non-display_text command.");
  }

  if (command.status === "COMPLETED") {
    const result = command.result;
    if (!isDisplayTextCommandResult(result)) {
      return createMapperErrorResult(
        "MCP_RESULT_MAPPING_INVALID",
        "Relay completed the display text command without a usable result.",
      );
    }

    return {
      content: [
        {
          type: "text",
          text: `Displayed the requested text for ${result.durationMs} ms.`,
        },
      ],
      structuredContent: {
        action: "display_text",
        status: "COMPLETED",
        outcome: {
          displayed: result.displayed,
          durationMs: result.durationMs,
        },
      },
    };
  }

  return mapTerminalFailure(command, "display_text");
}

export function mapCapturePhotoResult(command: CommandRecord, imageData: DownloadedRelayImage | null): McpToolResult {
  if (command.action !== "capture_photo") {
    return createMapperErrorResult("MCP_RESULT_MAPPING_INVALID", "Capture photo mapper received a non-capture_photo command.");
  }

  if (command.status === "COMPLETED") {
    const result = command.result;
    if (!isCapturePhotoCommandResult(result) || !imageData) {
      return createMapperErrorResult(
        "MCP_RESULT_MAPPING_INVALID",
        "Relay completed the photo command without a usable image payload.",
      );
    }

    if (result.imageId !== imageData.imageId || result.mimeType !== imageData.mimeType) {
      return createMapperErrorResult(
        "MCP_RESULT_MAPPING_INVALID",
        "Downloaded image metadata did not match the completed command result.",
      );
    }

    return {
      content: [
        {
          type: "text",
          text: `Captured a photo (${result.width}×${result.height}, ${result.size} bytes).`,
        },
        {
          type: "image",
          data: toBase64(imageData.bytes),
          mimeType: imageData.mimeType,
        },
      ],
      structuredContent: {
        action: "capture_photo",
        status: "COMPLETED",
        image: {
          mimeType: imageData.mimeType,
          size: result.size,
          width: result.width,
          height: result.height,
          ...(result.sha256 ? { sha256: result.sha256 } : {}),
        },
      },
    };
  }

  return mapTerminalFailure(command, "capture_photo");
}

function mapTerminalFailure(command: CommandRecord, action: "display_text" | "capture_photo"): McpToolResult {
  const message = toFailureMessage(action, command.status, command.error);

  return {
    isError: true,
    content: [
      {
        type: "text",
        text: message,
      },
    ],
    structuredContent: {
      action,
      status: command.status,
      ...(command.image
        ? {
            image: {
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

function toFailureMessage(
  action: "display_text" | "capture_photo",
  status: CommandRecord["status"],
  error: TerminalError | null,
): string {
  const actionLabel = action === "display_text" ? "Display text" : "Capture photo";

  if (error) {
    return `${actionLabel} command ${status.toLowerCase()}: ${error.message}`;
  }

  switch (status) {
    case "FAILED":
      return `${actionLabel} command failed.`;
    case "TIMEOUT":
      return `${actionLabel} command timed out before the device finished it.`;
    case "CANCELLED":
      return `${actionLabel} command was cancelled before completion.`;
    default:
      return `${actionLabel} command ended with status ${status}.`;
  }
}

function isDisplayTextCommandResult(result: CommandRecord["result"]): result is DisplayTextCommandResult {
  return result?.action === "display_text";
}

function isCapturePhotoCommandResult(result: CommandRecord["result"]): result is CapturePhotoCommandResult {
  return result?.action === "capture_photo";
}

function toBase64(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64");
}
