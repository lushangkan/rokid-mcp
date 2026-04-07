import { describe, expect, test } from "bun:test";

import { DeviceSessionManager } from "./device-session-manager.ts";

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
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });
    manager.confirmHello("device-a", oldSessionId);

    const newSessionId = manager.registerHello({
      deviceId: "device-a",
      socketId: "socket-new",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
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

  test("different-device hello replaces singleton context and old device becomes synthetic offline", () => {
    const manager = createManager();

    const oldSessionId = manager.registerHello({
      deviceId: "device-old",
      socketId: "socket-old",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    const newSessionId = manager.registerHello({
      deviceId: "device-new",
      socketId: "socket-new",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        capabilities: ["capture_photo"],
      },
    });

    const oldStatus = manager.getCurrentDeviceStatus("device-old");
    const newStatus = manager.getCurrentDeviceStatus("device-new");

    expect(oldSessionId).not.toBe(newSessionId);
    expect(manager.matchesCurrentSession("device-old", oldSessionId, "socket-old")).toBe(false);
    expect(manager.matchesCurrentSession("device-new", newSessionId, "socket-new")).toBe(true);
    expect(oldStatus.device.connected).toBe(false);
    expect(oldStatus.device.sessionState).toBe("OFFLINE");
    expect(oldStatus.device.runtimeState).toBe("DISCONNECTED");
    expect(oldStatus.device.sessionId).toBeNull();
    expect(newStatus.device.connected).toBe(true);
    expect(newStatus.device.sessionState).toBe("ONLINE");
    expect(newStatus.device.sessionId).toBe(newSessionId);
    expect(newStatus.device.capabilities).toEqual(["capture_photo"]);
  });

  test("late close from old socket does not clear current session", () => {
    const manager = createManager();

    const oldSessionId = manager.registerHello({
      deviceId: "device-b",
      socketId: "socket-old",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    const newSessionId = manager.registerHello({
      deviceId: "device-b",
      socketId: "socket-new",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text", "capture_photo"],
      },
    });

    manager.closeCurrentSession("device-b", oldSessionId, "socket-old");
    const status = manager.getCurrentDeviceStatus("device-b");

    expect(status.device.connected).toBe(true);
    expect(status.device.sessionState).toBe("ONLINE");
    expect(status.device.sessionId).toBe(newSessionId);
  });

  test("old device heartbeat does not affect current singleton", () => {
    const manager = createManager();

    const oldSessionId = manager.registerHello({
      deviceId: "device-heartbeat-old",
      socketId: "socket-heartbeat-old",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    const newSessionId = manager.registerHello({
      deviceId: "device-heartbeat-new",
      socketId: "socket-heartbeat-new",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["capture_photo"],
      },
    });

    const accepted = manager.markHeartbeat({
      deviceId: "device-heartbeat-old",
      sessionId: oldSessionId,
      socketId: "socket-heartbeat-old",
      payload: {
        runtimeState: "BUSY",
        activeCommandRequestId: "cmd_old",
      },
    });

    const oldStatus = manager.getCurrentDeviceStatus("device-heartbeat-old");
    const newStatus = manager.getCurrentDeviceStatus("device-heartbeat-new");

    expect(accepted).toBe(false);
    expect(oldStatus.device.sessionState).toBe("OFFLINE");
    expect(oldStatus.device.sessionId).toBeNull();
    expect(newStatus.device.sessionId).toBe(newSessionId);
    expect(newStatus.device.runtimeState).toBe("READY");
    expect(newStatus.device.activeCommandRequestId).toBeNull();
  });

  test("old device phone state update does not affect current singleton", () => {
    const manager = createManager();

    const oldSessionId = manager.registerHello({
      deviceId: "device-phone-old",
      socketId: "socket-phone-old",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    const newSessionId = manager.registerHello({
      deviceId: "device-phone-new",
      socketId: "socket-phone-new",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["capture_photo"],
      },
    });

    const accepted = manager.applyPhoneStateUpdate({
      deviceId: "device-phone-old",
      sessionId: oldSessionId,
      socketId: "socket-phone-old",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        activeCommandRequestId: "cmd_old",
        lastErrorCode: "OLD_ERR",
        lastErrorMessage: "old device update",
      },
    });

    const oldStatus = manager.getCurrentDeviceStatus("device-phone-old");
    const newStatus = manager.getCurrentDeviceStatus("device-phone-new");

    expect(accepted).toBe(false);
    expect(oldStatus.device.sessionState).toBe("OFFLINE");
    expect(oldStatus.device.sessionId).toBeNull();
    expect(newStatus.device.sessionId).toBe(newSessionId);
    expect(newStatus.device.runtimeState).toBe("READY");
    expect(newStatus.device.activeCommandRequestId).toBeNull();
    expect(newStatus.device.lastErrorCode).toBeNull();
    expect(newStatus.device.lastErrorMessage).toBeNull();
  });

  test("stale session forces disconnected runtime on query after cleanup interval fires", async () => {
    const manager = createManager();
    manager.startCleanupJob();

    try {
      manager.registerHello({
        deviceId: "device-stale",
        socketId: "socket-stale",
        payload: {
          setupState: "INITIALIZED",
          runtimeState: "READY",
          capabilities: ["display_text"],
        },
      });

      await Bun.sleep(80);

      const status = manager.getCurrentDeviceStatus("device-stale");

      expect(status.device.connected).toBe(false);
      expect(status.device.sessionState).toBe("STALE");
      expect(status.device.runtimeState).toBe("DISCONNECTED");
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
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
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
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    manager.closeCurrentSession("device-closed", sessionId, "socket-closed");
    const status = manager.getCurrentDeviceStatus("device-closed");

    expect(status.device.connected).toBe(false);
    expect(status.device.sessionState).toBe("CLOSED");
    expect(status.device.runtimeState).toBe("DISCONNECTED");
    expect(status.device.activeCommandRequestId).toBeNull();
  });

  test("phone state update keeps session fallback runtime fields consistent", () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-fallback",
      socketId: "socket-fallback",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    manager.applyPhoneStateUpdate({
      deviceId: "device-fallback",
      sessionId,
      socketId: "socket-fallback",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "BUSY",
        activeCommandRequestId: null,
      },
    });

    (manager as unknown as { runtimes: { clear: () => void } }).runtimes.clear();

    const status = manager.getCurrentDeviceStatus("device-fallback");
    expect(status.device.runtimeState).toBe("BUSY");
  });

  test("heartbeat keeps session fallback runtime fields consistent", () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-heartbeat-fallback",
      socketId: "socket-heartbeat-fallback",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    manager.markHeartbeat({
      deviceId: "device-heartbeat-fallback",
      sessionId,
      socketId: "socket-heartbeat-fallback",
      payload: {
        runtimeState: "BUSY",
        activeCommandRequestId: "cmd_123",
      },
    });

    (manager as unknown as { runtimes: { clear: () => void } }).runtimes.clear();

    const status = manager.getCurrentDeviceStatus("device-heartbeat-fallback");
    expect(status.device.runtimeState).toBe("BUSY");
    expect(status.device.activeCommandRequestId).toBe("cmd_123");
  });

  test("uses server time progression for lastSeenAt", async () => {
    const manager = createManager();

    const sessionId = manager.registerHello({
      deviceId: "device-server-time",
      socketId: "socket-server-time",
      payload: {
        setupState: "INITIALIZED",
        runtimeState: "READY",
        capabilities: ["display_text"],
      },
    });

    const afterHello = manager.getCurrentDeviceStatus("device-server-time");
    expect(afterHello.device.lastSeenAt).not.toBeNull();

    await Bun.sleep(5);

    manager.markHeartbeat({
      deviceId: "device-server-time",
      sessionId,
      socketId: "socket-server-time",
      payload: {
        runtimeState: "BUSY",
        activeCommandRequestId: null,
      },
    });

    const afterHeartbeat = manager.getCurrentDeviceStatus("device-server-time");
    expect(afterHeartbeat.device.lastSeenAt).not.toBeNull();
    expect(afterHeartbeat.device.lastSeenAt!).toBeGreaterThan(afterHello.device.lastSeenAt!);
  });
});
