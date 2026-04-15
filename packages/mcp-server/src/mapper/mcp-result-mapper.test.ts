import { describe, expect, test } from "bun:test";
import type { CommandRecord } from "@rokid-mcp/protocol";

import { mapCapturePhotoResult, mapDisplayTextResult } from "./mcp-result-mapper.js";

describe("result mapper", () => {
  test("result mapper formats successful display text results without raw relay JSON blobs", () => {
    const result = mapDisplayTextResult(
      createCommandRecord("display_text", "COMPLETED", {
        result: {
          action: "display_text",
          displayed: true,
          durationMs: 2_000,
        },
        completedAt: 1_710_000_000_300,
      }),
    );

    expect(result.isError).toBeUndefined();
    expect(result.content).toEqual([
      {
        type: "text",
        text: "Displayed the requested text for 2000 ms.",
      },
    ]);
    expect(result.structuredContent).toEqual({
      action: "display_text",
      status: "COMPLETED",
      outcome: {
        displayed: true,
        durationMs: 2_000,
      },
    });
  });

  test("result mapper formats failed display text results as MCP errors", () => {
    const result = mapDisplayTextResult(
      createCommandRecord("display_text", "FAILED", {
        error: {
          code: "BLUETOOTH_UNAVAILABLE",
          message: "Bluetooth is unavailable on the phone.",
          retryable: true,
        },
      }),
    );

    expect(result.isError).toBe(true);
    expect(result.content[0]).toEqual({
      type: "text",
      text: "Display text command failed: Bluetooth is unavailable on the phone.",
    });
    expect(result.error).toMatchObject({
      code: "BLUETOOTH_UNAVAILABLE",
      retryable: true,
    });
  });

  test("result mapper formats successful photo capture results with image content and metadata", () => {
    const result = mapCapturePhotoResult(
      createCommandRecord("capture_photo", "COMPLETED", {
        result: {
          action: "capture_photo",
          imageId: "img_photo123",
          transferId: "trf_photo123",
          mimeType: "image/jpeg",
          size: 3,
          width: 640,
          height: 480,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
        image: {
          imageId: "img_photo123",
          transferId: "trf_photo123",
          status: "UPLOADED",
          mimeType: "image/jpeg",
          size: 3,
          width: 640,
          height: 480,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          uploadedAt: 1_710_000_000_250,
        },
        completedAt: 1_710_000_000_300,
      }),
      {
        imageId: "img_photo123",
        transferId: "trf_photo123",
        status: "UPLOADED",
        mimeType: "image/jpeg",
        size: 3,
        sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        bytes: new Uint8Array([1, 2, 3]),
      },
    );

    expect(result.isError).toBeUndefined();
    expect(result.content).toEqual([
      {
        type: "text",
        text: "Captured a photo (640×480, 3 bytes).",
      },
      {
        type: "image",
        data: "AQID",
        mimeType: "image/jpeg",
      },
    ]);
    expect(result.structuredContent).toEqual({
      action: "capture_photo",
      status: "COMPLETED",
      image: {
        mimeType: "image/jpeg",
        size: 3,
        width: 640,
        height: 480,
        sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      },
    });
  });

  test("result mapper formats terminal photo failures without leaking raw relay records", () => {
    const result = mapCapturePhotoResult(
      createCommandRecord("capture_photo", "TIMEOUT", {
        error: {
          code: "TIMEOUT",
          message: "The device did not upload the photo in time.",
          retryable: true,
        },
        image: {
          imageId: "img_photo123",
          transferId: "trf_photo123",
          status: "FAILED",
          mimeType: "image/jpeg",
        },
      }),
      null,
    );

    expect(result.isError).toBe(true);
    expect(result.content[0]).toEqual({
      type: "text",
      text: "Capture photo command timeout: The device did not upload the photo in time.",
    });
    expect(result.structuredContent).toEqual({
      action: "capture_photo",
      status: "TIMEOUT",
      image: {
        mimeType: "image/jpeg",
      },
    });
  });
});

function createCommandRecord(
  action: CommandRecord["action"],
  status: CommandRecord["status"],
  overrides: Partial<CommandRecord> = {},
): CommandRecord {
  return {
    requestId: "req_mapper123",
    deviceId: "rokid_glasses_01",
    action,
    status,
    createdAt: 1_710_000_000_000,
    updatedAt: 1_710_000_000_100,
    acknowledgedAt: 1_710_000_000_050,
    completedAt: null,
    cancelledAt: null,
    result: null,
    error: null,
    image: null,
    ...overrides,
  };
}
