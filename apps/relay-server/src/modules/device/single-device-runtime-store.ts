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
  private current: CurrentRuntimeSnapshot | undefined;

  get(deviceId: string): CurrentRuntimeSnapshot | undefined {
    if (!this.current || this.current.deviceId !== deviceId) {
      return undefined;
    }

    return this.current;
  }

  set(snapshot: CurrentRuntimeSnapshot): void {
    this.current = snapshot;
  }

  delete(deviceId: string): void {
    if (this.current?.deviceId === deviceId) {
      this.current = undefined;
    }
  }
}
