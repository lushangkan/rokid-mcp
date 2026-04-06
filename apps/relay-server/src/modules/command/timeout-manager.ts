export type CommandTimeoutPhase = "ack" | "execution";

export type TimeoutScheduler = {
  setTimeout(callback: () => void, timeoutMs: number): unknown;
  clearTimeout(handle: unknown): void;
};

export type CommandTimeoutEntry = {
  requestId: string;
  phase: CommandTimeoutPhase;
  timeoutMs: number;
};

type TimeoutRegistration = CommandTimeoutEntry & {
  handle: unknown;
};

const DEFAULT_TIMEOUT_SCHEDULER: TimeoutScheduler = {
  setTimeout(callback, timeoutMs) {
    return setTimeout(callback, timeoutMs);
  },
  clearTimeout(handle) {
    clearTimeout(handle as ReturnType<typeof setTimeout>);
  },
};

export class TimeoutManager {
  private readonly scheduler: TimeoutScheduler;
  private readonly registrations = new Map<string, TimeoutRegistration>();

  constructor(scheduler: TimeoutScheduler = DEFAULT_TIMEOUT_SCHEDULER) {
    this.scheduler = scheduler;
  }

  schedule(entry: CommandTimeoutEntry, onTimeout: (entry: CommandTimeoutEntry) => void): void {
    this.cancel(entry.requestId);

    const handle = this.scheduler.setTimeout(() => {
      this.registrations.delete(entry.requestId);
      onTimeout(entry);
    }, entry.timeoutMs);

    this.registrations.set(entry.requestId, {
      ...entry,
      handle,
    });
  }

  cancel(requestId: string): void {
    const registration = this.registrations.get(requestId);
    if (!registration) {
      return;
    }

    this.scheduler.clearTimeout(registration.handle);
    this.registrations.delete(requestId);
  }

  get(requestId: string): CommandTimeoutEntry | null {
    const registration = this.registrations.get(requestId);
    if (!registration) {
      return null;
    }

    return {
      requestId: registration.requestId,
      phase: registration.phase,
      timeoutMs: registration.timeoutMs,
    };
  }

  dispose(): void {
    for (const registration of this.registrations.values()) {
      this.scheduler.clearTimeout(registration.handle);
    }

    this.registrations.clear();
  }
}
