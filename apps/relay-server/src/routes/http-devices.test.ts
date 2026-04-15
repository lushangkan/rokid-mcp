import { describe, expect, test } from "bun:test";
import { Elysia } from "elysia";

import { createRelayHttpAuthMiddleware } from "../lib/auth-middleware.ts";
import { DeviceSessionManager } from "../modules/device/device-session-manager.ts";
import { createHttpDevicesRoutes } from "./http-devices.ts";

function createManager() {
  return new DeviceSessionManager({
    heartbeatTimeoutMs: 50,
    cleanupIntervalMs: 10,
  });
}

describe("http device routes", () => {
  test("rejects missing or invalid bearer auth before the status handler runs", async () => {
    let statusCalls = 0;
    const app = new Elysia()
      .use(createRelayHttpAuthMiddleware({ httpAuthTokens: ["mcp-token-1"] }))
      .use(
        createHttpDevicesRoutes({
          manager: {
            getCurrentDeviceStatus(deviceId: string) {
              statusCalls += 1;
              return {
                device: {
                  deviceId,
                  connected: false,
                  sessionState: "OFFLINE",
                  sessionId: null,
                },
                timestamp: Date.now(),
              };
            },
          } as unknown as DeviceSessionManager,
        }),
      );

    for (const headers of [undefined, { authorization: "Bearer wrong-token" }]) {
      const response = await app.handle(
        new Request("http://localhost/api/v1/devices/device-protected/status", { headers }),
      );
      const json = await response.json();

      expect(response.status).toBe(401);
      expect(json.error.code).toBe("AUTH_HTTP_BEARER_INVALID");
    }

    expect(statusCalls).toBe(0);
  });

  test("returns synthetic offline when there is no current session", async () => {
    const manager = createManager();
    const app = createHttpDevicesRoutes({ manager });

    const response = await app.handle(
      new Request("http://localhost/api/v1/devices/device-missing/status"),
    );
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.device.deviceId).toBe("device-missing");
    expect(json.device.connected).toBe(false);
    expect(json.device.sessionState).toBe("OFFLINE");
  });

  test("returns synthetic offline for replaced device id", async () => {
    const manager = createManager();
    manager.registerHello({
      deviceId: "device-old",
      socketId: "socket-old",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });
    manager.registerHello({
      deviceId: "device-new",
      socketId: "socket-new",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        capabilities: ["capture_photo"],
      },
    });

    const app = createHttpDevicesRoutes({ manager });
    const response = await app.handle(
      new Request("http://localhost/api/v1/devices/device-old/status"),
    );
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.device.deviceId).toBe("device-old");
    expect(json.device.connected).toBe(false);
    expect(json.device.sessionState).toBe("OFFLINE");
    expect(json.device.sessionId).toBeNull();
  });

  test("phone_state_update is observable through status queries", async () => {
    const manager = createManager();
    const sessionId = manager.registerHello({
      deviceId: "device-live",
      socketId: "socket-live",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    manager.applyPhoneStateUpdate({
      deviceId: "device-live",
      sessionId,
      socketId: "socket-live",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        activeCommandRequestId: null,
        lastErrorCode: "PHONE_ERR",
        lastErrorMessage: "phone runtime changed",
      },
    });

    const app = createHttpDevicesRoutes({ manager });
    const response = await app.handle(
      new Request("http://localhost/api/v1/devices/device-live/status"),
    );
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.device.deviceId).toBe("device-live");
    expect(json.device.runtimeState).toBe("BUSY");
    expect(json.device.lastErrorCode).toBe("PHONE_ERR");
    expect(json.device.lastErrorMessage).toBe("phone runtime changed");
    expect(json.device.sessionId).toBe(sessionId);
  });
});
