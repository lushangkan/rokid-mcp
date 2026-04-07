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

import { CommandService, CommandServiceError } from "../modules/command/command-service.ts";
import type { DeviceSessionManager } from "../modules/device/device-session-manager.ts";

type DeviceWsAuthState = {
  deviceId?: string;
  sessionId?: string;
  socketId: string;
  authToken?: string;
};

type DeviceSocketLike = {
  data: DeviceWsAuthState;
  send: (payload: unknown) => unknown;
  close: (code?: number, reason?: string) => void;
};

type DeviceWsHandlersOptions = {
  manager: DeviceSessionManager;
  commandService: CommandService;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
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
  message: (ws: DeviceSocketLike, raw: string | Buffer) => void;
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
  | { ok: false; closeCode: number };

function parseInboundMessage(raw: string | Buffer): ParsedInboundMessage {
  if (typeof raw !== "string") {
    raw = raw.toString();
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, closeCode: CLOSE_UNSUPPORTED_DATA };
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

function sendOutboundMessage(ws: DeviceSocketLike, message: RelayDeviceOutboundMessage): boolean {
  if (!Value.Check(RelayDeviceOutboundMessageSchema, message)) {
    ws.close(CLOSE_INTERNAL_ERROR);
    return false;
  }

  try {
    ws.send(message);
    return true;
  } catch {
    ws.close(CLOSE_INTERNAL_ERROR);
    return false;
  }
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
      return false;
    }

    const device = options.manager.getCurrentDeviceStatus(command.deviceId).device;
    if (device.sessionState !== "ONLINE" || !device.sessionId) {
      return false;
    }

    const socket = resolveDispatchSocket(command.deviceId, device.sessionId);
    if (!socket) {
      return false;
    }

    try {
      const dispatched = options.commandService.dispatchCommand({
        requestId,
        sessionId: device.sessionId,
      });
      return sendOutboundMessage(socket, dispatched.message);
    } catch (error) {
      if (error instanceof CommandServiceError) {
        return false;
      }

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
      ws.close(error instanceof CommandServiceError ? CLOSE_POLICY_VIOLATION : CLOSE_INTERNAL_ERROR);
    }
  }

  const handlers = {
    open(ws: DeviceSocketLike) {
      ws.data.socketId ??= createSocketId();
    },

    message(ws: DeviceSocketLike, raw: string | Buffer) {
      ws.data.socketId ??= createSocketId();

      const parsed = parseInboundMessage(raw);
      if (!parsed.ok) {
        ws.close(parsed.closeCode);
        return;
      }

      const inbound = parsed.message;

      if (inbound.type === "hello") {
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
        ws.data.authToken = inbound.payload.authToken;
        if (!hasAuthenticatedSession(ws)) {
          ws.close(CLOSE_INTERNAL_ERROR);
          return;
        }

        setActiveSocket(ws);
        if (!sendOutboundMessage(ws, createHelloAckMessage(inbound.deviceId, sessionId, options))) {
          return;
        }

        dispatchActiveCommand(inbound.deviceId);
        return;
      }

      if (!hasAuthenticatedSession(ws)) {
        ws.close(CLOSE_POLICY_VIOLATION);
        return;
      }

      const inboundSessionId = getOptionalSessionId(inbound);

      if (
        inbound.deviceId !== ws.data.deviceId ||
        (inboundSessionId !== undefined && inboundSessionId !== ws.data.sessionId) ||
        !options.manager.matchesCurrentSession(ws.data.deviceId, ws.data.sessionId, ws.data.socketId)
      ) {
        ws.close(CLOSE_POLICY_VIOLATION);
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
        ws.close(CLOSE_POLICY_VIOLATION);
        return;
      }

      handleInboundCommandMessage(ws, inbound);
    },

    close(ws: DeviceSocketLike) {
      if (!hasAuthenticatedSession(ws)) {
        return;
      }

      clearActiveSocket(ws);
      options.manager.closeCurrentSession(ws.data.deviceId, ws.data.sessionId, ws.data.socketId);
    },
  };

  const app = new Elysia({ name: "ws-device-routes" });
  app.ws("/ws/device", {
    open(ws) {
      handlers.open(ws as unknown as DeviceSocketLike);
    },
    message(ws, message) {
      handlers.message(ws as unknown as DeviceSocketLike, message as string | Buffer);
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
