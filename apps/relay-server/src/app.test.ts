import { describe, expect, test } from "bun:test";
import { createApp, createDefaultApp } from "./app.js";
import { readRelayEnv } from "./config/env.js";
import { DeviceSessionManager } from "./modules/device/device-session-manager.js";

const TEST_ENV = {
  host: "127.0.0.1",
  port: 3000,
  heartbeatIntervalMs: 10,
  heartbeatTimeoutMs: 50,
};

describe("relay app", () => {
  test("health route responds", async () => {
    const app = createApp({ env: TEST_ENV, manager: new DeviceSessionManager({ heartbeatTimeoutMs: 50, cleanupIntervalMs: 10 }) });
    const response = await app.handle(new Request("http://localhost/health"));
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.ok).toBe(true);
    expect(json.service).toBe("relay-server");
    expect(typeof json.protocol).toBe("string");
    expect(json.protocol.length).toBeGreaterThan(0);
    expect(typeof json.version).toBe("string");
    expect(json.version).toMatch(/^\d+\.\d+$/);
  });

  test("unknown route is not successful", async () => {
    const app = createApp({ env: TEST_ENV, manager: new DeviceSessionManager({ heartbeatTimeoutMs: 50, cleanupIntervalMs: 10 }) });
    const response = await app.handle(new Request("http://localhost/not-found"));

    expect(response.status).not.toBe(200);
  });

  test("device status route is wired", async () => {
    const app = createApp({
      env: TEST_ENV,
      manager: new DeviceSessionManager({ heartbeatTimeoutMs: 50, cleanupIntervalMs: 10 }),
    });

    const response = await app.handle(new Request("http://localhost/api/v1/devices/device-a/status"));
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.device.deviceId).toBe("device-a");
    expect(json.device.sessionState).toBe("OFFLINE");
  });

  test("createApp does not read process env when env and manager are provided", async () => {
    const previousPort = process.env.PORT;
    process.env.PORT = "not-a-number";

    try {
      const app = createApp({
        env: TEST_ENV,
        manager: new DeviceSessionManager({ heartbeatTimeoutMs: 50, cleanupIntervalMs: 10 }),
      });

      const response = await app.handle(new Request("http://localhost/health"));

      expect(response.status).toBe(200);
    } finally {
      if (previousPort === undefined) {
        delete process.env.PORT;
      } else {
        process.env.PORT = previousPort;
      }
    }
  });

  test("createApp wires websocket route registration", () => {
    const app = createApp({
      env: TEST_ENV,
      manager: new DeviceSessionManager({ heartbeatTimeoutMs: 50, cleanupIntervalMs: 10 }),
    });

    expect(app.router.history).toContainEqual(
      expect.objectContaining({
        method: "WS",
        path: "/ws/device",
      }),
    );
  });

  test("createDefaultApp starts cleanup for fallback manager", async () => {
    const app = createDefaultApp(TEST_ENV);

    const manager = app.store.manager as DeviceSessionManager;
    manager.registerHello({
      deviceId: "device-stale",
      socketId: "socket-stale",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    try {
      await Bun.sleep(80);

      const response = await app.handle(new Request("http://localhost/api/v1/devices/device-stale/status"));
      const json = await response.json();

      expect(json.device.sessionState).toBe("STALE");
      expect(json.device.runtimeState).toBe("DISCONNECTED");
    } finally {
      manager.stopCleanupJob();
    }
  });
});

describe("readRelayEnv", () => {
  test("throws on invalid numeric values", () => {
    expect(() =>
      readRelayEnv({
        PORT: "abc",
        HOST: "127.0.0.1",
        RELAY_HEARTBEAT_INTERVAL_MS: "5000",
        RELAY_HEARTBEAT_TIMEOUT_MS: "15000"
      })
    ).toThrow("Invalid numeric environment variable: PORT");
  });
});
