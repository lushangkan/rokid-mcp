import type {
  Capabilities,
  GetDeviceStatusResponse,
  RuntimeState,
  SetupState,
} from "@rokid-mcp/protocol";

import { logger, type LogContext } from "../../lib/logger.ts";
import {
  SingleDeviceRuntimeStore,
  type CurrentRuntimeSnapshot,
} from "./single-device-runtime-store.ts";
import {
  SingleDeviceSessionStore,
  type CurrentSessionRecord,
} from "./single-device-session-store.ts";

type RegisterHelloInput = {
  deviceId: string;
  socketId: string;
  payload: {
    setupState: SetupState;
    runtimeState: RuntimeState;
    capabilities: Capabilities;
  };
};

type HeartbeatInput = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  payload: {
    runtimeState: RuntimeState;
    activeCommandRequestId: string | null;
  };
};

type PhoneStateUpdateInput = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  payload: {
    setupState: SetupState;
    runtimeState: RuntimeState;
    activeCommandRequestId: string | null;
    lastErrorCode?: string | null;
    lastErrorMessage?: string | null;
  };
};

type DeviceSessionManagerOptions = {
  heartbeatTimeoutMs: number;
  cleanupIntervalMs: number;
};

const DEFAULT_STATES = {
  setupState: "UNINITIALIZED" as const,
  runtimeState: "DISCONNECTED" as const,
};

function createPhoneLogContext(
  deviceId: string,
  context: LogContext = {},
): LogContext {
  return {
    phone_id: deviceId,
    deviceId,
    ...context,
  };
}

export class DeviceSessionManager {
  private readonly sessions = new SingleDeviceSessionStore();
  private readonly runtimes = new SingleDeviceRuntimeStore();
  private readonly heartbeatTimeoutMs: number;
  private readonly cleanupIntervalMs: number;
  private cleanupTimer: ReturnType<typeof setInterval> | null = null;

  constructor(options: DeviceSessionManagerOptions) {
    this.heartbeatTimeoutMs = options.heartbeatTimeoutMs;
    this.cleanupIntervalMs = options.cleanupIntervalMs;
  }

  registerHello(input: RegisterHelloInput): string {
    const seenAt = Date.now();
    const previous = this.sessions.get();
    const sessionId = `ses_${crypto.randomUUID().replace(/-/g, "_")}`;
    const record: CurrentSessionRecord = {
      deviceId: input.deviceId,
      sessionId,
      socketId: input.socketId,
      connected: true,
      sessionState: "ONLINE",
      setupState: input.payload.setupState,
      runtimeState: input.payload.runtimeState,
      capabilities: [...input.payload.capabilities],
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: seenAt,
    };

    this.sessions.set(record);
    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: seenAt,
    });

    logger.info(
      "phone session registered",
      createPhoneLogContext(input.deviceId, {
        sessionId,
        socketId: input.socketId,
        setupState: input.payload.setupState,
        runtimeState: input.payload.runtimeState,
        capabilities: [...input.payload.capabilities],
        replacedPhoneId: previous?.deviceId ?? null,
        replacedSessionId: previous?.sessionId ?? null,
      }),
    );

    return sessionId;
  }

  confirmHello(deviceId: string, sessionId: string, socketId?: string): boolean {
    const current = this.sessions.get();
    if (!current || current.deviceId !== deviceId || current.sessionId !== sessionId) {
      logger.info(
        "phone hello confirmation ignored",
        createPhoneLogContext(deviceId, {
          sessionId,
          socketId: socketId ?? null,
          reason: "session_not_current",
        }),
      );
      return false;
    }

    if (socketId && current.socketId !== socketId) {
      logger.info(
        "phone hello confirmation ignored",
        createPhoneLogContext(deviceId, {
          sessionId,
          socketId,
          expectedSocketId: current.socketId,
          reason: "socket_mismatch",
        }),
      );
      return false;
    }

    this.sessions.patch({
      connected: true,
      sessionState: "ONLINE",
    });

    logger.info(
      "phone session confirmed",
      createPhoneLogContext(deviceId, {
        sessionId,
        socketId: current.socketId,
        sessionState: "ONLINE",
      }),
    );

    return true;
  }

  markInboundSeen(deviceId: string, sessionId: string, socketId: string): boolean {
    const seenAt = Date.now();
    const current = this.sessions.get();
    if (!current || !this.matchesCurrentSession(deviceId, sessionId, socketId)) {
      return false;
    }

    this.sessions.patch({
      lastSeenAt: seenAt,
      connected: true,
      sessionState: "ONLINE",
    });

    if (this.runtimes.get()?.deviceId === deviceId) {
      this.runtimes.patch({
        lastSeenAt: seenAt,
      });
    }
    return true;
  }

  matchesCurrentSession(deviceId: string, sessionId: string, socketId: string): boolean {
    const current = this.sessions.get();
    return Boolean(
      current &&
        current.deviceId === deviceId &&
        current.sessionId === sessionId &&
        current.socketId === socketId,
    );
  }

  markHeartbeat(input: HeartbeatInput): boolean {
    const seenAt = Date.now();
    const current = this.sessions.get();
    if (!current || !this.matchesCurrentSession(input.deviceId, input.sessionId, input.socketId)) {
      logger.info(
        "phone heartbeat ignored",
        createPhoneLogContext(input.deviceId, {
          sessionId: input.sessionId,
          socketId: input.socketId,
          reason: "session_not_current",
        }),
      );
      return false;
    }

    this.sessions.patch({
      runtimeState: input.payload.runtimeState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: seenAt,
      connected: true,
      sessionState: "ONLINE",
    });

    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: seenAt,
    });

    logger.info(
      "phone heartbeat received",
      createPhoneLogContext(input.deviceId, {
        sessionId: input.sessionId,
        socketId: input.socketId,
        runtimeState: input.payload.runtimeState,
        activeCommandRequestId: input.payload.activeCommandRequestId,
        lastSeenAt: seenAt,
      }),
    );

    return true;
  }

  applyPhoneStateUpdate(input: PhoneStateUpdateInput): boolean {
    const seenAt = Date.now();
    const current = this.sessions.get();
    if (!current || !this.matchesCurrentSession(input.deviceId, input.sessionId, input.socketId)) {
      logger.info(
        "phone state update ignored",
        createPhoneLogContext(input.deviceId, {
          sessionId: input.sessionId,
          socketId: input.socketId,
          reason: "session_not_current",
        }),
      );
      return false;
    }

    this.sessions.patch({
      setupState: input.payload.setupState,
      runtimeState: input.payload.runtimeState,
      lastSeenAt: seenAt,
      connected: true,
      sessionState: "ONLINE",
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: input.payload.lastErrorCode ?? null,
      lastErrorMessage: input.payload.lastErrorMessage ?? null,
    });

    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: input.payload.lastErrorCode ?? null,
      lastErrorMessage: input.payload.lastErrorMessage ?? null,
      lastSeenAt: seenAt,
    });

    logger.info(
      "phone state updated",
      createPhoneLogContext(input.deviceId, {
        sessionId: input.sessionId,
        socketId: input.socketId,
        setupState: input.payload.setupState,
        runtimeState: input.payload.runtimeState,
        activeCommandRequestId: input.payload.activeCommandRequestId,
        lastErrorCode: input.payload.lastErrorCode ?? null,
        lastErrorMessage: input.payload.lastErrorMessage ?? null,
        lastSeenAt: seenAt,
      }),
    );

    return true;
  }

  closeCurrentSession(deviceId: string, sessionId: string, socketId: string): boolean {
    const current = this.sessions.get();
    if (!current || !this.matchesCurrentSession(deviceId, sessionId, socketId)) {
      logger.info(
        "phone session close ignored",
        createPhoneLogContext(deviceId, {
          sessionId,
          socketId,
          reason: "session_not_current",
        }),
      );
      return false;
    }

    this.sessions.patch({
      connected: false,
      sessionState: "CLOSED",
      runtimeState: "DISCONNECTED",
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
    });
    this.runtimes.clear();

    logger.info(
      "phone session closed",
      createPhoneLogContext(deviceId, {
        sessionId,
        socketId,
        sessionState: "CLOSED",
        runtimeState: "DISCONNECTED",
      }),
    );

    return true;
  }

  getCurrentDeviceStatus(deviceId: string): GetDeviceStatusResponse {
    const now = Date.now();
    const current = this.sessions.get();

    if (!current || current.deviceId !== deviceId) {
      return {
        ok: true,
        device: {
          deviceId,
          connected: false,
          sessionState: "OFFLINE",
          setupState: DEFAULT_STATES.setupState,
          runtimeState: DEFAULT_STATES.runtimeState,
          capabilities: [],
          activeCommandRequestId: null,
          lastErrorCode: null,
          lastErrorMessage: null,
          lastSeenAt: null,
          sessionId: null,
        },
        timestamp: now,
      };
    }

    const runtime = this.runtimes.get();
    const stale = current.sessionState === "STALE";
    const closed = current.sessionState === "CLOSED" || current.connected === false;

    return {
      ok: true,
      device: {
        deviceId,
        connected: stale ? false : current.connected,
        sessionState: current.sessionState,
        setupState: current.setupState,
        runtimeState: stale || closed ? "DISCONNECTED" : runtime?.runtimeState ?? current.runtimeState,
        capabilities: [...current.capabilities],
        activeCommandRequestId: stale || closed ? null : runtime?.activeCommandRequestId ?? current.activeCommandRequestId,
        lastErrorCode: stale || closed ? null : runtime?.lastErrorCode ?? current.lastErrorCode,
        lastErrorMessage: stale || closed ? null : runtime?.lastErrorMessage ?? current.lastErrorMessage,
        lastSeenAt: current.lastSeenAt,
        sessionId: current.sessionId,
      },
      timestamp: now,
    };
  }

  startCleanupJob(): void {
    if (this.cleanupTimer) {
      return;
    }

    this.cleanupTimer = setInterval(() => {
      this.sweepStaleSessions(Date.now());
    }, this.cleanupIntervalMs);
  }

  stopCleanupJob(): void {
    if (!this.cleanupTimer) {
      return;
    }

    clearInterval(this.cleanupTimer);
    this.cleanupTimer = null;
  }

  private sweepStaleSessions(now: number): void {
    const record = this.sessions.get();
    if (!record || !this.isStale(record, now) || record.sessionState !== "ONLINE") {
      return;
    }

    this.sessions.patch({
      connected: false,
      sessionState: "STALE",
    });
    this.runtimes.set({
      deviceId: record.deviceId,
      runtimeState: "DISCONNECTED",
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: record.lastSeenAt,
    } satisfies CurrentRuntimeSnapshot);

    logger.info(
      "phone session marked stale",
      createPhoneLogContext(record.deviceId, {
        sessionId: record.sessionId,
        socketId: record.socketId,
        sessionState: "STALE",
        runtimeState: "DISCONNECTED",
        lastSeenAt: record.lastSeenAt,
        heartbeatTimeoutMs: this.heartbeatTimeoutMs,
      }),
    );
  }

  private isStale(record: CurrentSessionRecord, now: number): boolean {
    return now - record.lastSeenAt > this.heartbeatTimeoutMs;
  }
}
