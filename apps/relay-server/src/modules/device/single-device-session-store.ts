import type {
  Capabilities,
  DeviceSessionState,
  RuntimeState,
  SetupState,
} from "@rokid-mcp/protocol";

export type CurrentSessionRecord = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  connected: boolean;
  sessionState: DeviceSessionState;
  setupState: SetupState;
  runtimeState: RuntimeState;
  capabilities: Capabilities;
  activeCommandRequestId: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  lastSeenAt: number;
};

export class SingleDeviceSessionStore {
  private current: CurrentSessionRecord | undefined;

  get(): CurrentSessionRecord | undefined {
    return this.current;
  }

  set(record: CurrentSessionRecord): void {
    this.current = record;
  }

  patch(update: Partial<CurrentSessionRecord>): CurrentSessionRecord | undefined {
    if (!this.current) {
      return undefined;
    }

    this.current = {
      ...this.current,
      ...update,
    };

    return this.current;
  }

  clear(): void {
    this.current = undefined;
  }
}
