import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
  RelayPhoneStateUpdateMessageSchema,
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
        uplinkState: "ONLINE",
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
        uplinkState: "CONNECTING",
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
        uplinkState: "CONNECTING",
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
        uplinkState: "CONNECTING",
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
        uplinkState: "ONLINE",
        lastErrorCode: null,
        lastErrorMessage: null,
        activeCommandRequestId: null,
      },
    };

    expect(Value.Check(RelayPhoneStateUpdateMessageSchema, message)).toBe(true);
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
        sessionTtlMs: 30000,
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
});
