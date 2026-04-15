import { describe, expect, test } from "bun:test";

import { TimeoutManager, type TimeoutScheduler } from "./timeout-manager.ts";

function createFakeScheduler() {
  let nextHandle = 0;
  const callbacks = new Map<number, () => void>();

  const scheduler: TimeoutScheduler = {
    setTimeout(callback) {
      const handle = ++nextHandle;
      callbacks.set(handle, callback);
      return handle;
    },
    clearTimeout(handle) {
      callbacks.delete(handle as number);
    },
  };

  return {
    scheduler,
    flush(handle: number) {
      callbacks.get(handle)?.();
    },
    getPendingHandles() {
      return [...callbacks.keys()];
    },
  };
}

describe("timeout manager", () => {
  test("replaces an existing timeout for the same request", () => {
    const fake = createFakeScheduler();
    const manager = new TimeoutManager(fake.scheduler);
    const events: string[] = [];

    manager.schedule(
      {
        requestId: "req_1",
        phase: "ack",
        timeoutMs: 10,
      },
      (entry) => {
        events.push(`${entry.requestId}:${entry.phase}`);
      },
    );

    manager.schedule(
      {
        requestId: "req_1",
        phase: "execution",
        timeoutMs: 20,
      },
      (entry) => {
        events.push(`${entry.requestId}:${entry.phase}`);
      },
    );

    expect(fake.getPendingHandles()).toEqual([2]);
    fake.flush(1);
    expect(events).toEqual([]);
    fake.flush(2);
    expect(events).toEqual(["req_1:execution"]);
  });

  test("cancel removes a scheduled timeout", () => {
    const fake = createFakeScheduler();
    const manager = new TimeoutManager(fake.scheduler);
    const events: string[] = [];

    manager.schedule(
      {
        requestId: "req_2",
        phase: "ack",
        timeoutMs: 10,
      },
      (entry) => {
        events.push(`${entry.requestId}:${entry.phase}`);
      },
    );
    manager.cancel("req_2");

    expect(manager.get("req_2")).toBeNull();
    expect(fake.getPendingHandles()).toEqual([]);
    fake.flush(1);
    expect(events).toEqual([]);
  });

  test("dispose clears every scheduled timeout", () => {
    const fake = createFakeScheduler();
    const manager = new TimeoutManager(fake.scheduler);

    manager.schedule(
      {
        requestId: "req_1",
        phase: "ack",
        timeoutMs: 10,
      },
      () => {},
    );
    manager.schedule(
      {
        requestId: "req_2",
        phase: "execution",
        timeoutMs: 20,
      },
      () => {},
    );

    manager.dispose();
    expect(fake.getPendingHandles()).toEqual([]);
  });

  test("timeout manager exposes the current registration", () => {
    const fake = createFakeScheduler();
    const manager = new TimeoutManager(fake.scheduler);

    manager.schedule(
      {
        requestId: "req_1",
        phase: "ack",
        timeoutMs: 10,
      },
      () => {},
    );

    expect(manager.get("req_1")).toEqual({
      requestId: "req_1",
      phase: "ack",
      timeoutMs: 10,
    });
  });
});
