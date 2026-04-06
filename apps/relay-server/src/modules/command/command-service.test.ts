import { describe, expect, test } from "bun:test";

import { CommandService, CommandServiceError } from "./command-service.ts";
import { DefaultCommandIdGenerator } from "./id-generator.ts";
import { TimeoutManager, type TimeoutScheduler } from "./timeout-manager.ts";

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

function createImageStatePort() {
  const states = new Map<
    string,
    {
      imageId: string;
      transferId: string;
      status: "RESERVED" | "UPLOADING" | "UPLOADED" | "FAILED";
      mimeType: "image/jpeg";
      size?: number;
      sha256?: string;
      uploadedAt?: number;
    }
  >();

  return {
    port: {
      getImageState(input: { imageId: string }) {
        return states.get(input.imageId) ?? null;
      },
      failPendingImage(input: { imageId: string }) {
        const current = states.get(input.imageId);
        if (!current || current.status === "UPLOADED") {
          return;
        }

        states.set(input.imageId, {
          ...current,
          status: "FAILED",
        });
      },
    },
    setUploaded(input: {
      imageId: string;
      transferId: string;
      size: number;
      sha256: string;
      uploadedAt: number;
    }) {
      states.set(input.imageId, {
        imageId: input.imageId,
        transferId: input.transferId,
        status: "UPLOADED",
        mimeType: "image/jpeg",
        size: input.size,
        sha256: input.sha256,
        uploadedAt: input.uploadedAt,
      });
    },
  };
}

describe("command service", () => {
  test("submits, dispatches, and completes a display_text command", () => {
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
    });

    const submitted = service.submitCommand({
      sessionId: "ses_123",
      request: {
        deviceId: "rokid-device",
        action: "display_text",
        payload: {
          text: "Hello relay",
          durationMs: 3_000,
        },
      },
    });

    expect(submitted.command.status).toBe("CREATED");

    const dispatched = service.dispatchCommand(submitted.command.requestId);
    expect(dispatched.command.status).toBe("DISPATCHED_TO_PHONE");
    expect(dispatched.message.payload).toEqual({
      action: "display_text",
      timeoutMs: 90_000,
      params: {
        text: "Hello relay",
        durationMs: 3_000,
      },
    });
    expect(service.getScheduledTimeout(submitted.command.requestId)).toEqual({
      requestId: submitted.command.requestId,
      phase: "ack",
      timeoutMs: 15_000,
    });

    const acknowledged = service.handleCommandAck({
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
    expect(acknowledged.status).toBe("ACKNOWLEDGED_BY_PHONE");
    expect(service.getScheduledTimeout(submitted.command.requestId)).toEqual({
      requestId: submitted.command.requestId,
      phase: "execution",
      timeoutMs: 90_000,
    });

    const running = service.handleCommandStatus({
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
    expect(running.status).toBe("RUNNING");

    const completed = service.handleCommandResult({
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

    expect(completed.status).toBe("COMPLETED");
    expect(completed.result).toEqual({
      action: "display_text",
      displayed: true,
      durationMs: 3_000,
    });
    expect(service.getActiveCommand()).toBeNull();
    expect(service.getScheduledTimeout(submitted.command.requestId)).toBeNull();
  });

  test("single active command enforcement rejects overlapping submissions", () => {
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
    });

    service.submitCommand({
      sessionId: "ses_123",
      request: {
        deviceId: "rokid-device",
        action: "display_text",
        payload: {
          text: "First",
          durationMs: 1_000,
        },
      },
    });

    expect(() =>
      service.submitCommand({
        sessionId: "ses_123",
        request: {
          deviceId: "rokid-device",
          action: "display_text",
          payload: {
            text: "Second",
            durationMs: 1_000,
          },
        },
      }),
    ).toThrow("The relay already owns an active command.");
  });

  test("capture photo command preserves relay-owned image reservation and upload progress", () => {
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
    });

    const submitted = service.submitCommand({
      sessionId: "ses_456",
      request: {
        deviceId: "rokid-device",
        action: "capture_photo",
        payload: {
          quality: "high",
        },
      },
    });

    expect(submitted.command.image).toEqual({
      imageId: "img_00000000_0000_4000_8000_000000000002",
      transferId: "trf_00000000_0000_4000_8000_000000000003",
      status: "RESERVED",
      mimeType: "image/jpeg",
      expiresAt: 1_700_000_090_000,
    });

    const dispatched = service.dispatchCommand(submitted.command.requestId);
    expect(dispatched.message.payload).toEqual({
      action: "capture_photo",
      timeoutMs: 90_000,
      params: {
        quality: "high",
      },
      image: {
        imageId: "img_00000000_0000_4000_8000_000000000002",
        transferId: "trf_00000000_0000_4000_8000_000000000003",
        uploadToken: "upl_00000000_0000_4000_8000_000000000004",
        contentType: "image/jpeg",
        expiresAt: 1_700_000_090_000,
        maxSizeBytes: 10485760,
      },
    });

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

    const uploading = service.handleCommandStatus({
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

    expect(uploading.status).toBe("RUNNING");
    expect(uploading.image?.status).toBe("UPLOADING");

    const completed = service.handleCommandResult({
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

    expect(completed.status).toBe("COMPLETED");
    expect(completed.image).toEqual({
      imageId: "img_00000000_0000_4000_8000_000000000002",
      transferId: "trf_00000000_0000_4000_8000_000000000003",
      status: "UPLOADED",
      mimeType: "image/jpeg",
      size: 2048,
      width: 1920,
      height: 1080,
      sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      expiresAt: 1_700_000_090_000,
    });
  });

  test("image manager gates capture photo completion until upload completes", () => {
    const imageStates = createImageStatePort();
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
      imageStates: imageStates.port,
    });

    const submitted = service.submitCommand({
      sessionId: "ses_456",
      request: {
        deviceId: "rokid-device",
        action: "capture_photo",
        payload: {},
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

    expect(() =>
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
      }),
    ).toThrow("Capture photo command cannot advance before relay image upload completes.");

    imageStates.setUploaded({
      imageId: "img_00000000_0000_4000_8000_000000000002",
      transferId: "trf_00000000_0000_4000_8000_000000000003",
      size: 2048,
      sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      uploadedAt: 1_700_000_000_002,
    });

    const completed = service.handleCommandResult({
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

    expect(completed.status).toBe("COMPLETED");
    expect(completed.image?.uploadedAt).toBe(1_700_000_000_002);
  });

  test("command service rejects status updates before phone acknowledgement", () => {
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
    });

    const submitted = service.submitCommand({
      sessionId: "ses_123",
      request: {
        deviceId: "rokid-device",
        action: "display_text",
        payload: {
          text: "Hello relay",
          durationMs: 3_000,
        },
      },
    });
    service.dispatchCommand(submitted.command.requestId);

    expect(() =>
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
      }),
    ).toThrow("Cannot apply command status while command is in 'DISPATCHED_TO_PHONE'.");
  });

  test("command service marks pending image as failed when command errors", () => {
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
    });

    const submitted = service.submitCommand({
      sessionId: "ses_456",
      request: {
        deviceId: "rokid-device",
        action: "capture_photo",
        payload: {},
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

    const failed = service.handleCommandError({
      version: "1.0",
      type: "command_error",
      deviceId: "rokid-device",
      requestId: submitted.command.requestId,
      timestamp: 1_700_000_000_002,
      payload: {
        action: "capture_photo",
        failedAt: 1_700_000_000_002,
        error: {
          code: "UPLOAD_FAILED",
          message: "Upload failed",
          retryable: true,
        },
      },
    });

    expect(failed.status).toBe("FAILED");
    expect(failed.image?.status).toBe("FAILED");
  });

  test("single active command clears after cancellation", () => {
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
    });

    const submitted = service.submitCommand({
      sessionId: "ses_123",
      request: {
        deviceId: "rokid-device",
        action: "display_text",
        payload: {
          text: "Hello relay",
          durationMs: 3_000,
        },
      },
    });

    const cancelled = service.cancelCommand(submitted.command.requestId);
    expect(cancelled.status).toBe("CANCELLED");
    expect(service.getActiveCommand()).toBeNull();

    expect(() =>
      service.submitCommand({
        sessionId: "ses_123",
        request: {
          deviceId: "rokid-device",
          action: "display_text",
          payload: {
            text: "Second",
            durationMs: 3_000,
          },
        },
      }),
    ).not.toThrow();
  });

  test("command service surfaces structured service errors", () => {
    const error = new CommandServiceError({
      code: "DEVICE_BUSY",
      message: "Busy",
      retryable: true,
    });

    expect(error.code).toBe("DEVICE_BUSY");
    expect(error.retryable).toBe(true);
  });

  test("command timeout marks the active command as timed out", () => {
    const fakeScheduler = createFakeScheduler();
    const service = new CommandService({
      clock: () => 1_700_000_000_000,
      idGenerator: createDeterministicIdGenerator(),
      timeoutManager: new TimeoutManager(fakeScheduler.scheduler),
    });

    const submitted = service.submitCommand({
      sessionId: "ses_123",
      request: {
        deviceId: "rokid-device",
        action: "display_text",
        payload: {
          text: "Hello relay",
          durationMs: 3_000,
        },
      },
    });
    service.dispatchCommand(submitted.command.requestId);

    const [handle] = fakeScheduler.getPendingHandles();
    fakeScheduler.flush(handle!);

    const timedOut = service.getCommand(submitted.command.requestId);
    expect(timedOut?.status).toBe("TIMEOUT");
    expect(timedOut?.error).toEqual({
      code: "TIMEOUT",
      message: "Command timed out while waiting for phone acknowledgement.",
      retryable: true,
      details: {
        phase: "ack",
      },
    });
    expect(service.getActiveCommand()).toBeNull();
  });
});
