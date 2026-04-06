import { describe, expect, mock, test } from "bun:test";
import type { CommandRecord, GetCommandStatusResponse } from "@rokid-mcp/protocol";

import { createCommandPoller } from "./command-poller.js";

describe("command poller", () => {
  test("command poller keeps polling until the command reaches a terminal relay state", async () => {
    const statuses: CommandRecord["status"][] = ["RUNNING", "RUNNING", "COMPLETED"];
    let index = 0;
    let nowMs = 0;
    const waits: number[] = [];

    const poller = createCommandPoller({
      relayCommandClient: {
        getCommandStatus: mock(async () => createStatusResponse(statuses[index++] ?? "COMPLETED")),
        submitCommand: async () => {
          throw new Error("not used");
        },
        downloadImage: async () => {
          throw new Error("not used");
        },
      },
      timeoutMs: 1_000,
      pollIntervalMs: 250,
      now: () => nowMs,
      wait: async (delayMs) => {
        waits.push(delayMs);
        nowMs += delayMs;
      },
    });

    const result = await poller.pollUntilTerminal("req_poll123");

    expect(result.status).toBe("COMPLETED");
    expect(waits).toEqual([250, 250]);
  });

  test("command poller returns failed terminal states without inventing user-facing text", async () => {
    const poller = createCommandPoller({
      relayCommandClient: {
        getCommandStatus: async () =>
          createStatusResponse("FAILED", {
            error: {
              code: "BLUETOOTH_UNAVAILABLE",
              message: "Bluetooth is unavailable on the phone.",
              retryable: true,
            },
          }),
        submitCommand: async () => {
          throw new Error("not used");
        },
        downloadImage: async () => {
          throw new Error("not used");
        },
      },
      timeoutMs: 1_000,
      pollIntervalMs: 250,
    });

    const result = await poller.pollUntilTerminal("req_failed123");

    expect(result.status).toBe("FAILED");
    expect(result.error?.code).toBe("BLUETOOTH_UNAVAILABLE");
  });

  test("command poller surfaces MCP timeout context when polling exceeds the MCP deadline", async () => {
    let nowMs = 0;

    const poller = createCommandPoller({
      relayCommandClient: {
        getCommandStatus: async () => createStatusResponse("RUNNING"),
        submitCommand: async () => {
          throw new Error("not used");
        },
        downloadImage: async () => {
          throw new Error("not used");
        },
      },
      timeoutMs: 500,
      pollIntervalMs: 300,
      now: () => nowMs,
      wait: async (delayMs) => {
        nowMs += delayMs;
      },
    });

    try {
      await poller.pollUntilTerminal("req_timeout123");
      throw new Error("expected pollUntilTerminal to throw");
    } catch (error) {
      expect(error).toMatchObject({
        code: "MCP_COMMAND_POLL_TIMEOUT",
        details: {
          requestId: "req_timeout123",
          timeoutMs: 500,
          lastStatus: "RUNNING",
        },
      });
    }
  });
});

function createStatusResponse(
  status: CommandRecord["status"],
  overrides: Partial<CommandRecord> = {},
): GetCommandStatusResponse {
  return {
    ok: true,
    command: {
      requestId: "req_poll123",
      deviceId: "rokid_glasses_01",
      action: "display_text",
      status,
      createdAt: 1_710_000_000_000,
      updatedAt: 1_710_000_000_100,
      acknowledgedAt: 1_710_000_000_050,
      completedAt: status === "COMPLETED" ? 1_710_000_000_200 : null,
      cancelledAt: status === "CANCELLED" ? 1_710_000_000_200 : null,
      result:
        status === "COMPLETED"
          ? {
              action: "display_text",
              displayed: true,
              durationMs: 1_500,
            }
          : null,
      error: null,
      image: null,
      ...overrides,
    },
    timestamp: 1_710_000_000_100,
  };
}
