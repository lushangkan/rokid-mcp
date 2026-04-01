import type {
  Capabilities,
  GetDeviceStatusResponse,
  RuntimeState,
  SetupState,
  UplinkState,
} from "@rokid-mcp/protocol";

import {
  SingleDeviceRuntimeStore,
  type CurrentRuntimeSnapshot,
} from "./single-device-runtime-store.js";
import {
  SingleDeviceSessionStore,
  type CurrentSessionRecord,
} from "./single-device-session-store.js";

type RegisterHelloInput = {
  deviceId: string;
  socketId: string;
  timestamp: number;
  payload: {
    setupState: SetupState;
    runtimeState: RuntimeState;
    uplinkState: UplinkState;
    capabilities: Capabilities;
  };
};

type HeartbeatInput = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  timestamp: number;
  payload: {
    runtimeState: RuntimeState;
    uplinkState: UplinkState;
    activeCommandRequestId: string | null;
  };
};

type PhoneStateUpdateInput = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  timestamp: number;
  payload: {
    setupState: SetupState;
    runtimeState: RuntimeState;
    uplinkState: UplinkState;
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
  uplinkState: "OFFLINE" as const,
};

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
    const sessionId = crypto.randomUUID();
    const record: CurrentSessionRecord = {
      deviceId: input.deviceId,
      sessionId,
      socketId: input.socketId,
      connected: true,
      sessionState: "ONLINE",
      setupState: input.payload.setupState,
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
      capabilities: [...input.payload.capabilities],
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: input.timestamp,
    };

    this.sessions.set(record);
    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: input.timestamp,
    });

    return sessionId;
  }

  confirmHello(deviceId: string, sessionId: string): boolean {
    const current = this.sessions.get(deviceId);
    if (!current || current.sessionId !== sessionId) {
      return false;
    }

    current.connected = true;
    current.sessionState = "ONLINE";
    this.sessions.set(current);
    return true;
  }

  markInboundSeen(deviceId: string, sessionId: string, socketId: string, timestamp: number): boolean {
    const current = this.sessions.get(deviceId);
    if (!current || !this.matchesCurrentSession(deviceId, sessionId, socketId)) {
      return false;
    }

    current.lastSeenAt = timestamp;
    current.connected = true;
    current.sessionState = "ONLINE";
    this.sessions.set(current);

    const runtime = this.runtimes.get(deviceId);
    if (runtime) {
      runtime.lastSeenAt = timestamp;
      this.runtimes.set(runtime);
    }
    return true;
  }

  matchesCurrentSession(deviceId: string, sessionId: string, socketId: string): boolean {
    const current = this.sessions.get(deviceId);
    return Boolean(current && current.sessionId === sessionId && current.socketId === socketId);
  }

  markHeartbeat(input: HeartbeatInput): boolean {
    if (!this.matchesCurrentSession(input.deviceId, input.sessionId, input.socketId)) {
      return false;
    }

    this.markInboundSeen(input.deviceId, input.sessionId, input.socketId, input.timestamp);
    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: input.timestamp,
    });
    return true;
  }

  applyPhoneStateUpdate(input: PhoneStateUpdateInput): boolean {
    const current = this.sessions.get(input.deviceId);
    if (!current || !this.matchesCurrentSession(input.deviceId, input.sessionId, input.socketId)) {
      return false;
    }

    current.setupState = input.payload.setupState;
    current.lastSeenAt = input.timestamp;
    current.connected = true;
    current.sessionState = "ONLINE";
    current.activeCommandRequestId = input.payload.activeCommandRequestId;
    current.lastErrorCode = input.payload.lastErrorCode ?? null;
    current.lastErrorMessage = input.payload.lastErrorMessage ?? null;
    this.sessions.set(current);

    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: input.payload.lastErrorCode ?? null,
      lastErrorMessage: input.payload.lastErrorMessage ?? null,
      lastSeenAt: input.timestamp,
    });
    return true;
  }

  closeCurrentSession(deviceId: string, sessionId: string, socketId: string): boolean {
    const current = this.sessions.get(deviceId);
    if (!current || !this.matchesCurrentSession(deviceId, sessionId, socketId)) {
      return false;
    }

    current.connected = false;
    current.sessionState = "CLOSED";
    this.sessions.set(current);
    this.runtimes.delete(deviceId);
    return true;
  }

  getCurrentDeviceStatus(deviceId: string): GetDeviceStatusResponse {
    const now = Date.now();
    const current = this.sessions.get(deviceId);

    if (!current) {
      return {
        ok: true,
        device: {
          deviceId,
          connected: false,
          sessionState: "OFFLINE",
          setupState: DEFAULT_STATES.setupState,
          runtimeState: DEFAULT_STATES.runtimeState,
          uplinkState: DEFAULT_STATES.uplinkState,
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

    const runtime = this.runtimes.get(deviceId);
    const stale = this.isStale(current, now);

    if (stale && current.sessionState === "ONLINE") {
      current.sessionState = "STALE";
      current.connected = false;
      this.sessions.set(current);
    }

    return {
      ok: true,
      device: {
        deviceId,
        connected: stale ? false : current.connected,
        sessionState: current.sessionState,
        setupState: current.setupState,
        runtimeState: stale ? "DISCONNECTED" : runtime?.runtimeState ?? current.runtimeState,
        uplinkState: stale ? "OFFLINE" : runtime?.uplinkState ?? current.uplinkState,
        capabilities: [...current.capabilities],
        activeCommandRequestId: stale ? null : runtime?.activeCommandRequestId ?? current.activeCommandRequestId,
        lastErrorCode: stale ? null : runtime?.lastErrorCode ?? current.lastErrorCode,
        lastErrorMessage: stale ? null : runtime?.lastErrorMessage ?? current.lastErrorMessage,
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
    for (const record of this.sessions.values()) {
      if (!this.isStale(record, now) || record.sessionState !== "ONLINE") {
        continue;
      }

      record.connected = false;
      record.sessionState = "STALE";
      this.sessions.set(record);
      this.runtimes.set({
        deviceId: record.deviceId,
        runtimeState: "DISCONNECTED",
        uplinkState: "OFFLINE",
        activeCommandRequestId: null,
        lastErrorCode: null,
        lastErrorMessage: null,
        lastSeenAt: record.lastSeenAt,
      } satisfies CurrentRuntimeSnapshot);
    }
  }

  private isStale(record: CurrentSessionRecord, now: number): boolean {
    return now - record.lastSeenAt > this.heartbeatTimeoutMs;
  }
}
