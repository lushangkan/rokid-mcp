import {
  ACCEPTED_IMAGE_CONTENT_TYPES,
  DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES,
  PROTOCOL_VERSION,
  RelayDeviceInboundMessageSchema,
  RelayDeviceOutboundMessageSchema,
  type CommandAckMessage,
  type CommandErrorMessage,
  type CommandResultMessage,
  type CommandStatusMessage,
  type RelayDeviceInboundMessage,
  type RelayDeviceOutboundMessage,
  type RelayHelloAckMessage,
} from "@rokid-mcp/protocol";
import { Value } from "@sinclair/typebox/value";
import { Elysia } from "elysia";

import { validateTokenAgainstAllowlist } from "../lib/auth.ts";
import { logger, toLogError, type LogContext } from "../lib/logger.ts";
import { CommandService, CommandServiceError } from "../modules/command/command-service.ts";
import type { DeviceSessionManager } from "../modules/device/device-session-manager.ts";

type DeviceWsAuthState = {
  deviceId?: string;
  sessionId?: string;
  socketId: string;
  authenticatedPrincipalId?: string;
  authenticatedScope?: "device";
  helloTimeout?: ReturnType<typeof setTimeout>;
};

type DeviceSocketLike = {
  data: DeviceWsAuthState;
  send: (payload: unknown) => unknown;
  close: (code?: number, reason?: string) => void;
};

type InboundWsRaw = unknown;

type DeviceWsHandlersOptions = {
  manager: DeviceSessionManager;
  commandService: CommandService;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
  helloTimeoutMs: number;
  wsAuthTokens: string[];
};

const CLOSE_UNSUPPORTED_DATA = 1003;
const CLOSE_POLICY_VIOLATION = 1008;
const CLOSE_INTERNAL_ERROR = 1011;

type AuthenticatedDeviceSocket = DeviceSocketLike & {
  data: Required<Pick<DeviceWsAuthState, "deviceId" | "sessionId" | "socketId">> & DeviceWsAuthState;
};

type CommandInboundMessage = CommandAckMessage | CommandStatusMessage | CommandResultMessage | CommandErrorMessage;

type ActiveSocketRecord = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  socket: AuthenticatedDeviceSocket;
};

type DeviceWsHandlers = {
  open: (ws: DeviceSocketLike) => void;
  message: (ws: DeviceSocketLike, raw: InboundWsRaw) => void;
  close: (ws: DeviceSocketLike) => void;
};

export type DeviceWsController = {
  app: Elysia;
  handlers: DeviceWsHandlers;
  dispatchPendingCommand: (requestId: string) => boolean;
};

function createSocketId() {
  return `sock_${crypto.randomUUID().replace(/-/g, "_")}`;
}

function createWsLogContext(
  context: LogContext & {
    deviceId?: string;
  },
): LogContext {
  const { deviceId, ...rest } = context;

  return {
    ...(deviceId
      ? {
          phone_id: deviceId,
          deviceId,
        }
      : {}),
    ...rest,
  };
}

function createHelloAckMessage(
  deviceId: string,
  sessionId: string,
  options: DeviceWsHandlersOptions,
): RelayHelloAckMessage {
  return {
    version: PROTOCOL_VERSION,
    type: "hello_ack",
    deviceId,
    timestamp: Date.now(),
    payload: {
      sessionId,
      serverTime: Date.now(),
      heartbeatIntervalMs: options.heartbeatIntervalMs,
      heartbeatTimeoutMs: options.heartbeatTimeoutMs,
        limits: {
          maxPendingCommands: 1,
          maxImageUploadSizeBytes: DEFAULT_MAX_IMAGE_UPLOAD_SIZE_BYTES,
          acceptedImageContentTypes: [...ACCEPTED_IMAGE_CONTENT_TYPES],
        },
      },
    };
}

type ParsedInboundMessage =
  | { ok: true; message: RelayDeviceInboundMessage }
  | { ok: false; closeCode: number; debugContext?: LogContext };

function describeInboundRaw(raw: InboundWsRaw): LogContext {
  if (typeof raw === "string") {
    return {
      inboundRuntimeType: "string",
      inboundLength: raw.length,
    };
  }

  if (Buffer.isBuffer(raw)) {
    return {
      inboundRuntimeType: "Buffer",
      inboundLength: raw.byteLength,
    };
  }

  if (raw instanceof ArrayBuffer) {
    return {
      inboundRuntimeType: "ArrayBuffer",
      inboundLength: raw.byteLength,
    };
  }

  if (ArrayBuffer.isView(raw)) {
    return {
      inboundRuntimeType: raw.constructor.name,
      inboundLength: raw.byteLength,
    };
  }

  if (raw !== null && typeof raw === "object") {
    return {
      inboundRuntimeType: raw.constructor?.name ?? "object",
      inboundKeys: Object.keys(raw).slice(0, 10),
    };
  }

  return {
    inboundRuntimeType: typeof raw,
  };
}

function normalizeInboundMessage(raw: InboundWsRaw): string | object | null {
  if (typeof raw === "string") {
    return raw;
  }

  if (Buffer.isBuffer(raw)) {
    return raw.toString("utf8");
  }

  if (raw instanceof ArrayBuffer) {
    return new TextDecoder().decode(new Uint8Array(raw));
  }

  if (ArrayBuffer.isView(raw)) {
    return new TextDecoder().decode(new Uint8Array(raw.buffer, raw.byteOffset, raw.byteLength));
  }

  if (raw !== null && typeof raw === "object") {
    return raw;
  }

  return null;
}

function parseInboundMessage(raw: InboundWsRaw): ParsedInboundMessage {
  const normalized = normalizeInboundMessage(raw);
  if (normalized === null) {
    return {
      ok: false,
      closeCode: CLOSE_UNSUPPORTED_DATA,
      debugContext: describeInboundRaw(raw),
    };
  }

  let parsed: unknown;
  if (typeof normalized === "string") {
    try {
      parsed = JSON.parse(normalized);
    } catch {
      return {
        ok: false,
        closeCode: CLOSE_UNSUPPORTED_DATA,
        debugContext: {
          ...describeInboundRaw(raw),
          normalizedRuntimeType: "string",
          normalizedLength: normalized.length,
        },
      };
    }
  } else {
    parsed = normalized;
  }

  if (!Value.Check(RelayDeviceInboundMessageSchema, parsed)) {
    return { ok: false, closeCode: CLOSE_POLICY_VIOLATION };
  }

  return { ok: true, message: parsed };
}

function hasAuthenticatedSession(
  ws: DeviceSocketLike,
): ws is AuthenticatedDeviceSocket {
  return Boolean(ws.data.deviceId && ws.data.sessionId && ws.data.socketId);
}

function getOptionalSessionId(inbound: Exclude<RelayDeviceInboundMessage, { type: "hello" }>) {
  return "sessionId" in inbound ? inbound.sessionId : undefined;
}

function isCommandInboundMessage(inbound: RelayDeviceInboundMessage): inbound is CommandInboundMessage {
  return (
    inbound.type === "command_ack" ||
    inbound.type === "command_status" ||
    inbound.type === "command_result" ||
    inbound.type === "command_error"
  );
}

function sendOutboundMessage(
  ws: DeviceSocketLike,
  message: RelayDeviceOutboundMessage,
  context: LogContext = {},
): boolean {
  const outboundMessageType = message.type;

  if (!Value.Check(RelayDeviceOutboundMessageSchema, message)) {
    logger.error("relay outbound message failed validation", {
      ...context,
      messageType: outboundMessageType,
    });
    ws.close(CLOSE_INTERNAL_ERROR);
    return false;
  }

  try {
    ws.send(message);
    return true;
  } catch (error) {
    logger.error("relay outbound message send failed", {
      ...context,
      messageType: outboundMessageType,
      error: toLogError(error),
    });
    ws.close(CLOSE_INTERNAL_ERROR);
    return false;
  }
}

function clearHelloTimeout(ws: DeviceSocketLike) {
  if (!ws.data.helloTimeout) {
    return;
  }

  clearTimeout(ws.data.helloTimeout);
  delete ws.data.helloTimeout;
}

function closeSocket(ws: DeviceSocketLike, code: number, reason?: string) {
  clearHelloTimeout(ws);
  ws.close(code, reason);
}

export function buildDeviceWsHandlers(options: DeviceWsHandlersOptions) {
  return createDeviceWsController(options).handlers;
}

export function createDeviceWsController(options: DeviceWsHandlersOptions): DeviceWsController {
  let activeSocket: ActiveSocketRecord | null = null;

  function setActiveSocket(socket: AuthenticatedDeviceSocket) {
    activeSocket = {
      deviceId: socket.data.deviceId,
      sessionId: socket.data.sessionId,
      socketId: socket.data.socketId,
      socket,
    };
  }

  function clearActiveSocket(socket: AuthenticatedDeviceSocket) {
    if (
      activeSocket?.deviceId === socket.data.deviceId &&
      activeSocket.sessionId === socket.data.sessionId &&
      activeSocket.socketId === socket.data.socketId
    ) {
      activeSocket = null;
    }
  }

  function resolveDispatchSocket(deviceId: string, sessionId: string): AuthenticatedDeviceSocket | null {
    if (!activeSocket) {
      return null;
    }

    if (
      activeSocket.deviceId !== deviceId ||
      activeSocket.sessionId !== sessionId ||
      !options.manager.matchesCurrentSession(deviceId, sessionId, activeSocket.socketId)
    ) {
      return null;
    }

    return activeSocket.socket;
  }

  function dispatchPendingCommand(requestId: string): boolean {
    const command = options.commandService.getCommand(requestId);
    if (!command || command.status !== "CREATED") {
      logger.info("command dispatch skipped", {
        requestId,
        status: command?.status ?? null,
        reason: command ? "command_not_created" : "command_not_found",
      });
      return false;
    }

    const device = options.manager.getCurrentDeviceStatus(command.deviceId).device;
    if (device.sessionState !== "ONLINE" || !device.sessionId) {
      logger.info(
        "command dispatch skipped",
        createWsLogContext({
          deviceId: command.deviceId,
          requestId,
          status: command.status,
          sessionState: device.sessionState,
          reason: "device_not_online",
        }),
      );
      return false;
    }

    const socket = resolveDispatchSocket(command.deviceId, device.sessionId);
    if (!socket) {
      logger.info(
        "command dispatch skipped",
        createWsLogContext({
          deviceId: command.deviceId,
          requestId,
          sessionId: device.sessionId,
          status: command.status,
          reason: "socket_not_ready",
        }),
      );
      return false;
    }

    try {
      const dispatched = options.commandService.dispatchCommand({
        requestId,
        sessionId: device.sessionId,
      });
      const sent = sendOutboundMessage(
        socket,
        dispatched.message,
        createWsLogContext({
          deviceId: command.deviceId,
          sessionId: device.sessionId,
          socketId: socket.data.socketId,
          requestId,
          action: dispatched.command.action,
        }),
      );

      if (sent) {
        logger.info(
          "command dispatch delivered to phone",
          createWsLogContext({
            deviceId: command.deviceId,
            sessionId: device.sessionId,
            socketId: socket.data.socketId,
            requestId,
            action: dispatched.command.action,
            status: dispatched.command.status,
          }),
        );
      }

      return sent;
    } catch (error) {
      if (error instanceof CommandServiceError) {
        logger.error(
          "command dispatch failed",
          createWsLogContext({
            deviceId: command.deviceId,
            sessionId: device.sessionId,
            socketId: socket.data.socketId,
            requestId,
            code: error.code,
            error: error.message,
            retryable: error.retryable,
          }),
        );
        return false;
      }

      logger.error(
        "command dispatch failed",
        createWsLogContext({
          deviceId: command.deviceId,
          sessionId: device.sessionId,
          socketId: socket.data.socketId,
          requestId,
          error: toLogError(error),
        }),
      );
      socket.close(CLOSE_INTERNAL_ERROR);
      return false;
    }
  }

  function dispatchActiveCommand(deviceId: string): boolean {
    const activeCommand = options.commandService.getActiveCommand();
    if (!activeCommand || activeCommand.deviceId !== deviceId || activeCommand.status !== "CREATED") {
      return false;
    }

    return dispatchPendingCommand(activeCommand.requestId);
  }

  function handleInboundCommandMessage(ws: AuthenticatedDeviceSocket, inbound: CommandInboundMessage) {
    try {
      switch (inbound.type) {
        case "command_ack":
          options.commandService.handleCommandAck(inbound);
          return;
        case "command_status":
          options.commandService.handleCommandStatus(inbound);
          return;
        case "command_result":
          options.commandService.handleCommandResult(inbound);
          return;
        case "command_error":
          options.commandService.handleCommandError(inbound);
          return;
      }
    } catch (error) {
      logger.error(
        "command inbound message handling failed",
        createWsLogContext({
          deviceId: ws.data.deviceId,
          sessionId: ws.data.sessionId,
          socketId: ws.data.socketId,
          requestId: inbound.requestId,
          messageType: inbound.type,
          error:
            error instanceof CommandServiceError
              ? {
                  code: error.code,
                  message: error.message,
                  retryable: error.retryable,
                }
              : toLogError(error),
        }),
      );
      ws.close(error instanceof CommandServiceError ? CLOSE_POLICY_VIOLATION : CLOSE_INTERNAL_ERROR);
    }
  }

  const handlers = {
    open(ws: DeviceSocketLike) {
      ws.data.socketId ??= createSocketId();
      clearHelloTimeout(ws);
      ws.data.helloTimeout = setTimeout(() => {
        if (hasAuthenticatedSession(ws)) {
          return;
        }

        logger.error("phone hello timed out before authentication", {
          socketId: ws.data.socketId,
          closeCode: CLOSE_POLICY_VIOLATION,
          helloTimeoutMs: options.helloTimeoutMs,
        });
        closeSocket(ws, CLOSE_POLICY_VIOLATION);
      }, options.helloTimeoutMs);

      logger.info("phone socket opened", {
        socketId: ws.data.socketId,
        helloTimeoutMs: options.helloTimeoutMs,
      });
    },

    message(ws: DeviceSocketLike, raw: InboundWsRaw) {
      ws.data.socketId ??= createSocketId();

      const parsed = parseInboundMessage(raw);
      if (!parsed.ok) {
        logger.error("device websocket message rejected", {
          socketId: ws.data.socketId,
          closeCode: parsed.closeCode,
          data: JSON.stringify(raw),
          ...parsed.debugContext,
        });
        closeSocket(ws, parsed.closeCode);
        return;
      }

      const inbound = parsed.message;

      if (inbound.type === "hello") {
        logger.info(
          "phone hello received",
          createWsLogContext({
            deviceId: inbound.deviceId,
            socketId: ws.data.socketId,
            setupState: inbound.payload.setupState,
            runtimeState: inbound.payload.runtimeState,
            capabilities: [...inbound.payload.capabilities],
          }),
        );

        if (!validateTokenAgainstAllowlist(inbound.payload.authToken, options.wsAuthTokens)) {
          logger.error(
            "phone hello rejected due to invalid websocket auth token",
            createWsLogContext({
              deviceId: inbound.deviceId,
              socketId: ws.data.socketId,
              closeCode: CLOSE_POLICY_VIOLATION,
            }),
          );
          closeSocket(ws, CLOSE_POLICY_VIOLATION);
          return;
        }

        clearHelloTimeout(ws);
        ws.data.authenticatedScope = "device";
        ws.data.authenticatedPrincipalId = inbound.deviceId;

        const sessionId = options.manager.registerHello({
          deviceId: inbound.deviceId,
          socketId: ws.data.socketId,
          payload: {
            setupState: inbound.payload.setupState,
            runtimeState: inbound.payload.runtimeState,
            capabilities: inbound.payload.capabilities,
          },
        });

        options.manager.confirmHello(inbound.deviceId, sessionId, ws.data.socketId);
        ws.data.deviceId = inbound.deviceId;
        ws.data.sessionId = sessionId;
        if (!hasAuthenticatedSession(ws)) {
          logger.error(
            "phone hello authentication state invalid",
            createWsLogContext({
              deviceId: inbound.deviceId,
              sessionId,
              socketId: ws.data.socketId,
            }),
          );
          closeSocket(ws, CLOSE_INTERNAL_ERROR);
          return;
        }

        setActiveSocket(ws);
        if (!sendOutboundMessage(
          ws,
          createHelloAckMessage(inbound.deviceId, sessionId, options),
          createWsLogContext({
            deviceId: inbound.deviceId,
            sessionId,
            socketId: ws.data.socketId,
          }),
        )) {
          return;
        }

        logger.info(
          "phone hello acknowledged",
          createWsLogContext({
            deviceId: inbound.deviceId,
            sessionId,
            socketId: ws.data.socketId,
            heartbeatIntervalMs: options.heartbeatIntervalMs,
            heartbeatTimeoutMs: options.heartbeatTimeoutMs,
          }),
        );

        dispatchActiveCommand(inbound.deviceId);
        return;
      }

      if (!hasAuthenticatedSession(ws)) {
        logger.error("device websocket message rejected before hello", {
          socketId: ws.data.socketId,
          messageType: inbound.type,
          closeCode: CLOSE_POLICY_VIOLATION,
        });
        closeSocket(ws, CLOSE_POLICY_VIOLATION);
        return;
      }

      const inboundSessionId = getOptionalSessionId(inbound);

      if (
        inbound.deviceId !== ws.data.deviceId ||
        (inboundSessionId !== undefined && inboundSessionId !== ws.data.sessionId) ||
        !options.manager.matchesCurrentSession(ws.data.deviceId, ws.data.sessionId, ws.data.socketId)
      ) {
        logger.error(
          "device websocket message rejected due to session mismatch",
          createWsLogContext({
            deviceId: ws.data.deviceId,
            sessionId: ws.data.sessionId,
            socketId: ws.data.socketId,
            messageType: inbound.type,
            inboundDeviceId: inbound.deviceId,
            inboundSessionId,
            closeCode: CLOSE_POLICY_VIOLATION,
          }),
        );
        closeSocket(ws, CLOSE_POLICY_VIOLATION);
        return;
      }

      options.manager.markInboundSeen(inbound.deviceId, ws.data.sessionId, ws.data.socketId);

      if (inbound.type === "heartbeat") {
        options.manager.markHeartbeat({
          deviceId: inbound.deviceId,
          sessionId: inbound.sessionId,
          socketId: ws.data.socketId,
          payload: {
            runtimeState: inbound.payload.runtimeState,
            activeCommandRequestId: inbound.payload.activeCommandRequestId,
          },
        });
        return;
      }

      if (inbound.type === "phone_state_update") {
        const activeCommandRequestId = inbound.payload.activeCommandRequestId ?? null;

        options.manager.applyPhoneStateUpdate({
          deviceId: inbound.deviceId,
          sessionId: ws.data.sessionId,
          socketId: ws.data.socketId,
          payload: {
            setupState: inbound.payload.setupState,
            runtimeState: inbound.payload.runtimeState,
            activeCommandRequestId,
            lastErrorCode: inbound.payload.lastErrorCode,
            lastErrorMessage: inbound.payload.lastErrorMessage,
          },
        });
        return;
      }

      if (!isCommandInboundMessage(inbound)) {
        logger.error(
          "device websocket message rejected due to unsupported authenticated type",
        createWsLogContext({
          deviceId: ws.data.deviceId,
          sessionId: ws.data.sessionId,
          socketId: ws.data.socketId,
          messageType: "unsupported_authenticated_message",
          closeCode: CLOSE_POLICY_VIOLATION,
        }),
      );
        closeSocket(ws, CLOSE_POLICY_VIOLATION);
        return;
      }

      handleInboundCommandMessage(ws, inbound);
    },

    close(ws: DeviceSocketLike) {
      clearHelloTimeout(ws);

      if (!hasAuthenticatedSession(ws)) {
        logger.info("phone socket closed before authentication", {
          socketId: ws.data.socketId,
        });
        return;
      }

      clearActiveSocket(ws);
      const closed = options.manager.closeCurrentSession(ws.data.deviceId, ws.data.sessionId, ws.data.socketId);

      logger.info(
        "phone socket closed",
        createWsLogContext({
          deviceId: ws.data.deviceId,
          sessionId: ws.data.sessionId,
          socketId: ws.data.socketId,
          closedCurrentSession: closed,
        }),
      );
    },
  };

  const app = new Elysia({ name: "ws-device-routes" });
  app.ws("/ws/device", {
    open(ws) {
      handlers.open(ws as unknown as DeviceSocketLike);
    },
    message(ws, message) {
      handlers.message(ws as unknown as DeviceSocketLike, message as InboundWsRaw);
    },
    close(ws) {
      handlers.close(ws as unknown as DeviceSocketLike);
    },
  });

  return {
    app,
    handlers,
    dispatchPendingCommand,
  };
}

export function createDeviceWsRoutes(options: DeviceWsHandlersOptions) {
  return createDeviceWsController(options).app;
}
