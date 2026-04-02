import { describe, expect, test } from "bun:test";

import { DeviceSessionManager } from "./device-session-manager.js";

function createManager() {
  return new DeviceSessionManager({
    heartbeatTimeoutMs: 50,
    cleanupIntervalMs: 10,
  });
}

describe("DeviceSessionManager", () => {
  test("returns synthetic offline response for unknown device", () => {
    const manager = createManager();

    const status = manager.getCurrentDeviceStatus("device-unknown");

    expect(status.ok).toBe(true);
    expect(status.device.deviceId).toBe("device-unknown");
    expect(status.device.connected).toBe(false);
    expect(status.device.sessionState).toBe("OFFLINE");
    expect(status.device.setupState).toBe("UNINITIALIZED");
    expect(status.device.runtimeState).toBe("DISCONNECTED");
    expect(status.device.uplinkState).toBe("OFFLINE");
    expect(status.device.capabilities).toEqual([]);
    expect(status.device.activeCommandRequestId).toBeNull();
    expect(status.device.lastErrorCode).toBeNull();
    expect(status.device.lastErrorMessage).toBeNull();
    expect(status.device.lastSeenAt).toBeNull();
    expect(status.device.sessionId).toBeNull();
  });

  test("new hello replaces old session and keeps latest capability set", () => {
    const manager = createManager();

    const oldSessionId = manager.registerHello({
      deviceId: "device-a",
      socketId: "socket-old",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });
    manager.confirmHello("device-a", oldSessionId);

    const newSessionId = manager.registerHello({
      deviceId: "device-a",
      socketId: "socket-new",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        uplinkState: "ONLINE",
        capabilities: ["capture_photo"],
      },
    });

    const status = manager.getCurrentDeviceStatus("device-a");

    expect(newSessionId).not.toBe(oldSessionId);
    expect(newSessionId).toMatch(/^ses_[a-zA-Z0-9_-]{6,128}$/);
    expect(manager.matchesCurrentSession("device-a", oldSessionId, "socket-old")).toBe(false);
    expect(manager.matchesCurrentSession("device-a", newSessionId, "socket-new")).toBe(true);
    expect(status.device.connected).toBe(true);
    expect(status.device.sessionState).toBe("ONLINE");
    expect(status.device.sessionId).toBe(newSessionId);
    expect(status.device.capabilities).toEqual(["capture_photo"]);
  });

  test("late close from old socket does not clear current session", () => {
    const manager = createManager();

    const oldSessionId = manager.registerHello({
      deviceId: "device-b",
      socketId: "socket-old",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    const newSessionId = manager.registerHello({
      deviceId: "device-b",
      socketId: "socket-new",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text", "capture_photo"],
      },
    });

    manager.closeCurrentSession("device-b", oldSessionId, "socket-old");
    const status = manager.getCurrentDeviceStatus("device-b");

    expect(status.device.connected).toBe(true);
    expect(status.device.sessionState).toBe("ONLINE");
    expect(status.device.sessionId).toBe(newSessionId);
  });

  test("stale session forces disconnected runtime on query after cleanup interval fires", async () => {
    const manager = createManager();
    manager.startCleanupJob();

    try {
      manager.registerHello({
        deviceId: "device-stale",
        socketId: "socket-stale",
        timestamp: Date.now(),
        payload: {
          setupState: "INITIALIZED",
          runtimeState: "READY",
          uplinkState: "ONLINE",
          capabilities: ["display_text"],
        },
      });

      await Bun.sleep(80);

      const status = manager.getCurrentDeviceStatus("device-stale");

      expect(status.device.connected).toBe(false);
      expect(status.device.sessionState).toBe("STALE");
      expect(status.device.runtimeState).toBe("DISCONNECTED");
      expect(status.device.uplinkState).toBe("OFFLINE");
      expect(status.device.activeCommandRequestId).toBeNull();
    } finally {
      manager.stopCleanupJob();
    }
  });

  test("stale state is not mutated on read when cleanup interval is not running", async () => {
    const manager = createManager();

    manager.registerHello({
      deviceId: "device-no-cleanup",
      socketId: "socket-no-cleanup",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    await Bun.sleep(80);

    const status = manager.getCurrentDeviceStatus("device-no-cleanup");

    expect(status.device.sessionState).toBe("ONLINE");
  });

  test("closed session reports disconnected runtime semantics", () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-closed",
      socketId: "socket-closed",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    manager.closeCurrentSession("device-closed", sessionId, "socket-closed");
    const status = manager.getCurrentDeviceStatus("device-closed");

    expect(status.device.connected).toBe(false);
    expect(status.device.sessionState).toBe("CLOSED");
    expect(status.device.runtimeState).toBe("DISCONNECTED");
    expect(status.device.uplinkState).toBe("OFFLINE");
    expect(status.device.activeCommandRequestId).toBeNull();
  });

  test("phone state update keeps session fallback runtime fields consistent", () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-fallback",
      socketId: "socket-fallback",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    manager.applyPhoneStateUpdate({
      deviceId: "device-fallback",
      sessionId,
      socketId: "socket-fallback",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        uplinkState: "ERROR",
        activeCommandRequestId: null,
      },
    });

    (manager as unknown as { runtimes: { delete: (deviceId: string) => void } }).runtimes.delete("device-fallback");

    const status = manager.getCurrentDeviceStatus("device-fallback");
    expect(status.device.runtimeState).toBe("BUSY");
    expect(status.device.uplinkState).toBe("ERROR");
  });

  test("heartbeat keeps session fallback runtime fields consistent", () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-heartbeat-fallback",
      socketId: "socket-heartbeat-fallback",
      timestamp: Date.now(),
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    manager.markHeartbeat({
      deviceId: "device-heartbeat-fallback",
      sessionId,
      socketId: "socket-heartbeat-fallback",
      timestamp: Date.now(),
      payload: {
        runtimeState: "BUSY",
        uplinkState: "ERROR",
        activeCommandRequestId: "cmd_123",
      },
    });

    (manager as unknown as { runtimes: { delete: (deviceId: string) => void } }).runtimes.delete("device-heartbeat-fallback");

    const status = manager.getCurrentDeviceStatus("device-heartbeat-fallback");
    expect(status.device.runtimeState).toBe("BUSY");
    expect(status.device.uplinkState).toBe("ERROR");
    expect(status.device.activeCommandRequestId).toBe("cmd_123");
  });

  test("uses server time for lastSeenAt instead of client timestamp", () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-server-time",
      socketId: "socket-server-time",
      timestamp: 1,
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        uplinkState: "ONLINE",
        capabilities: ["display_text"],
      },
    });

    const afterHello = manager.getCurrentDeviceStatus("device-server-time");
    expect(afterHello.device.lastSeenAt).not.toBe(1);

    manager.markHeartbeat({
      deviceId: "device-server-time",
      sessionId,
      socketId: "socket-server-time",
      timestamp: 2,
      payload: {
        runtimeState: "BUSY",
        uplinkState: "ERROR",
        activeCommandRequestId: null,
      },
    });

    const afterHeartbeat = manager.getCurrentDeviceStatus("device-server-time");
    expect(afterHeartbeat.device.lastSeenAt).not.toBe(2);
  });
});
