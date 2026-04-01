import type { RuntimeState, UplinkState } from "@rokid-mcp/protocol";

export type CurrentRuntimeSnapshot = {
  deviceId: string;
  runtimeState: RuntimeState;
  uplinkState: UplinkState;
  activeCommandRequestId: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  lastSeenAt: number;
};

export class SingleDeviceRuntimeStore {
  private readonly snapshots = new Map<string, CurrentRuntimeSnapshot>();

  get(deviceId: string): CurrentRuntimeSnapshot | undefined {
    return this.snapshots.get(deviceId);
  }

  set(snapshot: CurrentRuntimeSnapshot): void {
    this.snapshots.set(snapshot.deviceId, snapshot);
  }

  delete(deviceId: string): void {
    this.snapshots.delete(deviceId);
  }
}
