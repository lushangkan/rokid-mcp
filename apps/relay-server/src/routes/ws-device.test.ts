import { describe, expect, test } from "bun:test";

import { PROTOCOL_VERSION } from "@rokid-mcp/protocol";

import { CommandService } from "../modules/command/command-service.ts";
import { DefaultCommandIdGenerator } from "../modules/command/id-generator.ts";
import { DeviceSessionManager } from "../modules/device/device-session-manager.ts";
import { createHttpCommandsRoutes } from "./http-commands.ts";
import { buildDeviceWsHandlers, createDeviceWsController } from "./ws-device.ts";

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

function createDeterministicIdGenerator() {
  let value = 0;
  return new DefaultCommandIdGenerator({
    randomUUID: () => `00000000-0000-4000-8000-${String(++value).padStart(12, "0")}`,
  });
}

function createService() {
  return new CommandService({
    clock: () => 1_700_000_000_000,
    idGenerator: createDeterministicIdGenerator(),
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
    commandService: createService(),
    heartbeatIntervalMs: 5000,
    heartbeatTimeoutMs: 15000,
  });
}

function createController(manager = createManager(), service = createService()) {
  const controller = createDeviceWsController({
    manager,
    commandService: service,
    heartbeatIntervalMs: 5000,
    heartbeatTimeoutMs: 15000,
  });

  return {
    manager,
    service,
    controller,
    handlers: controller.handlers,
  };
}

function createSubmitRequest(body: unknown) {
  return new Request("http://localhost/api/v1/commands", {
    method: "POST",
    headers: {
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
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

function commandAckMessage(args: {
  requestId: string;
  deviceId?: string;
  sessionId?: string;
  action?: "display_text" | "capture_photo";
}) {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "command_ack",
    deviceId: args.deviceId ?? "device-a",
    requestId: args.requestId,
    ...(args.sessionId ? { sessionId: args.sessionId } : {}),
    timestamp: 1_700_000_000_001,
    payload: {
      action: args.action ?? "display_text",
      acknowledgedAt: 1_700_000_000_001,
      runtimeState: "READY",
    },
  });
}

function commandStatusMessage(args: {
  requestId: string;
  deviceId?: string;
  sessionId?: string;
  action?: "display_text" | "capture_photo";
}) {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "command_status",
    deviceId: args.deviceId ?? "device-a",
    requestId: args.requestId,
    ...(args.sessionId ? { sessionId: args.sessionId } : {}),
    timestamp: 1_700_000_000_002,
    payload: {
      action: args.action ?? "display_text",
      status: "displaying",
      statusAt: 1_700_000_000_002,
    },
  });
}

function commandResultMessage(args: {
  requestId: string;
  deviceId?: string;
  sessionId?: string;
}) {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "command_result",
    deviceId: args.deviceId ?? "device-a",
    requestId: args.requestId,
    ...(args.sessionId ? { sessionId: args.sessionId } : {}),
    timestamp: 1_700_000_000_003,
    payload: {
      completedAt: 1_700_000_000_003,
      result: {
        action: "display_text",
        displayed: true,
        durationMs: 3_000,
      },
    },
  });
}

function commandErrorMessage(args: {
  requestId: string;
  deviceId?: string;
  sessionId?: string;
  action?: "display_text" | "capture_photo";
}) {
  return JSON.stringify({
    version: PROTOCOL_VERSION,
    type: "command_error",
    deviceId: args.deviceId ?? "device-a",
    requestId: args.requestId,
    ...(args.sessionId ? { sessionId: args.sessionId } : {}),
    timestamp: 1_700_000_000_002,
    payload: {
      action: args.action ?? "display_text",
      failedAt: 1_700_000_000_002,
      error: {
        code: "BLUETOOTH_UNAVAILABLE",
        message: "Bluetooth disconnected",
        retryable: true,
      },
    },
  });
}

describe("ws device command dispatch", () => {
  test("ws device command dispatches submitted commands to the active phone session", async () => {
    const { manager, service, controller, handlers } = createController();
    const app = createHttpCommandsRoutes({
      manager,
      commandService: service,
      dispatchPendingCommand: controller.dispatchPendingCommand,
    });
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    const helloAck = socket.sent[0] as { payload: { sessionId: string } };

    const response = await app.handle(
      createSubmitRequest({
        deviceId: "device-a",
        action: "display_text",
        payload: {
          text: "Hello relay",
          durationMs: 3_000,
        },
      }),
    );
    const body = await response.json();

    expect(response.status).toBe(202);
    expect(body.status).toBe("DISPATCHED_TO_PHONE");
    expect(socket.sent).toHaveLength(2);
    expect(socket.sent[1]).toEqual({
      version: PROTOCOL_VERSION,
      type: "command",
      deviceId: "device-a",
      requestId: body.requestId,
      sessionId: helloAck.payload.sessionId,
      timestamp: 1_700_000_000_000,
      payload: {
        action: "display_text",
        timeoutMs: 90_000,
        params: {
          text: "Hello relay",
          durationMs: 3_000,
        },
      },
    });
    expect(service.getCommand(body.requestId)?.status).toBe("DISPATCHED_TO_PHONE");
  });

  test("ws device command dispatches created commands after phone reconnect", () => {
    const { service, handlers } = createController();
    const oldSocket = createSocket();
    const newSocket = createSocket();

    handlers.message(oldSocket, helloMessage("device-a"));
    const oldHelloAck = oldSocket.sent[0] as { payload: { sessionId: string } };
    const submitted = service.submitCommand({
      sessionId: oldHelloAck.payload.sessionId,
      request: {
        deviceId: "device-a",
        action: "display_text",
        payload: {
          text: "Reconnect me",
          durationMs: 3_000,
        },
      },
    });

    handlers.close(oldSocket);
    handlers.message(newSocket, helloMessage("device-a"));

    const newHelloAck = newSocket.sent[0] as { payload: { sessionId: string } };
    const dispatched = newSocket.sent[1] as { requestId: string; sessionId: string };

    expect(newSocket.sent).toHaveLength(2);
    expect(dispatched.requestId).toBe(submitted.command.requestId);
    expect(dispatched.sessionId).toBe(newHelloAck.payload.sessionId);
    expect(service.getCommand(submitted.command.requestId)?.status).toBe("DISPATCHED_TO_PHONE");
  });

  test("ws device command ingests ack and status updates through CommandService", () => {
    const { service, controller, handlers } = createController();
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    const helloAck = socket.sent[0] as { payload: { sessionId: string } };
    const submitted = service.submitCommand({
      sessionId: helloAck.payload.sessionId,
      request: {
        deviceId: "device-a",
        action: "display_text",
        payload: {
          text: "Track state",
          durationMs: 3_000,
        },
      },
    });

    expect(controller.dispatchPendingCommand(submitted.command.requestId)).toBe(true);
    expect(service.getCommand(submitted.command.requestId)?.status).toBe("DISPATCHED_TO_PHONE");

    handlers.message(socket, commandAckMessage({ requestId: submitted.command.requestId }));
    expect(service.getCommand(submitted.command.requestId)?.status).toBe("ACKNOWLEDGED_BY_PHONE");

    handlers.message(socket, commandStatusMessage({ requestId: submitted.command.requestId }));
    expect(service.getCommand(submitted.command.requestId)?.status).toBe("RUNNING");
  });

  test("command result ingestion completes acknowledged commands through CommandService", () => {
    const { service, controller, handlers } = createController();
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    const helloAck = socket.sent[0] as { payload: { sessionId: string } };
    const submitted = service.submitCommand({
      sessionId: helloAck.payload.sessionId,
      request: {
        deviceId: "device-a",
        action: "display_text",
        payload: {
          text: "Finish me",
          durationMs: 3_000,
        },
      },
    });

    controller.dispatchPendingCommand(submitted.command.requestId);
    handlers.message(socket, commandAckMessage({ requestId: submitted.command.requestId }));
    handlers.message(socket, commandStatusMessage({ requestId: submitted.command.requestId }));
    handlers.message(socket, commandResultMessage({ requestId: submitted.command.requestId }));

    const completed = service.getCommand(submitted.command.requestId);
    expect(completed?.status).toBe("COMPLETED");
    expect(completed?.result).toEqual({
      action: "display_text",
      displayed: true,
      durationMs: 3_000,
    });
  });

  test("ws device command ingests phone terminal errors through CommandService", () => {
    const { service, controller, handlers } = createController();
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    const helloAck = socket.sent[0] as { payload: { sessionId: string } };
    const submitted = service.submitCommand({
      sessionId: helloAck.payload.sessionId,
      request: {
        deviceId: "device-a",
        action: "display_text",
        payload: {
          text: "Fail me",
          durationMs: 3_000,
        },
      },
    });

    controller.dispatchPendingCommand(submitted.command.requestId);
    handlers.message(socket, commandAckMessage({ requestId: submitted.command.requestId }));
    handlers.message(socket, commandErrorMessage({ requestId: submitted.command.requestId }));

    const failed = service.getCommand(submitted.command.requestId);
    expect(failed?.status).toBe("FAILED");
    expect(failed?.error?.code).toBe("BLUETOOTH_UNAVAILABLE");
  });

  test("command result ingestion rejects unknown request ids", () => {
    const { handlers } = createController();
    const socket = createSocket();

    handlers.message(socket, helloMessage("device-a"));
    handlers.message(
      socket,
      commandResultMessage({
        requestId: "req_00000000_0000_4000_8000_000000009999",
      }),
    );

    expect(socket.closeCalls).toEqual([1008]);
  });
});

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
