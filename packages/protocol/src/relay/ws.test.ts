import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
  RelayHelloAckMessageSchema,
  RelayDeviceInboundMessageSchema,
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
        phoneInfo: {},
        setupState: "INITIALIZED",
        runtimeState: "CONNECTING",
        uplinkState: "CONNECTING",
        capabilities: ["display_text"],
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
        uplinkState: "ONLINE",
        pendingCommandCount: 0,
        activeCommandRequestId: null,
      },
    };

    expect(Value.Check(RelayHeartbeatMessageSchema, message)).toBe(true);
    expect(Value.Check(RelayDeviceInboundMessageSchema, message)).toBe(true);
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
});
