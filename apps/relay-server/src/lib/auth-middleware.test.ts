import { describe, expect, test } from "bun:test";
import { Elysia } from "elysia";

import {
  createRelayHttpAuthMiddleware,
  isRelayDeviceWebSocketUpgradeRequest,
} from "./auth-middleware.ts";

const TEST_HTTP_AUTH_TOKENS = ["mcp-token-1"];

function createRequest(path: string, init?: RequestInit) {
  return new Request(`http://localhost${path}`, init);
}

function createAppWithAuthGate() {
  const calls = {
    health: 0,
    deviceStatus: 0,
    commandSubmit: 0,
    commandStatus: 0,
    imageUpload: 0,
    imageDownload: 0,
  };

  const app = new Elysia()
    .use(createRelayHttpAuthMiddleware({ httpAuthTokens: TEST_HTTP_AUTH_TOKENS }))
    .get("/health", () => {
      calls.health += 1;
      return { ok: true };
    })
    .get("/api/v1/devices/:deviceId/status", () => {
      calls.deviceStatus += 1;
      return { ok: true };
    })
    .post("/api/v1/commands", () => {
      calls.commandSubmit += 1;
      return { ok: true };
    })
    .get("/api/v1/commands/:requestId", () => {
      calls.commandStatus += 1;
      return { ok: true };
    })
    .put("/api/v1/images/:imageId", () => {
      calls.imageUpload += 1;
      return { ok: true };
    })
    .get("/api/v1/images/:imageId", () => {
      calls.imageDownload += 1;
      return { ok: true };
    });

  return { app, calls };
}

describe("relay HTTP auth middleware", () => {
  test("detects only real device websocket upgrade requests for auth bypass", () => {
    expect(
      isRelayDeviceWebSocketUpgradeRequest(
        createRequest("/ws/device", {
          headers: {
            upgrade: "websocket",
          },
        }),
      ),
    ).toBe(true);

    expect(isRelayDeviceWebSocketUpgradeRequest(createRequest("/ws/device"))).toBe(false);
    expect(
      isRelayDeviceWebSocketUpgradeRequest(
        createRequest("/api/v1/devices/device-a/status", {
          headers: {
            upgrade: "websocket",
          },
        }),
      ),
    ).toBe(false);
  });

  test("allows GET /health without bearer auth", async () => {
    const { app, calls } = createAppWithAuthGate();

    const response = await app.handle(createRequest("/health"));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(calls.health).toBe(1);
  });

  test("bypasses bearer auth for PUT /api/v1/images/:imageId", async () => {
    const { app, calls } = createAppWithAuthGate();

    const response = await app.handle(
      createRequest("/api/v1/images/img_00000000_0000_4000_8000_000000000001", {
        method: "PUT",
      }),
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(calls.imageUpload).toBe(1);
  });

  test("keeps exempt upload route public even with an invalid bearer header", async () => {
    const { app, calls } = createAppWithAuthGate();

    const response = await app.handle(
      createRequest("/api/v1/images/img_00000000_0000_4000_8000_000000000001", {
        method: "PUT",
        headers: {
          authorization: "Bearer wrong-token",
        },
      }),
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(calls.imageUpload).toBe(1);
  });

  test.each([
    {
      name: "device status",
      request: createRequest("/api/v1/devices/device-a/status"),
      callKey: "deviceStatus",
    },
    {
      name: "command submission",
      request: createRequest("/api/v1/commands", { method: "POST" }),
      callKey: "commandSubmit",
    },
    {
      name: "command status",
      request: createRequest("/api/v1/commands/req_00000000_0000_4000_8000_000000000001"),
      callKey: "commandStatus",
    },
    {
      name: "image download",
      request: createRequest("/api/v1/images/img_00000000_0000_4000_8000_000000000001"),
      callKey: "imageDownload",
    },
  ] as const)("rejects missing bearer before %s handler runs", async ({ request, callKey }) => {
    const { app, calls } = createAppWithAuthGate();

    const response = await app.handle(request);
    const json = await response.json();

    expect(response.status).toBe(401);
    expect(json.error.code).toBe("AUTH_HTTP_BEARER_INVALID");
    expect(calls[callKey]).toBe(0);
  });

  test.each([
    {
      name: "malformed bearer",
      headerValue: "token-only",
    },
    {
      name: "invalid bearer",
      headerValue: "Bearer wrong-token",
    },
  ])("rejects $name on protected routes", async ({ headerValue }) => {
    const { app, calls } = createAppWithAuthGate();

    const response = await app.handle(
      createRequest("/api/v1/devices/device-a/status", {
        headers: {
          authorization: headerValue,
        },
      }),
    );
    const json = await response.json();

    expect(response.status).toBe(401);
    expect(json.error.code).toBe("AUTH_HTTP_BEARER_INVALID");
    expect(calls.deviceStatus).toBe(0);
  });

  test.each([
    {
      name: "device status",
      request: createRequest("/api/v1/devices/device-a/status", {
        headers: {
          authorization: "Bearer mcp-token-1",
        },
      }),
      callKey: "deviceStatus",
    },
    {
      name: "command submission",
      request: createRequest("/api/v1/commands", {
        method: "POST",
        headers: {
          authorization: "Bearer mcp-token-1",
        },
      }),
      callKey: "commandSubmit",
    },
    {
      name: "command status",
      request: createRequest("/api/v1/commands/req_00000000_0000_4000_8000_000000000001", {
        headers: {
          authorization: "Bearer mcp-token-1",
        },
      }),
      callKey: "commandStatus",
    },
    {
      name: "image download",
      request: createRequest("/api/v1/images/img_00000000_0000_4000_8000_000000000001", {
        headers: {
          authorization: "Bearer mcp-token-1",
        },
      }),
      callKey: "imageDownload",
    },
  ] as const)("allows valid bearer auth on protected %s route", async ({ request, callKey }) => {
    const { app, calls } = createAppWithAuthGate();

    const response = await app.handle(request);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(calls[callKey]).toBe(1);
  });
});
