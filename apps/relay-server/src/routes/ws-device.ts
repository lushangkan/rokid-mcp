import {
  PROTOCOL_VERSION,
  RelayDeviceInboundMessageSchema,
  type RelayDeviceInboundMessage,
  type RelayHelloAckMessage,
} from "@rokid-mcp/protocol";
import { Value } from "@sinclair/typebox/value";
import { Elysia } from "elysia";

import type { DeviceSessionManager } from "../modules/device/device-session-manager.js";

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
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
};

const CLOSE_UNSUPPORTED_DATA = 1003;
const CLOSE_POLICY_VIOLATION = 1008;

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
        maxImageUploadSizeBytes: 5_242_880,
        acceptedImageContentTypes: ["image/jpeg", "image/png"],
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
): ws is DeviceSocketLike & { data: Required<Pick<DeviceWsAuthState, "deviceId" | "sessionId" | "socketId">> & DeviceWsAuthState } {
  return Boolean(ws.data.deviceId && ws.data.sessionId && ws.data.socketId);
}

export function buildDeviceWsHandlers(options: DeviceWsHandlersOptions) {
  return {
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
            uplinkState: inbound.payload.uplinkState,
            capabilities: inbound.payload.capabilities,
          },
        });

        options.manager.confirmHello(inbound.deviceId, sessionId, ws.data.socketId);
        ws.data.deviceId = inbound.deviceId;
        ws.data.sessionId = sessionId;
        ws.data.authToken = inbound.payload.authToken;
        ws.send(createHelloAckMessage(inbound.deviceId, sessionId, options));
        return;
      }

      if (!hasAuthenticatedSession(ws)) {
        ws.close(CLOSE_POLICY_VIOLATION);
        return;
      }

      if (
        inbound.deviceId !== ws.data.deviceId ||
        inbound.sessionId !== ws.data.sessionId ||
        !options.manager.matchesCurrentSession(inbound.deviceId, inbound.sessionId, ws.data.socketId)
      ) {
        ws.close(CLOSE_POLICY_VIOLATION);
        return;
      }

      options.manager.markInboundSeen(inbound.deviceId, inbound.sessionId, ws.data.socketId);

      if (inbound.type === "heartbeat") {
        options.manager.markHeartbeat({
          deviceId: inbound.deviceId,
          sessionId: inbound.sessionId,
          socketId: ws.data.socketId,
          payload: {
            runtimeState: inbound.payload.runtimeState,
            uplinkState: inbound.payload.uplinkState,
            activeCommandRequestId: inbound.payload.activeCommandRequestId,
          },
        });
        return;
      }

      options.manager.applyPhoneStateUpdate({
        deviceId: inbound.deviceId,
        sessionId: inbound.sessionId,
        socketId: ws.data.socketId,
        payload: {
          setupState: inbound.payload.setupState,
          runtimeState: inbound.payload.runtimeState,
          uplinkState: inbound.payload.uplinkState,
          activeCommandRequestId: inbound.payload.activeCommandRequestId,
          lastErrorCode: inbound.payload.lastErrorCode,
          lastErrorMessage: inbound.payload.lastErrorMessage,
        },
      });
    },

    close(ws: DeviceSocketLike) {
      if (!hasAuthenticatedSession(ws)) {
        return;
      }

      options.manager.closeCurrentSession(ws.data.deviceId, ws.data.sessionId, ws.data.socketId);
    },
  };
}

export function createDeviceWsRoutes(options: DeviceWsHandlersOptions) {
  const handlers = buildDeviceWsHandlers(options);
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

  return app;
}
