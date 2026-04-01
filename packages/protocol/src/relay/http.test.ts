import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";
import {
  GetDeviceStatusParamsSchema,
  GetDeviceStatusResponseSchema,
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
        uplinkState: "OFFLINE",
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

  test("rejects malformed response", () => {
    const response = {
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
      timestamp: "1710000000000",
    };

    expect(Value.Check(GetDeviceStatusResponseSchema, response)).toBe(false);
  });
});
