import { describe, expect, test } from "bun:test";

import {
  CommandStatusResponseSchema,
  ErrorResponseSchema,
  SubmitCommandResponseSchema,
} from "@rokid-mcp/protocol";
import { Value } from "@sinclair/typebox/value";

import { CommandService } from "../modules/command/command-service.ts";
import { DefaultCommandIdGenerator } from "../modules/command/id-generator.ts";
import { TimeoutManager, type TimeoutScheduler } from "../modules/command/timeout-manager.ts";
import { DeviceSessionManager } from "../modules/device/device-session-manager.ts";
import { createHttpCommandsRoutes } from "./http-commands.ts";

function createDeterministicIdGenerator() {
  let value = 0;
  return new DefaultCommandIdGenerator({
    randomUUID: () => `00000000-0000-4000-8000-${String(++value).padStart(12, "0")}`,
  });
}

function createFakeScheduler() {
  let nextHandle = 0;
  const callbacks = new Map<number, () => void>();

  const scheduler: TimeoutScheduler = {
    setTimeout(callback) {
      const handle = ++nextHandle;
      callbacks.set(handle, callback);
      return handle;
    },
    clearTimeout(handle) {
      callbacks.delete(handle as number);
    },
  };

  return {
    scheduler,
    flush(handle: number) {
      callbacks.get(handle)?.();
    },
    getPendingHandles() {
      return [...callbacks.keys()];
    },
  };
}

function createManager() {
  return new DeviceSessionManager({
    heartbeatTimeoutMs: 50,
    cleanupIntervalMs: 10,
  });
}

let socketCounter = 0;

function registerOnlineDevice(manager: DeviceSessionManager, deviceId = "rokid-device") {
  socketCounter += 1;

  return manager.registerHello({
    deviceId,
    socketId: `socket-${socketCounter}`,
    payload: {
      setupState: "INITIALIZED",
      runtimeState: "READY",
      uplinkState: "ONLINE",
      capabilities: ["display_text", "capture_photo"],
    },
  });
}

function createService(dependencies: ConstructorParameters<typeof CommandService>[0] = {}) {
  return new CommandService({
    clock: () => 1_700_000_000_000,
    idGenerator: createDeterministicIdGenerator(),
    ...dependencies,
  });
}

function createRoutes(args: { manager?: DeviceSessionManager; service?: CommandService } = {}) {
  const manager = args.manager ?? createManager();
  const service = args.service ?? createService();

  return {
    manager,
    service,
    app: createHttpCommandsRoutes({ manager, commandService: service }),
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

async function submitDisplayText(app: ReturnType<typeof createRoutes>["app"], deviceId = "rokid-device") {
  return app.handle(
    createSubmitRequest({
      deviceId,
      action: "display_text",
      payload: {
        text: "Hello relay",
        durationMs: 3_000,
      },
    }),
  );
}

type StatusCase = {
  name: string;
  expectedStatus: string;
  create: () => { app: ReturnType<typeof createRoutes>["app"]; requestId: string };
};

function createDisplayTextCommand(service: CommandService, sessionId: string) {
  return service.submitCommand({
    sessionId,
    request: {
      deviceId: "rokid-device",
      action: "display_text",
      payload: {
        text: "Hello relay",
        durationMs: 3_000,
      },
    },
  });
}

describe("http commands", () => {
  test("submits a display_text command and returns a protocol response", async () => {
    const { app, manager, service } = createRoutes();
    registerOnlineDevice(manager);

    const response = await submitDisplayText(app);
    const json = await response.json();

    expect(response.status).toBe(202);
    expect(Value.Check(SubmitCommandResponseSchema, json)).toBe(true);
    expect(json).toEqual({
      ok: true,
      requestId: "req_00000000_0000_4000_8000_000000000001",
      deviceId: "rokid-device",
      action: "display_text",
      status: "CREATED",
      createdAt: 1_700_000_000_000,
      statusUrl: "http://localhost/api/v1/commands/req_00000000_0000_4000_8000_000000000001",
    });
    expect(service.getCommand(json.requestId)?.status).toBe("CREATED");
  });

  test("submits a capture_photo command with relay-owned reserved image metadata", async () => {
    const { app, manager } = createRoutes();
    registerOnlineDevice(manager);

    const response = await app.handle(
      createSubmitRequest({
        deviceId: "rokid-device",
        action: "capture_photo",
        payload: {
          quality: "high",
        },
      }),
    );
    const json = await response.json();

    expect(response.status).toBe(202);
    expect(Value.Check(SubmitCommandResponseSchema, json)).toBe(true);
    expect(json).toEqual({
      ok: true,
      requestId: "req_00000000_0000_4000_8000_000000000001",
      deviceId: "rokid-device",
      action: "capture_photo",
      status: "CREATED",
      createdAt: 1_700_000_000_000,
      statusUrl: "http://localhost/api/v1/commands/req_00000000_0000_4000_8000_000000000001",
      image: {
        imageId: "img_00000000_0000_4000_8000_000000000002",
        transferId: "trf_00000000_0000_4000_8000_000000000003",
        status: "RESERVED",
        mimeType: "image/jpeg",
        expiresAt: 1_700_000_090_000,
      },
    });
  });

  test("rejects invalid command submissions with a standardized validation error", async () => {
    const { app, manager } = createRoutes();
    registerOnlineDevice(manager);

    const response = await app.handle(
      createSubmitRequest({
        deviceId: "rokid-device",
        action: "unknown",
        payload: {},
      }),
    );
    const json = await response.json();

    expect(response.status).toBe(422);
    expect(Value.Check(ErrorResponseSchema, json)).toBe(true);
    expect(json.error.code).toBe("HTTP_VALIDATION_FAILED");
  });

  test("rejects overlapping submissions through the command service busy rule", async () => {
    const { app, manager, service } = createRoutes();
    registerOnlineDevice(manager);

    const firstResponse = await submitDisplayText(app);
    const firstJson = await firstResponse.json();
    const secondResponse = await submitDisplayText(app);
    const secondJson = await secondResponse.json();

    expect(firstResponse.status).toBe(202);
    expect(secondResponse.status).toBe(409);
    expect(Value.Check(ErrorResponseSchema, secondJson)).toBe(true);
    expect(secondJson.error).toEqual({
      code: "DEVICE_BUSY",
      message: "The relay already owns an active command.",
      retryable: true,
    });
    expect(service.getActiveCommand()?.requestId).toBe(firstJson.requestId);
  });
});

describe("command status route", () => {
  const statusCases: StatusCase[] = [
    {
      name: "CREATED",
      expectedStatus: "CREATED",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "DISPATCHED_TO_PHONE",
      expectedStatus: "DISPATCHED_TO_PHONE",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.dispatchCommand(submitted.command.requestId);
        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "ACKNOWLEDGED_BY_PHONE",
      expectedStatus: "ACKNOWLEDGED_BY_PHONE",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.dispatchCommand(submitted.command.requestId);
        service.handleCommandAck({
          version: "1.0",
          type: "command_ack",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
          timestamp: 1_700_000_000_001,
          payload: {
            action: "display_text",
            acknowledgedAt: 1_700_000_000_001,
            runtimeState: "READY",
          },
        });

        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "RUNNING",
      expectedStatus: "RUNNING",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.dispatchCommand(submitted.command.requestId);
        service.handleCommandAck({
          version: "1.0",
          type: "command_ack",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
          timestamp: 1_700_000_000_001,
          payload: {
            action: "display_text",
            acknowledgedAt: 1_700_000_000_001,
            runtimeState: "READY",
          },
        });
        service.handleCommandStatus({
          version: "1.0",
          type: "command_status",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
          timestamp: 1_700_000_000_002,
          payload: {
            action: "display_text",
            status: "displaying",
            statusAt: 1_700_000_000_002,
          },
        });

        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "COMPLETED",
      expectedStatus: "COMPLETED",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.dispatchCommand(submitted.command.requestId);
        service.handleCommandAck({
          version: "1.0",
          type: "command_ack",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
          timestamp: 1_700_000_000_001,
          payload: {
            action: "display_text",
            acknowledgedAt: 1_700_000_000_001,
            runtimeState: "READY",
          },
        });
        service.handleCommandResult({
          version: "1.0",
          type: "command_result",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
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

        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "FAILED",
      expectedStatus: "FAILED",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.dispatchCommand(submitted.command.requestId);
        service.handleCommandAck({
          version: "1.0",
          type: "command_ack",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
          timestamp: 1_700_000_000_001,
          payload: {
            action: "display_text",
            acknowledgedAt: 1_700_000_000_001,
            runtimeState: "READY",
          },
        });
        service.handleCommandError({
          version: "1.0",
          type: "command_error",
          deviceId: "rokid-device",
          requestId: submitted.command.requestId,
          timestamp: 1_700_000_000_002,
          payload: {
            action: "display_text",
            failedAt: 1_700_000_000_002,
            error: {
              code: "BLUETOOTH_UNAVAILABLE",
              message: "Bluetooth disconnected",
              retryable: true,
            },
          },
        });

        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "TIMEOUT",
      expectedStatus: "TIMEOUT",
      create: () => {
        const fakeScheduler = createFakeScheduler();
        const service = createService({
          timeoutManager: new TimeoutManager(fakeScheduler.scheduler),
        });
        const { app, manager } = createRoutes({ service });
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.dispatchCommand(submitted.command.requestId);
        const [handle] = fakeScheduler.getPendingHandles();
        fakeScheduler.flush(handle!);

        return { app, requestId: submitted.command.requestId };
      },
    },
    {
      name: "CANCELLED",
      expectedStatus: "CANCELLED",
      create: () => {
        const { app, manager, service } = createRoutes();
        const sessionId = registerOnlineDevice(manager);
        const submitted = createDisplayTextCommand(service, sessionId);

        service.cancelCommand(submitted.command.requestId);
        return { app, requestId: submitted.command.requestId };
      },
    },
  ];

  for (const statusCase of statusCases) {
    test(`returns ${statusCase.name} records`, async () => {
      const { app, requestId } = statusCase.create();

      const response = await app.handle(
        new Request(`http://localhost/api/v1/commands/${requestId}`),
      );
      const json = await response.json();

      expect(response.status).toBe(200);
      expect(Value.Check(CommandStatusResponseSchema, json)).toBe(true);
      expect(json.command.requestId).toBe(requestId);
      expect(json.command.status).toBe(statusCase.expectedStatus);
    });
  }

  test("returns capture_photo metadata without embedding image bytes", async () => {
    const { app, manager, service } = createRoutes();
    const sessionId = registerOnlineDevice(manager);
    const submitted = service.submitCommand({
      sessionId,
      request: {
        deviceId: "rokid-device",
        action: "capture_photo",
        payload: {
          quality: "high",
        },
      },
    });

    service.dispatchCommand(submitted.command.requestId);
    service.handleCommandAck({
      version: "1.0",
      type: "command_ack",
      deviceId: "rokid-device",
      requestId: submitted.command.requestId,
      timestamp: 1_700_000_000_001,
      payload: {
        action: "capture_photo",
        acknowledgedAt: 1_700_000_000_001,
        runtimeState: "BUSY",
      },
    });
    service.handleCommandStatus({
      version: "1.0",
      type: "command_status",
      deviceId: "rokid-device",
      requestId: submitted.command.requestId,
      timestamp: 1_700_000_000_002,
      payload: {
        action: "capture_photo",
        status: "uploading_image",
        statusAt: 1_700_000_000_002,
        image: {
          imageId: "img_00000000_0000_4000_8000_000000000002",
          transferId: "trf_00000000_0000_4000_8000_000000000003",
          uploadStartedAt: 1_700_000_000_002,
        },
      },
    });
    service.handleCommandResult({
      version: "1.0",
      type: "command_result",
      deviceId: "rokid-device",
      requestId: submitted.command.requestId,
      timestamp: 1_700_000_000_003,
      payload: {
        completedAt: 1_700_000_000_003,
        result: {
          action: "capture_photo",
          imageId: "img_00000000_0000_4000_8000_000000000002",
          transferId: "trf_00000000_0000_4000_8000_000000000003",
          mimeType: "image/jpeg",
          size: 2048,
          width: 1920,
          height: 1080,
          sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        },
      },
    });

    const response = await app.handle(
      new Request(`http://localhost/api/v1/commands/${submitted.command.requestId}`),
    );
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(Value.Check(CommandStatusResponseSchema, json)).toBe(true);
    expect(json.command.status).toBe("COMPLETED");
    expect(json.command.result).toEqual({
      action: "capture_photo",
      imageId: "img_00000000_0000_4000_8000_000000000002",
      transferId: "trf_00000000_0000_4000_8000_000000000003",
      mimeType: "image/jpeg",
      size: 2048,
      width: 1920,
      height: 1080,
      sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    });
    expect(json.command.result.dataBase64).toBeUndefined();
  });

  test("returns 404 for unknown commands", async () => {
    const { app } = createRoutes();

    const response = await app.handle(
      new Request("http://localhost/api/v1/commands/req_missing123"),
    );
    const json = await response.json();

    expect(response.status).toBe(404);
    expect(Value.Check(ErrorResponseSchema, json)).toBe(true);
    expect(json.error.code).toBe("COMMAND_NOT_FOUND");
  });

  test("rejects invalid request ids", async () => {
    const { app } = createRoutes();

    const response = await app.handle(
      new Request("http://localhost/api/v1/commands/not-a-request-id"),
    );
    const json = await response.json();

    expect(response.status).toBe(422);
    expect(Value.Check(ErrorResponseSchema, json)).toBe(true);
    expect(json.error.code).toBe("HTTP_VALIDATION_FAILED");
  });
});
