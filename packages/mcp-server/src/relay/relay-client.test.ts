import { beforeEach, describe, expect, mock, test } from "bun:test";

import { RelayRequestError } from "../lib/errors.js";
import { createRelayClient } from "./relay-client.js";

const originalFetch = globalThis.fetch;

describe("createRelayClient", () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("returns parsed payload for successful relay response", async () => {
    const fetchMock = mock(async () =>
      new Response(
        JSON.stringify({
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
        }),
        { status: 200 }
      )
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const client = createRelayClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3000,
    });

    const result = await client.getDeviceStatus({ deviceId: "rokid_glasses_01" });
    expect(result.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      "https://relay.example.com/api/v1/devices/rokid_glasses_01/status",
      expect.objectContaining({
        method: "GET",
        headers: {
          Authorization: "Bearer relay-token",
        },
      }),
    );
  });

  test("maps network failure to MCP_RELAY_REQUEST_FAILED", async () => {
    globalThis.fetch = mock(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;

    const client = createRelayClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3000,
    });

    await expect(client.getDeviceStatus({ deviceId: "rokid_glasses_01" })).rejects.toMatchObject({
      code: "MCP_RELAY_REQUEST_FAILED",
    });
  });

  test("maps invalid json response to MCP_RELAY_REQUEST_FAILED", async () => {
    globalThis.fetch = mock(async () => new Response("not-json", { status: 200 })) as unknown as typeof fetch;

    const client = createRelayClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3000,
    });

    await expect(client.getDeviceStatus({ deviceId: "rokid_glasses_01" })).rejects.toMatchObject({
      code: "MCP_RELAY_REQUEST_FAILED",
    });
  });

  test("maps schema validation failure to MCP_RELAY_RESPONSE_INVALID", async () => {
    globalThis.fetch = mock(async () =>
      new Response(
        JSON.stringify({
          ok: true,
          device: {
            deviceId: "rokid_glasses_01",
          },
          timestamp: 1710000000001,
        }),
        { status: 200 }
      )
    ) as unknown as typeof fetch;

    const client = createRelayClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3000,
    });

    await expect(client.getDeviceStatus({ deviceId: "rokid_glasses_01" })).rejects.toMatchObject({
      code: "MCP_RELAY_RESPONSE_INVALID",
    });
  });

  test("passes through relay standard error code", async () => {
    globalThis.fetch = mock(async () =>
      new Response(
        JSON.stringify({
          ok: false,
          error: {
            code: "RELAY_DEVICE_NOT_FOUND",
            message: "Device not found",
            retryable: false,
          },
          timestamp: 1710000000001,
        }),
        { status: 404 }
      )
    ) as unknown as typeof fetch;

    const client = createRelayClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3000,
    });

    try {
      await client.getDeviceStatus({ deviceId: "rokid_glasses_01" });
      throw new Error("expected getDeviceStatus to throw");
    } catch (error) {
      expect(error).toBeInstanceOf(RelayRequestError);
      expect(error).toMatchObject({
        code: "RELAY_DEVICE_NOT_FOUND",
      });
    }
  });

  test("passes through relay auth errors for protected status requests", async () => {
    globalThis.fetch = mock(async () =>
      new Response(
        JSON.stringify({
          ok: false,
          error: {
            code: "AUTH_HTTP_BEARER_INVALID",
            message: "Authorization header must contain a valid bearer token.",
            retryable: false,
          },
          timestamp: 1710000000001,
        }),
        { status: 401 },
      ),
    ) as unknown as typeof fetch;

    const client = createRelayClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3000,
    });

    await expect(client.getDeviceStatus({ deviceId: "rokid_glasses_01" })).rejects.toMatchObject({
      code: "AUTH_HTTP_BEARER_INVALID",
      message: "Authorization header must contain a valid bearer token.",
      retryable: false,
    });
  });
});
