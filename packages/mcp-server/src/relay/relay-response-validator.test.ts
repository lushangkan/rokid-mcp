import { describe, expect, test } from "bun:test";

import { validateRelayGetDeviceStatusResponse } from "./relay-response-validator.js";

describe("validateRelayGetDeviceStatusResponse", () => {
  test("returns success payload when response is valid", () => {
    const result = validateRelayGetDeviceStatusResponse({
      ok: true,
      device: {
        deviceId: "rokid_glasses_01",
        connected: true,
        sessionState: "ONLINE",
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
        activeCommandRequestId: null,
        lastErrorCode: null,
        lastErrorMessage: null,
        lastSeenAt: 1710000000000,
        sessionId: "ses_abcdef",
      },
      timestamp: 1710000000001,
    });

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.device.deviceId).toBe("rokid_glasses_01");
    }
  });

  test("returns relay error payload when relay returns standard error response", () => {
    const result = validateRelayGetDeviceStatusResponse({
      ok: false,
      error: {
        code: "RELAY_DEVICE_NOT_FOUND",
        message: "Device not found",
        retryable: false,
      },
      timestamp: 1710000000002,
    });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe("RELAY_DEVICE_NOT_FOUND");
      expect(result.error.message).toBe("Device not found");
      expect(result.error.retryable).toBe(false);
    }
  });

  test("maps schema mismatch to MCP_RELAY_RESPONSE_INVALID", () => {
    const result = validateRelayGetDeviceStatusResponse({
      ok: true,
      device: {
        deviceId: "rokid_glasses_01",
        connected: true,
      },
      timestamp: 1710000000003,
    });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe("MCP_RELAY_RESPONSE_INVALID");
    }
  });
});
