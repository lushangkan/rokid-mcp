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

  get(): CurrentRuntimeSnapshot | undefined {
    return this.current;
  }

  set(snapshot: CurrentRuntimeSnapshot): void {
    this.current = snapshot;
  }

  patch(update: Partial<CurrentRuntimeSnapshot>): CurrentRuntimeSnapshot | undefined {
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
