import type {
  Capabilities,
  DeviceSessionState,
  RuntimeState,
  SetupState,
  UplinkState,
} from "@rokid-mcp/protocol";

export type CurrentSessionRecord = {
  deviceId: string;
  sessionId: string;
  socketId: string;
  connected: boolean;
  sessionState: DeviceSessionState;
  setupState: SetupState;
  runtimeState: RuntimeState;
  uplinkState: UplinkState;
  capabilities: Capabilities;
  activeCommandRequestId: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  lastSeenAt: number;
};

export class SingleDeviceSessionStore {
  private current: CurrentSessionRecord | undefined;

  get(deviceId: string): CurrentSessionRecord | undefined {
    if (!this.current || this.current.deviceId !== deviceId) {
      return undefined;
    }

    return this.current;
  }

  set(record: CurrentSessionRecord): void {
    this.current = record;
  }

  delete(deviceId: string): void {
    if (this.current?.deviceId === deviceId) {
      this.current = undefined;
    }
  }

  values(): IterableIterator<CurrentSessionRecord> {
    return [this.current].filter((record): record is CurrentSessionRecord => record !== undefined).values();
  }
}
