import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  CommandAckMessageSchema,
  CommandCancelMessageSchema,
  CommandDispatchMessageSchema,
  CommandErrorMessageSchema,
  CommandResultMessageSchema,
  CommandStatusMessageSchema,
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
  RelayHelloAckMessageSchema,
  RelayDeviceInboundMessageSchema,
  RelayDeviceOutboundMessageSchema,
  RelayPhoneStateUpdateMessageSchema,
} from "./ws.js";

describe("relay ws schema", () => {
  test("accepts hello message", () => {
    const message = {
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "dev_token_xxx",
        appVersion: "0.1.0",
        appBuild: "12",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        capabilities: ["display_text"],
      },
    };

    expect(Value.Check(RelayHelloMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
  });

  test("accepts hello message with explicit nulls from Android serializer", () => {
    const message = {
      version: "1.0",
      type: "hello",
      deviceId: "phone-fd296361",
      timestamp: 1775987393523,
      payload: {
        authToken: "JTEWnRMFhS1YppoV9+hAiQ==",
        appVersion: "1.0",
        appBuild: null,
        phoneInfo: {
          brand: null,
          model: null,
          androidVersion: null,
          sdkInt: null,
        },
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        capabilities: ["display_text", "capture_photo"],
        targetGlasses: null,
        relayConfig: null,
      },
    };

    expect(Value.Check(RelayHelloMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
  });

  test("accepts heartbeat message", () => {
    const message = {
      version: "1.0",
      type: "heartbeat",
      deviceId: "rokid_glasses_01",
      sessionId: "ses_abcdef",
      timestamp: 1710000001000,
      payload: {
        seq: 1,
        runtimeState: "READY",
        pendingCommandCount: 0,
        activeCommandRequestId: null,
      },
    };

    expect(Value.Check(RelayHeartbeatMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
  });

  test("rejects non-integer heartbeat counters", () => {
    const message = {
      version: "1.0",
      type: "heartbeat",
      deviceId: "rokid_glasses_01",
      sessionId: "ses_abcdef",
      timestamp: 1710000001000,
      payload: {
        seq: 1.5,
        runtimeState: "READY",
        pendingCommandCount: 0.1,
        activeCommandRequestId: null,
      },
    };

    expect(Value.Check(RelayHeartbeatMessageSchema, message)).toBe(false);
  });

  test("rejects empty authToken and appVersion", () => {
    const authTokenEmpty = {
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        capabilities: ["display_text"],
      },
    };

    const appVersionEmpty = {
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "dev_token_xxx",
        appVersion: "",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        capabilities: ["display_text"],
      },
    };

    expect(Value.Check(RelayHelloMessageSchema, authTokenEmpty)).toBe(false);
    expect(Value.Check(RelayHelloMessageSchema, appVersionEmpty)).toBe(false);
  });

  test("rejects extra top-level properties", () => {
    const message = {
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      debug: true,
      payload: {
        authToken: "dev_token_xxx",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        capabilities: ["display_text"],
      },
    };

    expect(Value.Check(RelayHelloMessageSchema, message)).toBe(false);
  });

  test("accepts phone_state_update message", () => {
    const message = {
      version: "1.0",
      type: "phone_state_update",
      deviceId: "rokid_glasses_01",
      sessionId: "ses_abcdef",
      timestamp: 1710000002000,
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        lastErrorCode: null,
        lastErrorMessage: null,
        activeCommandRequestId: null,
      },
    };

    expect(Value.Check(RelayPhoneStateUpdateMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
  });

  test("rejects legacy uplinkState in hello payload", () => {
    const message = {
      version: "1.0",
      type: "hello",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        authToken: "dev_token_xxx",
        appVersion: "0.1.0",
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"],
      },
    };

    expect(Value.Check(RelayHelloMessageSchema, message)).toBe(false);
  });

  test("rejects hello_ack from inbound union", () => {
    const message = {
      version: "1.0",
      type: "hello_ack",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        sessionId: "ses_abcdef",
        serverTime: 1710000000000,
        heartbeatIntervalMs: 5000,
        heartbeatTimeoutMs: 15000,
        limits: {
          maxPendingCommands: 1,
          maxImageUploadSizeBytes: 10485760,
          acceptedImageContentTypes: ["image/jpeg"],
        },
      },
    };

    expect(Value.Check(RelayHelloAckMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(false);
  });

  test("accepts relay command dispatch for capture_photo", () => {
    const message = {
      version: "1.0",
      type: "command",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000000000,
      payload: {
        action: "capture_photo",
        timeoutMs: 90_000,
        params: {
          quality: "medium",
        },
        image: {
          imageId: "img_abc123",
          transferId: "trf_abc123",
          uploadToken: "123456789012345678901234",
          contentType: "image/jpeg",
          expiresAt: 1710000090000,
          maxSizeBytes: 10485760,
        },
      },
    };

    expect(Value.Check(CommandDispatchMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceOutboundMessageSchema, message)).toBe(true);
  });

  test("accepts command_ack, status, result, and error messages", () => {
    const ack = {
      version: "1.0",
      type: "command_ack",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000001000,
      payload: {
        action: "capture_photo",
        acknowledgedAt: 1710000001000,
        runtimeState: "BUSY",
      },
    };

    const status = {
      version: "1.0",
      type: "command_status",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000002000,
      payload: {
        action: "capture_photo",
        status: "image_uploaded",
        statusAt: 1710000002000,
        image: {
          imageId: "img_abc123",
          transferId: "trf_abc123",
          uploadedAt: 1710000001800,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
      },
    };

    const result = {
      version: "1.0",
      type: "command_result",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000003000,
      payload: {
        completedAt: 1710000003000,
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
      },
    };

    const error = {
      version: "1.0",
      type: "command_error",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000003000,
      payload: {
        action: "capture_photo",
        failedAt: 1710000003000,
        error: {
          code: "UPLOAD_FAILED",
          message: "phone upload failed",
          retryable: true,
        },
      },
    };

    expect(Value.Check(CommandAckMessageSchema, ack)).toBe(true);
    expect(Value.Check(CommandStatusMessageSchema, status)).toBe(true);
    expect(Value.Check(CommandResultMessageSchema, result)).toBe(true);
    expect(Value.Check(CommandErrorMessageSchema, error)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, ack)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, status)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, result)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, error)).toBe(true);
  });

  test("accepts command_status with explicit null detail fields from Android serializer", () => {
    const status = {
      version: "1.0",
      type: "command_status",
      deviceId: "phone-fd296361",
      requestId: "req_2b12b01c_35ae_48d2_a939_cc84d7844df5",
      sessionId: "ses_cac2b1f7_e1bf_4c4f_b58d_68938496a291",
      timestamp: 1775993872516,
      payload: {
        action: "display_text",
        status: "forwarding_to_glasses",
        statusAt: 1775993872501,
        detailCode: null,
        detailMessage: null,
      },
    };

    expect(Value.Check(CommandStatusMessageSchema, status)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, status)).toBe(true);
  });

  test("accepts capture_photo image progress with explicit null upload timestamps from Android serializer", () => {
    const status = {
      version: "1.0",
      type: "command_status",
      deviceId: "phone-fd296361",
      requestId: "req_4979d40e_a041_4c1e_8c9d_e0bd995437b6",
      sessionId: "ses_30180601_a729_4d56_b7d1_7b0ecdd20428",
      timestamp: 1776075427084,
      payload: {
        action: "capture_photo",
        status: "image_captured",
        statusAt: 1776075426519,
        detailCode: null,
        detailMessage: null,
        image: {
          imageId: "img_bb12ee70_881b_404a_91c2_58dcd5c07652",
          transferId: "trf_440b8d97_9f9b_4c9d_aa3b_8325ea5c7ffe",
          uploadStartedAt: null,
          uploadedAt: null,
          sha256: "90434e8b1ec40f4aa051c986be1ab0d46647cd847c7ccb9398934db0a4f331e4",
        },
      },
    };

    expect(Value.Check(CommandStatusMessageSchema, status)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, status)).toBe(true);
  });

  test("accepts relay command_cancel message", () => {
    const message = {
      version: "1.0",
      type: "command_cancel",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000003000,
      payload: {
        action: "capture_photo",
        cancelledAt: 1710000003000,
        reasonCode: "TIMEOUT",
        reasonMessage: "relay cancelled after timeout",
      },
    };

    expect(Value.Check(CommandCancelMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceOutboundMessageSchema, message)).toBe(true);
  });

  test("rejects non-integer hello_ack limit and timing fields", () => {
    const message = {
      version: "1.0",
      type: "hello_ack",
      deviceId: "rokid_glasses_01",
      timestamp: 1710000000000,
      payload: {
        sessionId: "ses_abcdef",
        serverTime: 1710000000000,
        heartbeatIntervalMs: 5000.5,
        heartbeatTimeoutMs: 15000.5,
        sessionTtlMs: 30000.5,
        limits: {
          maxPendingCommands: 1.5,
          maxImageUploadSizeBytes: 10485760.5,
          acceptedImageContentTypes: ["image/jpeg"],
        },
      },
    };

    expect(Value.Check(RelayHelloAckMessageSchema, message)).toBe(false);
  });

  test("rejects image status messages without image metadata", () => {
    const message = {
      version: "1.0",
      type: "command_status",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000002000,
      payload: {
        action: "capture_photo",
        status: "image_uploaded",
        statusAt: 1710000002000,
      },
    };

    expect(Value.Check(CommandStatusMessageSchema, message)).toBe(false);
  });

  test("rejects command dispatch with non-jpeg content type", () => {
    const message = {
      version: "1.0",
      type: "command",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      sessionId: "ses_abcdef",
      timestamp: 1710000000000,
      payload: {
        action: "capture_photo",
        timeoutMs: 90_000,
        params: {},
        image: {
          imageId: "img_abc123",
          transferId: "trf_abc123",
          uploadToken: "123456789012345678901234",
          contentType: "image/png",
          expiresAt: 1710000090000,
          maxSizeBytes: 10485760,
        },
      },
    };

    expect(Value.Check(CommandDispatchMessageSchema, message)).toBe(false);
  });

  test("rejects command_error with unsupported terminal code", () => {
    const message = {
      version: "1.0",
      type: "command_error",
      deviceId: "rokid_glasses_01",
      requestId: "req_abc123",
      timestamp: 1710000003000,
      payload: {
        action: "display_text",
        failedAt: 1710000003000,
        error: {
          code: "DEVICE_BUSY",
          message: "device busy",
          retryable: true,
        },
      },
    };

    expect(Value.Check(CommandErrorMessageSchema, message)).toBe(false);
  });
});
