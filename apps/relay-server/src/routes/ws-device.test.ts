import { describe, expect, test } from "bun:test";

import { PROTOCOL_VERSION } from "@rokid-mcp/protocol";

import { DeviceSessionManager } from "../modules/device/device-session-manager.ts";
import { buildDeviceWsHandlers } from "./ws-device.ts";

type FakeSocket = {
  data: { socketId: string; deviceId?: string; sessionId?: string; authToken?: string };
  sent: unknown[];
  closeCalls: Array<number | undefined>;
  send: (payload: unknown) => void;
  close: (code?: number) => void;
};

function createManager() {
  return new DeviceSessionManager({
    heartbeatTimeoutMs: 50,
    cleanupIntervalMs: 10,
  });
}

let socketCounter = 0;

function createSocket(): FakeSocket {
  socketCounter += 1;

  return {
    data: { socketId: `socket-${socketCounter}` },
    sent: [],
    closeCalls: [],
    send(payload: unknown) {
      this.sent.push(payload);
    },
    close(code?: number) {
      this.closeCalls.push(code);
    },
  };
}

function createHandlers(manager = createManager()) {
  return buildDeviceWsHandlers({
    manager,
    heartbeatIntervalMs: 5000,
    heartbeatTimeoutMs: 15000,
  });
}

function helloMessage(deviceId = "device-a") {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "hello",
    deviceId,
    timestamp: Date.now(),
    payload: {
      authToken: "token-123",
      appVersion: "1.0.0",
      phoneInfo: { model: "pixel" },
      setupState: "INITIALIZED",
      runtimeState: "READY",
      uplinkState: "ONLINE",
      capabilities: ["display_text"],
    },
  });
}

function heartbeatMessage(args: {
  deviceId?: string;
  sessionId: string;
  runtimeState?: string;
  uplinkState?: string;
  activeCommandRequestId?: string | null;
}) {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "heartbeat",
    deviceId: args.deviceId ?? "device-a",
    sessionId: args.sessionId,
    timestamp: Date.now(),
    payload: {
      seq: 1,
      runtimeState: args.runtimeState ?? "BUSY",
      uplinkState: args.uplinkState ?? "ERROR",
      pendingCommandCount: 0,
      activeCommandRequestId: args.activeCommandRequestId ?? "cmd-1",
    },
  });
}

function phoneStateUpdateMessage(args: {
  deviceId?: string;
  sessionId: string;
  runtimeState?: string;
  uplinkState?: string;
}) {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "phone_state_update",
    deviceId: args.deviceId ?? "device-a",
    sessionId: args.sessionId,
    timestamp: Date.now(),
    payload: {
      setupState: "INITIALIZED",
      runtimeState: args.runtimeState ?? "BUSY",
      uplinkState: args.uplinkState ?? "ERROR",
      activeCommandRequestId: null,
      lastErrorCode: "PHONE_ERR",
      lastErrorMessage: "runtime updated",
    },
  });
}

describe("device websocket handlers", () => {
  test("pre-hello non-hello messages close connection", () => {
    const manager = createManager();
    const handlers = createHandlers(manager);
    const socket = createSocket();

    handlers.message(socket, heartbeatMessage({ sessionId: "ses_missing" }));

    expect(socket.closeCalls).toEqual([1008]);
    expect(manager.getCurrentDeviceStatus("device-a").device.sessionState).toBe("OFFLINE");
  });

  test("invalid json closes connection", () => {
    const handlers = createHandlers();
    const socket = createSocket();

    handlers.message(socket, "not-json");

    expect(socket.closeCalls).toEqual([1003]);
  });

  test("invalid schema closes connection", () => {
    const handlers = createHandlers();
    const socket = createSocket();

    handlers.message(
      socket,
      JSON.stringify({
        version: PROTOCOL_VERSION,
        type: "hello",
        deviceId: "device-a",
        timestamp: Date.now(),
        payload: {
          authToken: "token-123",
          appVersion: "1.0.0",
          phoneInfo: { model: "pixel" },
          setupState: "INITIALIZED",
          runtimeState: "READY",
          uplinkState: "ONLINE",
        },
      }),
    );

    expect(socket.closeCalls).toEqual([1008]);
  });

  test("phone_state_update does not directly reply and status reads updated snapshot", () => {
    const manager = createManager();
    const handlers = createHandlers(manager);
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    const ack = socket.sent[0] as { payload: { sessionId: string } };

    handlers.message(socket, phoneStateUpdateMessage({ sessionId: ack.payload.sessionId }));

    const status = manager.getCurrentDeviceStatus("device-a");

    expect(socket.sent).toHaveLength(1);
    expect(status.device.runtimeState).toBe("BUSY");
    expect(status.device.uplinkState).toBe("ERROR");
    expect(status.device.lastErrorCode).toBe("PHONE_ERR");
  });

  test("new hello from different device replaces current singleton", () => {
    const manager = createManager();
    const handlers = createHandlers(manager);
    const oldSocket = createSocket();
    const newSocket = createSocket();

    handlers.message(oldSocket, helloMessage("device-old"));
    handlers.message(newSocket, helloMessage("device-new"));

    const oldStatus = manager.getCurrentDeviceStatus("device-old");
    const newStatus = manager.getCurrentDeviceStatus("device-new");

    expect(oldStatus.device.sessionState).toBe("OFFLINE");
    expect(oldStatus.device.sessionId).toBeNull();
    expect(newStatus.device.sessionState).toBe("ONLINE");
    expect(newStatus.device.sessionId).not.toBeNull();
  });

  test("authenticated message with mismatched deviceId closes connection", () => {
    const handlers = createHandlers();
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    const ack = socket.sent[0] as { payload: { sessionId: string } };

    handlers.message(socket, heartbeatMessage({ deviceId: "device-b", sessionId: ack.payload.sessionId }));

    expect(socket.closeCalls).toEqual([1008]);
  });

  test("authenticated message with mismatched sessionId closes connection", () => {
    const handlers = createHandlers();
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));

    handlers.message(socket, heartbeatMessage({ sessionId: "ses_wrong" }));

    expect(socket.closeCalls).toEqual([1008]);
  });

  test("old socket late close after replacement does not clear current session", () => {
    const manager = createManager();
    const handlers = createHandlers(manager);
    const oldSocket = createSocket();
    const newSocket = createSocket();

    handlers.message(oldSocket, helloMessage("device-a"));
    handlers.message(newSocket, helloMessage("device-b"));
    handlers.close(oldSocket);

    const status = manager.getCurrentDeviceStatus("device-b");

    expect(status.device.sessionState).toBe("ONLINE");
    expect(status.device.sessionId).not.toBeNull();
  });

  test("old socket late heartbeat after replacement does not pollute current singleton", () => {
    const manager = createManager();
    const handlers = createHandlers(manager);
    const oldSocket = createSocket();
    const newSocket = createSocket();

    handlers.message(oldSocket, helloMessage("device-old"));
    const oldAck = oldSocket.sent[0] as { payload: { sessionId: string } };
    handlers.message(newSocket, helloMessage("device-new"));

    handlers.message(oldSocket, heartbeatMessage({
      deviceId: "device-old",
      sessionId: oldAck.payload.sessionId,
      runtimeState: "BUSY",
      uplinkState: "ERROR",
    }));

    const oldStatus = manager.getCurrentDeviceStatus("device-old");
    const newStatus = manager.getCurrentDeviceStatus("device-new");

    expect(oldSocket.closeCalls).toEqual([1008]);
    expect(oldStatus.device.sessionState).toBe("OFFLINE");
    expect(newStatus.device.runtimeState).toBe("READY");
    expect(newStatus.device.uplinkState).toBe("ONLINE");
  });

  test("old socket late phone_state_update after replacement does not pollute current singleton", () => {
    const manager = createManager();
    const handlers = createHandlers(manager);
    const oldSocket = createSocket();
    const newSocket = createSocket();

    handlers.message(oldSocket, helloMessage("device-old"));
    const oldAck = oldSocket.sent[0] as { payload: { sessionId: string } };
    handlers.message(newSocket, helloMessage("device-new"));

    handlers.message(oldSocket, phoneStateUpdateMessage({
      deviceId: "device-old",
      sessionId: oldAck.payload.sessionId,
      runtimeState: "BUSY",
      uplinkState: "ERROR",
    }));

    const oldStatus = manager.getCurrentDeviceStatus("device-old");
    const newStatus = manager.getCurrentDeviceStatus("device-new");

    expect(oldSocket.closeCalls).toEqual([1008]);
    expect(oldStatus.device.sessionState).toBe("OFFLINE");
    expect(newStatus.device.runtimeState).toBe("READY");
    expect(newStatus.device.lastErrorCode).toBeNull();
  });
});
