import {
  PROTOCOL_VERSION,
  type CommandAckMessage,
  type CommandDispatchImage,
  type CommandDispatchMessage,
  type CommandErrorMessage,
  type CommandExecutionStatus,
  type CommandImage,
  type CommandRecord,
  type CommandResultMessage,
  type CommandStatus,
  type CommandStatusMessage,
  type CommandStatusPayload,
  type SubmitCommandRequest,
  type TerminalError,
} from "@rokid-mcp/protocol";

import { createCommandConfig, type CommandConfig } from "../../config/command-config.ts";
import { logger, type LogContext } from "../../lib/logger.ts";
import { CommandStore, type StoredCommand } from "./command-store.ts";
import {
  DefaultCommandIdGenerator,
  type CommandIdGenerator,
} from "./id-generator.ts";
import {
  TimeoutManager,
  type CommandTimeoutEntry,
  type CommandTimeoutPhase,
} from "./timeout-manager.ts";

type Clock = () => number;

export type SubmitCommandInput = {
  request: SubmitCommandRequest;
  sessionId: string;
};

export type SubmitCommandResult = {
  command: CommandRecord;
};

export type DispatchCommandResult = {
  command: CommandRecord;
  message: CommandDispatchMessage;
};

export type DispatchCommandInput = {
  requestId: string;
  sessionId?: string;
};

export type CommandImageReservationPort = {
  reserve(input: { requestId: string; deviceId: string }): CommandDispatchImage;
};

export type CommandImageState = Pick<
  CommandImage,
  "imageId" | "transferId" | "status" | "mimeType" | "size" | "sha256" | "uploadedAt"
>;

export type CommandImageStatePort = {
  getImageState(input: {
    imageId: string;
    requestId: string;
    deviceId: string;
    transferId: string;
  }): CommandImageState | null;
  failPendingImage?(input: {
    imageId: string;
    requestId: string;
    deviceId: string;
    transferId: string;
  }): void;
};

type CommandServiceDependencies = {
  config?: Partial<CommandConfig>;
  clock?: Clock;
  idGenerator?: CommandIdGenerator;
  imageReservations?: CommandImageReservationPort;
  imageStates?: CommandImageStatePort;
  store?: CommandStore;
  timeoutManager?: TimeoutManager;
};

const TERMINAL_STATUSES = new Set<CommandStatus>(["COMPLETED", "FAILED", "TIMEOUT", "CANCELLED"]);
const RUNNING_STATUSES = new Set<CommandExecutionStatus>([
  "forwarding_to_glasses",
  "waiting_glasses_ack",
  "executing",
  "displaying",
  "capturing",
  "image_captured",
  "uploading_image",
  "image_uploaded",
]);

function createCommandServiceError(code: string, message: string, retryable = false): CommandServiceError {
  return new CommandServiceError({ code, message, retryable });
}

function toReservedCommandImage(dispatchImage: CommandDispatchImage): CommandImage {
  return {
    imageId: dispatchImage.imageId,
    transferId: dispatchImage.transferId,
    status: "RESERVED",
    mimeType: dispatchImage.contentType,
    expiresAt: dispatchImage.expiresAt,
  };
}

function createTimeoutError(phase: CommandTimeoutPhase): TerminalError {
  return {
    code: "TIMEOUT",
    message:
      phase === "ack"
        ? "Command timed out while waiting for phone acknowledgement."
        : "Command timed out before reaching a terminal result.",
    retryable: true,
    details: {
      phase,
    },
  };
}

function cloneSubmissionRequest(request: SubmitCommandRequest): SubmitCommandRequest {
  return structuredClone(request);
}

function createCommandLogContext(
  storedCommand: Pick<StoredCommand, "command" | "sessionId">,
  context: LogContext = {},
): LogContext {
  return {
    phone_id: storedCommand.command.deviceId,
    deviceId: storedCommand.command.deviceId,
    requestId: storedCommand.command.requestId,
    sessionId: storedCommand.sessionId,
    action: storedCommand.command.action,
    status: storedCommand.command.status,
    imageId: storedCommand.command.image?.imageId ?? null,
    transferId: storedCommand.command.image?.transferId ?? null,
    ...context,
  };
}

export class CommandService {
  private readonly config: CommandConfig;
  private readonly clock: Clock;
  private readonly idGenerator: CommandIdGenerator;
  private readonly imageReservations: CommandImageReservationPort;
  private readonly imageStates: CommandImageStatePort | null;
  private readonly store: CommandStore;
  private readonly timeoutManager: TimeoutManager;

  constructor(dependencies: CommandServiceDependencies = {}) {
    this.config = createCommandConfig(dependencies.config);
    this.clock = dependencies.clock ?? (() => Date.now());
    this.idGenerator = dependencies.idGenerator ?? new DefaultCommandIdGenerator();
    this.store = dependencies.store ?? new CommandStore();
    this.timeoutManager = dependencies.timeoutManager ?? new TimeoutManager();
    this.imageStates = dependencies.imageStates ?? null;
    this.imageReservations = dependencies.imageReservations ?? {
      reserve: () => {
        const expiresAt = this.clock() + this.config.imageReservationTtlMs;
        return {
          imageId: this.idGenerator.createImageId(),
          transferId: this.idGenerator.createTransferId(),
          uploadToken: this.idGenerator.createUploadToken(),
          contentType: this.config.imageContentType,
          expiresAt,
          maxSizeBytes: this.config.maxImageUploadSizeBytes,
        };
      },
    };
  }

  submitCommand(input: SubmitCommandInput): SubmitCommandResult {
    this.assertCanAcceptNewCommand();

    const createdAt = this.clock();
    const requestId = this.idGenerator.createRequestId();
    const dispatchImage =
      input.request.action === "capture_photo"
        ? this.imageReservations.reserve({
            requestId,
            deviceId: input.request.deviceId,
          })
        : null;

    const storedCommand: StoredCommand = {
      command: {
        requestId,
        deviceId: input.request.deviceId,
        action: input.request.action,
        status: "CREATED",
        createdAt,
        updatedAt: createdAt,
        acknowledgedAt: null,
        completedAt: null,
        cancelledAt: null,
        result: null,
        error: null,
        image: dispatchImage ? toReservedCommandImage(dispatchImage) : null,
      },
      submission: cloneSubmissionRequest(input.request),
      sessionId: input.sessionId,
      dispatchImage,
    };

    const saved = this.store.save(storedCommand);

    logger.info(
      "command created",
      createCommandLogContext(saved, {
        createdAt,
      }),
    );

    return {
      command: saved.command,
    };
  }

  dispatchCommand(input: string | DispatchCommandInput): DispatchCommandResult {
    const requestId = typeof input === "string" ? input : input.requestId;
    const sessionIdOverride = typeof input === "string" ? undefined : input.sessionId;
    const current = this.requireCommand(requestId);
    this.assertStatus(current.command.status, ["CREATED"], "dispatch command");

    const timestamp = this.clock();
    const sessionId = sessionIdOverride ?? current.sessionId;
    const message = this.buildDispatchMessage(current, timestamp, sessionId);
    const updated = this.updateCommand(requestId, (storedCommand) => ({
      ...storedCommand,
      sessionId,
      command: {
        ...storedCommand.command,
        status: "DISPATCHED_TO_PHONE",
        updatedAt: timestamp,
      },
    }));

    this.timeoutManager.schedule(
      {
        requestId,
        phase: "ack",
        timeoutMs: this.config.ackTimeoutMs,
      },
      (entry) => {
        this.applyTimeout(entry);
      },
    );

    logger.info(
      "command dispatched to phone",
      createCommandLogContext(updated, {
        dispatchedSessionId: sessionId,
        dispatchedAt: timestamp,
      }),
    );

    return {
      command: updated.command,
      message,
    };
  }

  handleCommandAck(message: CommandAckMessage): CommandRecord {
    const current = this.requireCommand(message.requestId);
    this.assertSameCommand(current, message.deviceId, message.payload.action);
    this.assertStatus(current.command.status, ["DISPATCHED_TO_PHONE"], "acknowledge command");

    const acknowledged = this.updateCommand(message.requestId, (storedCommand) => ({
      ...storedCommand,
      command: {
        ...storedCommand.command,
        status: "ACKNOWLEDGED_BY_PHONE",
        acknowledgedAt: message.payload.acknowledgedAt,
        updatedAt: this.clock(),
      },
    }));

    this.timeoutManager.schedule(
      {
        requestId: message.requestId,
        phase: "execution",
        timeoutMs: this.config.executionTimeoutMs,
      },
      (entry) => {
        this.applyTimeout(entry);
      },
    );

    logger.info(
      "command acknowledged by phone",
      createCommandLogContext(acknowledged, {
        acknowledgedAt: message.payload.acknowledgedAt,
        runtimeState: message.payload.runtimeState,
      }),
    );

    return acknowledged.command;
  }

  handleCommandStatus(message: CommandStatusMessage): CommandRecord {
    const current = this.requireCommand(message.requestId);
    this.assertSameCommand(current, message.deviceId, message.payload.action);
    this.assertStatus(current.command.status, ["ACKNOWLEDGED_BY_PHONE", "RUNNING"], "apply command status");

    if (!RUNNING_STATUSES.has(message.payload.status)) {
      throw createCommandServiceError("COMMAND_STATUS_INVALID", "Unsupported running status.");
    }

    const uploadedImageState = this.resolveUploadedImageStateFromStatus(current, message.payload);
    const updated = this.updateCommand(message.requestId, (storedCommand) => ({
      ...storedCommand,
      command: {
        ...storedCommand.command,
        status: "RUNNING",
        updatedAt: this.clock(),
        image: this.updateCommandImageFromStatus(
          storedCommand.command.image,
          message.payload,
          uploadedImageState,
        ),
      },
    }));

    logger.info(
      "command status updated",
      createCommandLogContext(updated, {
        executionStatus: message.payload.status,
        statusAt: message.payload.statusAt,
      }),
    );

    return updated.command;
  }

  handleCommandResult(message: CommandResultMessage): CommandRecord {
    const current = this.requireCommand(message.requestId);
    this.assertStatus(
      current.command.status,
      ["ACKNOWLEDGED_BY_PHONE", "RUNNING"],
      "complete command",
    );
    this.assertSameCommand(current, message.deviceId, message.payload.result.action);

    const uploadedImageState = this.resolveUploadedImageStateFromResult(current, message.payload.result);
    const nextImage = this.updateImageFromResult(
      current.command.image,
      message.payload.result,
      uploadedImageState,
    );
    const updated = this.updateCommand(message.requestId, (storedCommand) => ({
      ...storedCommand,
      command: {
        ...storedCommand.command,
        status: "COMPLETED",
        completedAt: message.payload.completedAt,
        updatedAt: this.clock(),
        result: structuredClone(message.payload.result),
        error: null,
        image: nextImage,
      },
    }));

    this.timeoutManager.cancel(message.requestId);

    logger.info(
      "command completed",
      createCommandLogContext(updated, {
        completedAt: message.payload.completedAt,
        resultAction: message.payload.result.action,
      }),
    );

    return updated.command;
  }

  handleCommandError(message: CommandErrorMessage): CommandRecord {
    const current = this.requireCommand(message.requestId);
    this.assertStatus(
      current.command.status,
      ["DISPATCHED_TO_PHONE", "ACKNOWLEDGED_BY_PHONE", "RUNNING"],
      "fail command",
    );
    this.assertSameCommand(current, message.deviceId, message.payload.action);

    const updated = this.updateCommand(message.requestId, (storedCommand) => ({
      ...storedCommand,
      command: {
        ...storedCommand.command,
        status: "FAILED",
        completedAt: message.payload.failedAt,
        updatedAt: this.clock(),
        error: structuredClone(message.payload.error),
        result: null,
        image: this.failPendingImage(storedCommand.command.image),
      },
    }));

    this.failOwnedImage(current.command.image, current.command.requestId, current.command.deviceId);
    this.timeoutManager.cancel(message.requestId);

    logger.error(
      "command failed",
      createCommandLogContext(updated, {
        failedAt: message.payload.failedAt,
        errorCode: message.payload.error.code,
        errorMessage: message.payload.error.message,
        retryable: message.payload.error.retryable,
      }),
    );

    return updated.command;
  }

  cancelCommand(requestId: string, reason: TerminalError | null = null): CommandRecord {
    const current = this.requireCommand(requestId);
    if (TERMINAL_STATUSES.has(current.command.status)) {
      return current.command;
    }

    const cancelledAt = this.clock();
    const updated = this.updateCommand(requestId, (storedCommand) => ({
      ...storedCommand,
      command: {
        ...storedCommand.command,
        status: "CANCELLED",
        cancelledAt,
        completedAt: cancelledAt,
        updatedAt: cancelledAt,
        error: reason ? structuredClone(reason) : null,
        result: null,
        image: this.failPendingImage(storedCommand.command.image),
      },
    }));

    this.failOwnedImage(current.command.image, current.command.requestId, current.command.deviceId);
    this.timeoutManager.cancel(requestId);

    logger.info(
      "command cancelled",
      createCommandLogContext(updated, {
        cancelledAt,
        errorCode: reason?.code ?? null,
        errorMessage: reason?.message ?? null,
        retryable: reason?.retryable ?? null,
      }),
    );

    return updated.command;
  }

  getCommand(requestId: string): CommandRecord | null {
    return this.store.getCommand(requestId);
  }

  getActiveCommand(): CommandRecord | null {
    return this.store.getActive()?.command ?? null;
  }

  getScheduledTimeout(requestId: string): CommandTimeoutEntry | null {
    return this.timeoutManager.get(requestId);
  }

  dispose(): void {
    this.timeoutManager.dispose();
  }

  private assertCanAcceptNewCommand(): void {
    const active = this.store.getActive();
    if (active) {
      throw createCommandServiceError("DEVICE_BUSY", "The relay already owns an active command.", true);
    }

    if (this.config.activeCommandLimit !== 1) {
      throw createCommandServiceError(
        "INTERNAL_UNEXPECTED_STATE",
        "Single-device relay foundation only supports one active command.",
      );
    }
  }

  private applyTimeout(entry: CommandTimeoutEntry): void {
    const current = this.store.get(entry.requestId);
    if (!current || TERMINAL_STATUSES.has(current.command.status)) {
      return;
    }

    const now = this.clock();
    this.failOwnedImage(current.command.image, current.command.requestId, current.command.deviceId);
    const updated = this.updateCommand(entry.requestId, (storedCommand) => ({
      ...storedCommand,
      command: {
        ...storedCommand.command,
        status: "TIMEOUT",
        completedAt: now,
        updatedAt: now,
        error: createTimeoutError(entry.phase),
        result: null,
        image: this.failPendingImage(storedCommand.command.image),
      },
    }));

    logger.error(
      "command timed out",
      createCommandLogContext(updated, {
        phase: entry.phase,
        timeoutMs: entry.timeoutMs,
        completedAt: now,
      }),
    );
  }

  private requireCommand(requestId: string): StoredCommand {
    const command = this.store.get(requestId);
    if (!command) {
      this.raiseNotFound(requestId);
    }

    return command;
  }

  private raiseNotFound(requestId: string): never {
    throw createCommandServiceError("COMMAND_NOT_FOUND", `Command '${requestId}' does not exist.`);
  }

  private updateCommand(
    requestId: string,
    updater: (storedCommand: StoredCommand) => StoredCommand,
  ): StoredCommand {
    const updated = this.store.update(requestId, updater);
    if (!updated) {
      this.raiseNotFound(requestId);
    }

    return updated;
  }

  private buildDispatchMessage(
    command: StoredCommand,
    timestamp: number,
    sessionId: string,
  ): CommandDispatchMessage {
    return {
      version: PROTOCOL_VERSION,
      type: "command",
      deviceId: command.command.deviceId,
      requestId: command.command.requestId,
      sessionId,
      timestamp,
      payload:
        command.submission.action === "display_text"
          ? {
              action: "display_text",
              timeoutMs: this.config.executionTimeoutMs,
              params: structuredClone(command.submission.payload),
            }
          : {
              action: "capture_photo",
              timeoutMs: this.config.executionTimeoutMs,
              params: structuredClone(command.submission.payload),
              image: structuredClone(command.dispatchImage ?? this.raiseMissingImageReservation()),
            },
    };
  }

  private raiseMissingImageReservation(): never {
    throw createCommandServiceError(
      "INTERNAL_UNEXPECTED_STATE",
      "Capture photo commands must carry a reserved image dispatch payload.",
    );
  }

  private assertSameCommand(storedCommand: StoredCommand, deviceId: string, action: SubmitCommandRequest["action"]): void {
    if (storedCommand.command.deviceId !== deviceId) {
      throw createCommandServiceError("COMMAND_SEQUENCE_INVALID", "Command device does not match the active record.");
    }

    if (storedCommand.command.action !== action) {
      throw createCommandServiceError("COMMAND_SEQUENCE_INVALID", "Command action does not match the active record.");
    }
  }

  private assertStatus(currentStatus: CommandStatus, allowed: CommandStatus[], action: string): void {
    if (allowed.includes(currentStatus)) {
      return;
    }

    if (TERMINAL_STATUSES.has(currentStatus)) {
      throw createCommandServiceError("COMMAND_ALREADY_FINISHED", `Cannot ${action} after terminal state '${currentStatus}'.`);
    }

    throw createCommandServiceError(
      "COMMAND_SEQUENCE_INVALID",
      `Cannot ${action} while command is in '${currentStatus}'.`,
    );
  }

  private updateCommandImageFromStatus(
    image: CommandImage | null,
    payload: CommandStatusPayload,
    uploadedImageState: CommandImageState | null,
  ): CommandImage | null {
    if (payload.action !== "capture_photo") {
      return image;
    }

    if (!image) {
      throw createCommandServiceError(
        "COMMAND_SEQUENCE_INVALID",
        "Capture photo status requires a reserved image record.",
      );
    }

    if (!("image" in payload)) {
      return image;
    }

    const nextImage: CommandImage = {
      ...image,
      imageId: payload.image.imageId,
      transferId: payload.image.transferId,
    };

    if (payload.status === "uploading_image") {
      return {
        ...nextImage,
        status: "UPLOADING",
      };
    }

    if (payload.status === "image_uploaded") {
      return {
        ...nextImage,
        status: uploadedImageState?.status ?? "UPLOADED",
        uploadedAt: uploadedImageState?.uploadedAt ?? payload.image.uploadedAt ?? undefined,
        sha256: uploadedImageState?.sha256 ?? payload.image.sha256,
        size: uploadedImageState?.size ?? nextImage.size,
        mimeType: uploadedImageState?.mimeType ?? nextImage.mimeType,
      };
    }

    return nextImage;
  }

  private updateImageFromResult(
    commandImage: CommandImage | null,
    result: CommandResultMessage["payload"]["result"],
    uploadedImageState: CommandImageState | null,
  ): CommandImage | null {
    if (result.action !== "capture_photo") {
      return commandImage;
    }

    if (!commandImage) {
      throw createCommandServiceError(
        "COMMAND_RESULT_INVALID",
        "Capture photo result requires a reserved image record.",
      );
    }

    if (commandImage.imageId !== result.imageId || commandImage.transferId !== result.transferId) {
      throw createCommandServiceError(
        "COMMAND_RESULT_INVALID",
        "Capture photo result does not match the reserved image identifiers.",
      );
    }

    return {
      ...commandImage,
      status: uploadedImageState?.status ?? "UPLOADED",
      mimeType: uploadedImageState?.mimeType ?? result.mimeType,
      size: uploadedImageState?.size ?? result.size,
      width: result.width,
      height: result.height,
      sha256: uploadedImageState?.sha256 ?? result.sha256,
      uploadedAt: uploadedImageState?.uploadedAt ?? commandImage.uploadedAt,
    };
  }

  private resolveUploadedImageStateFromStatus(
    current: StoredCommand,
    payload: CommandStatusPayload,
  ): CommandImageState | null {
    if (payload.action !== "capture_photo" || payload.status !== "image_uploaded" || !("image" in payload)) {
      return null;
    }

    return this.requireUploadedImageState(current, payload.image.imageId, payload.image.transferId);
  }

  private resolveUploadedImageStateFromResult(
    current: StoredCommand,
    result: CommandResultMessage["payload"]["result"],
  ): CommandImageState | null {
    if (result.action !== "capture_photo") {
      return null;
    }

    return this.requireUploadedImageState(current, result.imageId, result.transferId);
  }

  private requireUploadedImageState(
    current: StoredCommand,
    imageId: string,
    transferId: string,
  ): CommandImageState | null {
    if (!this.imageStates) {
      return null;
    }

    const imageState = this.imageStates.getImageState({
      imageId,
      transferId,
      requestId: current.command.requestId,
      deviceId: current.command.deviceId,
    });

    if (!imageState || imageState.status !== "UPLOADED") {
      throw createCommandServiceError(
        "COMMAND_SEQUENCE_INVALID",
        "Capture photo command cannot advance before relay image upload completes.",
      );
    }

    return imageState;
  }

  private failOwnedImage(
    image: CommandImage | null,
    requestId: string,
    deviceId: string,
  ): void {
    if (!image || !this.imageStates?.failPendingImage) {
      return;
    }

    this.imageStates.failPendingImage({
      imageId: image.imageId,
      requestId,
      deviceId,
      transferId: image.transferId,
    });
  }

  private failPendingImage(image: CommandImage | null): CommandImage | null {
    if (!image || image.status === "UPLOADED" || image.status === "FAILED") {
      return image;
    }

    return {
      ...image,
      status: "FAILED",
    };
  }
}

export class CommandServiceError extends Error {
  readonly code: string;
  readonly retryable: boolean;

  constructor(options: { code: string; message: string; retryable: boolean }) {
    super(options.message);
    this.name = "CommandServiceError";
    this.code = options.code;
    this.retryable = options.retryable;
  }
}
