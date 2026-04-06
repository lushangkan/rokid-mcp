import type { TSchema } from "@sinclair/typebox";
import {
  CommandStatusResponseSchema,
  type CommandRecord,
  type CommandStatusResponse,
  type ErrorResponse,
  RequestIdSchema,
  type ReservedImage,
  type SubmitCommandRequest,
  SubmitCommandRequestSchema,
  SubmitCommandResponseSchema,
  type SubmitCommandResponse,
} from "@rokid-mcp/protocol";
import { Value } from "@sinclair/typebox/value";
import { Elysia } from "elysia";

import { RelayAppError } from "../lib/errors.ts";
import { CommandService, CommandServiceError } from "../modules/command/command-service.ts";
import type { DeviceSessionManager } from "../modules/device/device-session-manager.ts";

type HttpCommandsRoutesOptions = {
  manager: DeviceSessionManager;
  commandService: CommandService;
  dispatchPendingCommand?: (requestId: string) => boolean;
};

type RouteSuccess<T> = {
  ok: true;
  value: T;
};

type RouteFailure = {
  ok: false;
  status: number;
  body: ErrorResponse;
};

type RouteResult<T> = RouteSuccess<T> | RouteFailure;

export function createHttpCommandsRoutes(options: HttpCommandsRoutesOptions) {
  return new Elysia({ name: "http-commands-routes" })
    .post("/api/v1/commands", async ({ request, set }) => {
      const parsedBody = await parseProtocolJson<SubmitCommandRequest>(request, SubmitCommandRequestSchema);
      if (!parsedBody.ok) {
        set.status = parsedBody.status;
        return parsedBody.body;
      }

      try {
        const sessionId = resolveActiveSessionId(options.manager, parsedBody.value.deviceId);
        const submitted = options.commandService.submitCommand({
          request: parsedBody.value,
          sessionId,
        });
        options.dispatchPendingCommand?.(submitted.command.requestId);
        const latestCommand = options.commandService.getCommand(submitted.command.requestId) ?? submitted.command;

        const response = buildSubmitCommandResponse(latestCommand, request.url);
        set.status = 202;
        return response;
      } catch (error) {
        const failure = mapRouteError(error);
        set.status = failure.status;
        return failure.body;
      }
    })
    .get("/api/v1/commands/:requestId", ({ params, set }) => {
      const requestId = (params as { requestId?: unknown }).requestId;
      if (!Value.Check(RequestIdSchema, requestId)) {
        const failure = createErrorResponse({
          status: 422,
          code: "HTTP_VALIDATION_FAILED",
          message: "Route parameter 'requestId' failed protocol validation.",
          details: {
            issues: collectValidationIssues(RequestIdSchema, requestId),
          },
        });
        set.status = failure.status;
        return failure.body;
      }

      const command = options.commandService.getCommand(requestId);
      if (!command) {
        const failure = createErrorResponse({
          status: 404,
          code: "COMMAND_NOT_FOUND",
          message: `Command '${requestId}' does not exist.`,
          retryable: false,
        });
        set.status = failure.status;
        return failure.body;
      }

      const response = buildCommandStatusResponse(command);
      set.status = 200;
      return response;
    });
}

function resolveActiveSessionId(manager: DeviceSessionManager, deviceId: string): string {
  const device = manager.getCurrentDeviceStatus(deviceId).device;

  if (device.sessionState !== "ONLINE" || !device.sessionId) {
    throw new RelayAppError(
      "DEVICE_OFFLINE",
      `Device '${deviceId}' is not currently connected to the relay.`,
      true,
    );
  }

  if (device.setupState !== "INITIALIZED") {
    throw new RelayAppError(
      "DEVICE_NOT_INITIALIZED",
      `Device '${deviceId}' is not initialized for command submission.`,
      true,
    );
  }

  return device.sessionId;
}

async function parseProtocolJson<T>(request: Request, schema: TSchema): Promise<RouteResult<T>> {
  let parsed: unknown;

  try {
    parsed = await request.json();
  } catch {
    return createErrorResponse({
      status: 400,
      code: "HTTP_BAD_JSON",
      message: "Request body must be valid JSON.",
      details: {
        source: "body",
      },
    });
  }

  if (!Value.Check(schema, parsed)) {
    return createErrorResponse({
      status: 422,
      code: "HTTP_VALIDATION_FAILED",
      message: "Request body failed protocol validation.",
      details: {
        issues: collectValidationIssues(schema, parsed),
      },
    });
  }

  return {
    ok: true,
    value: parsed as T,
  };
}

function collectValidationIssues(schema: TSchema, value: unknown) {
  return [...Value.Errors(schema, value)].map((error) => ({
    path: error.path,
    message: error.message,
  }));
}

function buildSubmitCommandResponse(command: CommandRecord, requestUrl: string): SubmitCommandResponse {
  const statusUrl = new URL(`/api/v1/commands/${command.requestId}`, requestUrl).toString();
  const status = toSubmissionStatus(command.status);

  const response: SubmitCommandResponse =
    command.action === "display_text"
      ? {
          ok: true,
          requestId: command.requestId,
          deviceId: command.deviceId,
          action: "display_text",
          status,
          createdAt: command.createdAt,
          statusUrl,
        }
      : {
          ok: true,
          requestId: command.requestId,
          deviceId: command.deviceId,
          action: "capture_photo",
          status,
          createdAt: command.createdAt,
          statusUrl,
          image: toReservedImage(command.image),
        };

  return assertProtocolShape(SubmitCommandResponseSchema, response);
}

function toReservedImage(image: CommandRecord["image"]): ReservedImage {
  if (!image || image.status !== "RESERVED" || typeof image.expiresAt !== "number") {
    throw new RelayAppError(
      "INTERNAL_PROTOCOL_RESPONSE_INVALID",
      "Capture photo submission must expose a reserved relay image record.",
    );
  }

  return {
    imageId: image.imageId,
    transferId: image.transferId,
    status: "RESERVED",
    mimeType: image.mimeType,
    expiresAt: image.expiresAt,
  };
}

function buildCommandStatusResponse(command: CommandRecord): CommandStatusResponse {
  const response: CommandStatusResponse = {
    ok: true,
    command,
    timestamp: Date.now(),
  };

  return assertProtocolShape(CommandStatusResponseSchema, response);
}

function toSubmissionStatus(status: CommandRecord["status"]): SubmitCommandResponse["status"] {
  if (status === "CREATED" || status === "DISPATCHED_TO_PHONE") {
    return status;
  }

  throw new RelayAppError(
    "INTERNAL_PROTOCOL_RESPONSE_INVALID",
    `Command submission cannot expose terminal status '${status}'.`,
  );
}

function assertProtocolShape<T>(schema: TSchema, value: T): T {
  if (!Value.Check(schema, value)) {
    throw new RelayAppError(
      "INTERNAL_PROTOCOL_RESPONSE_INVALID",
      "Route produced a response that does not match the shared relay protocol.",
    );
  }

  return value;
}

function mapRouteError(error: unknown): RouteFailure {
  if (error instanceof RelayAppError) {
    return createErrorResponse({
      status: mapRelayAppErrorStatus(error.code),
      code: error.code,
      message: error.message,
      retryable: error.retryable,
    });
  }

  if (error instanceof CommandServiceError) {
    return createErrorResponse({
      status: mapCommandServiceErrorStatus(error.code),
      code: error.code,
      message: error.message,
      retryable: error.retryable,
    });
  }

  return createErrorResponse({
    status: 500,
    code: "INTERNAL_SERVER_ERROR",
    message: "Unexpected relay error while handling the request.",
  });
}

function mapRelayAppErrorStatus(code: string): number {
  switch (code) {
    case "DEVICE_OFFLINE":
    case "DEVICE_NOT_INITIALIZED":
      return 409;
    case "INTERNAL_PROTOCOL_RESPONSE_INVALID":
      return 500;
    default:
      return 400;
  }
}

function mapCommandServiceErrorStatus(code: string): number {
  switch (code) {
    case "COMMAND_NOT_FOUND":
      return 404;
    case "DEVICE_BUSY":
    case "COMMAND_SEQUENCE_INVALID":
    case "COMMAND_ALREADY_FINISHED":
    case "COMMAND_STATUS_INVALID":
    case "COMMAND_RESULT_INVALID":
      return 409;
    case "INTERNAL_UNEXPECTED_STATE":
      return 500;
    default:
      return 400;
  }
}

function createErrorResponse(options: {
  status: number;
  code: string;
  message: string;
  retryable?: boolean;
  details?: Record<string, unknown>;
}): RouteFailure {
  return {
    ok: false,
    status: options.status,
    body: {
      ok: false,
      error: {
        code: options.code,
        message: options.message,
        retryable: options.retryable ?? false,
        ...(options.details ? { details: options.details } : {}),
      },
      timestamp: Date.now(),
    },
  };
}
