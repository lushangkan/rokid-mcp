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
    uplinkState: UplinkState;
    capabilities: Capabilities;
  };
};

type HeartbeatInput = {
  deviceId: string;
  sessionId: string;
  socketId: string;
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
    const seenAt = Date.now();
    const sessionId = `ses_${crypto.randomUUID().replace(/-/g, "_")}`;
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
      lastSeenAt: seenAt,
    };

    this.sessions.set(record);
    this.runtimes.set({
      deviceId: input.deviceId,
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: seenAt,
    });

    return sessionId;
  }

  confirmHello(deviceId: string, sessionId: string, socketId?: string): boolean {
    const current = this.sessions.get();
    if (!current || current.deviceId !== deviceId || current.sessionId !== sessionId) {
      return false;
    }

    if (socketId && current.socketId !== socketId) {
      return false;
    }

    this.sessions.patch({
      connected: true,
      sessionState: "ONLINE",
    });
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
      return false;
    }

    this.sessions.patch({
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
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
      uplinkState: input.payload.uplinkState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: seenAt,
    });
    return true;
  }

  applyPhoneStateUpdate(input: PhoneStateUpdateInput): boolean {
    const seenAt = Date.now();
    const current = this.sessions.get();
    if (!current || !this.matchesCurrentSession(input.deviceId, input.sessionId, input.socketId)) {
      return false;
    }

    this.sessions.patch({
      setupState: input.payload.setupState,
      runtimeState: input.payload.runtimeState,
      uplinkState: input.payload.uplinkState,
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
      uplinkState: input.payload.uplinkState,
      activeCommandRequestId: input.payload.activeCommandRequestId,
      lastErrorCode: input.payload.lastErrorCode ?? null,
      lastErrorMessage: input.payload.lastErrorMessage ?? null,
      lastSeenAt: seenAt,
    });
    return true;
  }

  closeCurrentSession(deviceId: string, sessionId: string, socketId: string): boolean {
    const current = this.sessions.get();
    if (!current || !this.matchesCurrentSession(deviceId, sessionId, socketId)) {
      return false;
    }

    this.sessions.patch({
      connected: false,
      sessionState: "CLOSED",
      runtimeState: "DISCONNECTED",
      uplinkState: "OFFLINE",
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
    });
    this.runtimes.clear();
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
        uplinkState: stale || closed ? "OFFLINE" : runtime?.uplinkState ?? current.uplinkState,
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
      uplinkState: "OFFLINE",
      activeCommandRequestId: null,
      lastErrorCode: null,
      lastErrorMessage: null,
      lastSeenAt: record.lastSeenAt,
    } satisfies CurrentRuntimeSnapshot);
  }

  private isStale(record: CurrentSessionRecord, now: number): boolean {
    return now - record.lastSeenAt > this.heartbeatTimeoutMs;
  }
}
