import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  CommandStatusResponseSchema,
  GetDeviceStatusParamsSchema,
  GetDeviceStatusResponseSchema,
  ImageDownloadResponseSchema,
  ImageUploadRequestSchema,
  SubmitCommandRequestSchema,
  SubmitCommandResponseSchema,
} from "./http.js";

describe("relay http schema", () => {
  test("accepts device status params", () => {
    expect(
      Value.Check(GetDeviceStatusParamsSchema, { deviceId: "rokid_glasses_01" }),
    ).toBe(true);
  });

  test("accepts synthetic offline response", () => {
    const response = {
      ok: true,
      device: {
        deviceId: "rokid_glasses_01",
        connected: false,
        sessionState: "OFFLINE",
        setupState: "UNINITIALIZED",
        runtimeState: "DISCONNECTED",
        capabilities: [],
        activeCommandRequestId: null,
        lastErrorCode: null,
        lastErrorMessage: null,
        lastSeenAt: null,
        sessionId: null,
      },
      timestamp: 1710000000000,
    };

    expect(Value.Check(GetDeviceStatusResponseSchema, response)).toBe(true);
  });

  test("accepts display_text submit command request", () => {
    expect(
      Value.Check(SubmitCommandRequestSchema, {
        deviceId: "rokid_glasses_01",
        action: "display_text",
        payload: {
          text: "hello world",
          durationMs: 2_000,
        },
      }),
    ).toBe(true);
  });

  test("accepts capture_photo submit command response with reserved image", () => {
    expect(
      Value.Check(SubmitCommandResponseSchema, {
        ok: true,
        requestId: "req_abc123",
        deviceId: "rokid_glasses_01",
        action: "capture_photo",
        status: "CREATED",
        createdAt: 1710000000000,
        statusUrl: "/api/v1/commands/req_abc123",
        image: {
          imageId: "img_abc123",
          transferId: "trf_abc123",
          status: "RESERVED",
          mimeType: "image/jpeg",
          expiresAt: 1710000090000,
        },
      }),
    ).toBe(true);
  });

  test("accepts completed capture_photo command status response", () => {
    expect(
      Value.Check(CommandStatusResponseSchema, {
        ok: true,
        command: {
          requestId: "req_abc123",
          deviceId: "rokid_glasses_01",
          action: "capture_photo",
          status: "COMPLETED",
          createdAt: 1710000000000,
          updatedAt: 1710000005000,
          acknowledgedAt: 1710000001000,
          completedAt: 1710000005000,
          cancelledAt: null,
          result: {
            action: "capture_photo",
            imageId: "img_abc123",
            transferId: "trf_abc123",
            mimeType: "image/jpeg",
            size: 123456,
            width: 1024,
            height: 768,
            sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          },
          error: null,
          image: {
            imageId: "img_abc123",
            transferId: "trf_abc123",
            status: "UPLOADED",
            mimeType: "image/jpeg",
            size: 123456,
            width: 1024,
            height: 768,
            sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            uploadedAt: 1710000004000,
          },
        },
        timestamp: 1710000005000,
      }),
    ).toBe(true);
  });

  test("accepts image upload request metadata", () => {
    expect(
      Value.Check(ImageUploadRequestSchema, {
        imageId: "img_abc123",
        transferId: "trf_abc123",
        uploadToken: "123456789012345678901234",
        headers: {
          contentType: "image/jpeg",
          contentLength: 123456,
          deviceId: "rokid_glasses_01",
          requestId: "req_abc123",
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
      }),
    ).toBe(true);
  });

  test("accepts image download response metadata", () => {
    expect(
      Value.Check(ImageDownloadResponseSchema, {
        ok: true,
        image: {
          imageId: "img_abc123",
          transferId: "trf_abc123",
          status: "UPLOADED",
          mimeType: "image/jpeg",
          size: 123456,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
        timestamp: 1710000005000,
      }),
    ).toBe(true);
  });

  test("rejects malformed response", () => {
    const response = {
      ok: true,
      device: {
        deviceId: "rokid_glasses_01",
        connected: false,
        sessionState: "OFFLINE",
        setupState: "UNINITIALIZED",
        runtimeState: "DISCONNECTED",
        capabilities: [],
        activeCommandRequestId: null,
        lastErrorCode: null,
        lastErrorMessage: null,
        lastSeenAt: null,
        sessionId: null,
      },
      timestamp: "1710000000000",
    };

    expect(Value.Check(GetDeviceStatusResponseSchema, response)).toBe(false);
  });

  test("rejects legacy uplinkState on device status response", () => {
    expect(
      Value.Check(GetDeviceStatusResponseSchema, {
        ok: true,
        device: {
          deviceId: "rokid_glasses_01",
          connected: false,
          sessionState: "OFFLINE",
          setupState: "UNINITIALIZED",
          runtimeState: "DISCONNECTED",
          uplinkState: "OFFLINE",
          capabilities: [],
          activeCommandRequestId: null,
          lastErrorCode: null,
          lastErrorMessage: null,
          lastSeenAt: null,
          sessionId: null,
        },
        timestamp: 1710000000000,
      }),
    ).toBe(false);
  });

  test("rejects submit command payload drift", () => {
    expect(
      Value.Check(SubmitCommandRequestSchema, {
        deviceId: "rokid_glasses_01",
        action: "display_text",
        payload: {
          text: "hello world",
          durationMs: 2_000,
          priority: "high",
        },
      }),
    ).toBe(false);
  });

  test("rejects capture_photo status results with url-only payloads", () => {
    expect(
      Value.Check(CommandStatusResponseSchema, {
        ok: true,
        command: {
          requestId: "req_abc123",
          deviceId: "rokid_glasses_01",
          action: "capture_photo",
          status: "COMPLETED",
          createdAt: 1710000000000,
          updatedAt: 1710000005000,
          acknowledgedAt: 1710000001000,
          completedAt: 1710000005000,
          cancelledAt: null,
          result: {
            action: "capture_photo",
            url: "/api/v1/images/img_abc123",
          },
          error: null,
          image: null,
        },
        timestamp: 1710000005000,
      }),
    ).toBe(false);
  });

  test("rejects image upload requests without upload token", () => {
    expect(
      Value.Check(ImageUploadRequestSchema, {
        imageId: "img_abc123",
        transferId: "trf_abc123",
        headers: {
          contentType: "image/jpeg",
          deviceId: "rokid_glasses_01",
          requestId: "req_abc123",
        },
      }),
    ).toBe(false);
  });
});
