import { beforeEach, describe, expect, mock, test } from "bun:test";

import { createRelayCommandClient } from "./relay-command-client.js";

const originalFetch = globalThis.fetch;

describe("relay client - RelayCommandClient", () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("relay client submits commands through the shared protocol schema", async () => {
    const fetchMock = mock(async () =>
      new Response(
        JSON.stringify({
          ok: true,
          requestId: "req_submit123",
          deviceId: "rokid_glasses_01",
          action: "display_text",
          status: "CREATED",
          createdAt: 1_710_000_000_000,
          statusUrl: "https://relay.example.com/api/v1/commands/req_submit123",
        }),
        { status: 202 },
      ),
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const client = createRelayCommandClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3_000,
    });

    const result = await client.submitCommand({
      deviceId: "rokid_glasses_01",
      action: "display_text",
      payload: {
        text: "hello",
        durationMs: 1_500,
      },
    });

    expect(result.requestId).toBe("req_submit123");
    expect(fetchMock).toHaveBeenCalledWith(
      "https://relay.example.com/api/v1/commands",
      expect.objectContaining({
        method: "POST",
        headers: {
          Authorization: "Bearer relay-token",
          "content-type": "application/json",
        },
        body: JSON.stringify({
          deviceId: "rokid_glasses_01",
          action: "display_text",
          payload: {
            text: "hello",
            durationMs: 1_500,
          },
        }),
      }),
    );
  });

  test("relay client polls command status until the relay response validates", async () => {
    const fetchMock = mock(async () =>
      new Response(
        JSON.stringify({
          ok: true,
          command: {
            requestId: "req_status123",
            deviceId: "rokid_glasses_01",
            action: "display_text",
            status: "RUNNING",
            createdAt: 1_710_000_000_000,
            updatedAt: 1_710_000_000_100,
            acknowledgedAt: 1_710_000_000_050,
            completedAt: null,
            cancelledAt: null,
            result: null,
            error: null,
            image: null,
          },
          timestamp: 1_710_000_000_100,
        }),
        { status: 200 },
      ),
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const client = createRelayCommandClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3_000,
    });

    const result = await client.getCommandStatus("req_status123");

    expect(result.command.status).toBe("RUNNING");
    expect(fetchMock).toHaveBeenCalledWith(
      "https://relay.example.com/api/v1/commands/req_status123",
      expect.objectContaining({
        method: "GET",
        headers: {
          Authorization: "Bearer relay-token",
        },
      }),
    );
  });

  test("relay client downloads image bytes and validates response headers", async () => {
    const fetchMock = mock(async () =>
      new Response(new Uint8Array([1, 2, 3, 4]), {
        status: 200,
        headers: {
          "content-type": "image/jpeg",
          "x-image-id": "img_photo123",
          "x-transfer-id": "trf_photo123",
          "x-image-sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
      }),
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const client = createRelayCommandClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3_000,
    });

    const result = await client.downloadImage("img_photo123");

    expect(result.imageId).toBe("img_photo123");
    expect(result.transferId).toBe("trf_photo123");
    expect(result.bytes).toEqual(new Uint8Array([1, 2, 3, 4]));
    expect(fetchMock).toHaveBeenCalledWith(
      "https://relay.example.com/api/v1/images/img_photo123",
      expect.objectContaining({
        method: "GET",
        headers: {
          Authorization: "Bearer relay-token",
          accept: "image/jpeg",
        },
      }),
    );
  });

  test("relay client passes through relay JSON errors for image download failures", async () => {
    globalThis.fetch = mock(async () =>
      new Response(
        JSON.stringify({
          ok: false,
          error: {
            code: "IMAGE_NOT_FOUND",
            message: "Image not found",
            retryable: false,
          },
          timestamp: 1_710_000_000_200,
        }),
        {
          status: 404,
          headers: {
            "content-type": "application/json",
          },
        },
      ),
    ) as unknown as typeof fetch;

    const client = createRelayCommandClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3_000,
    });

    try {
      await client.downloadImage("img_missing123");
      throw new Error("expected downloadImage to throw");
    } catch (error) {
      expect(error).toMatchObject({
        code: "IMAGE_NOT_FOUND",
      });
    }
  });

  test.each([
    {
      name: "command submission",
      responseHeaders: undefined,
      invoke: (client: ReturnType<typeof createRelayCommandClient>) =>
        client.submitCommand({
          deviceId: "rokid_glasses_01",
          action: "display_text",
          payload: {
            text: "hello",
            durationMs: 1_500,
          },
        }),
    },
    {
      name: "command status",
      responseHeaders: undefined,
      invoke: (client: ReturnType<typeof createRelayCommandClient>) => client.getCommandStatus("req_status123"),
    },
    {
      name: "image download",
      responseHeaders: {
        "content-type": "application/json",
      },
      invoke: (client: ReturnType<typeof createRelayCommandClient>) => client.downloadImage("img_photo123"),
    },
  ])("relay client passes through relay auth failures for $name", async ({ responseHeaders, invoke }) => {
    globalThis.fetch = mock(async () =>
      new Response(
        JSON.stringify({
          ok: false,
          error: {
            code: "AUTH_HTTP_BEARER_INVALID",
            message: "Authorization header must contain a valid bearer token.",
            retryable: false,
          },
          timestamp: 1_710_000_000_200,
        }),
        {
          status: 401,
          headers: responseHeaders,
        },
      ),
    ) as unknown as typeof fetch;

    const client = createRelayCommandClient({
      relayBaseUrl: "https://relay.example.com",
      relayHttpAuthToken: "relay-token",
      requestTimeoutMs: 3_000,
    });

    await expect(invoke(client)).rejects.toMatchObject({
      code: "AUTH_HTTP_BEARER_INVALID",
      message: "Authorization header must contain a valid bearer token.",
      retryable: false,
    });
  });
});
