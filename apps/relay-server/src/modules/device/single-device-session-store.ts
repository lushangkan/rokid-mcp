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
  private readonly records = new Map<string, CurrentSessionRecord>();

  get(deviceId: string): CurrentSessionRecord | undefined {
    return this.records.get(deviceId);
  }

  set(record: CurrentSessionRecord): void {
    this.records.set(record.deviceId, record);
  }

  delete(deviceId: string): void {
    this.records.delete(deviceId);
  }

  values(): IterableIterator<CurrentSessionRecord> {
    return this.records.values();
  }
}
