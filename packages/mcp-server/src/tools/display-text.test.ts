import { describe, expect, mock, test } from "bun:test";
import type { CommandRecord, SubmitCommandResponse } from "@rokid-mcp/protocol";

import { RelayRequestError } from "../lib/errors.js";
import { createDisplayTextTool, DISPLAY_TEXT_TOOL_NAME } from "./display-text.js";
import { createDisplayTextUseCase } from "../usecases/display-text-usecase.js";

const DEFAULT_DEVICE_ID = "rokid_glasses_01";

describe("display text tool", () => {
  test("display text tool submits to relay, polls to completion, and returns structured success", async () => {
    const submitCommand = mock(async () => createSubmitResponse());
    const pollUntilTerminal = mock(async (requestId: string) =>
      createCommandRecord("COMPLETED", {
        requestId,
        result: {
          action: "display_text",
          displayed: true,
          durationMs: 1500,
        },
        completedAt: 1_710_000_000_300,
      }),
    );

    const tool = createTool({ submitCommand, pollUntilTerminal });
    const result = await tool.handler({ text: "  Hello from MCP  ", durationMs: 1500 });

    expect(tool.name).toBe(DISPLAY_TEXT_TOOL_NAME);
    expect(submitCommand).toHaveBeenCalledWith({
      deviceId: DEFAULT_DEVICE_ID,
      action: "display_text",
      payload: {
        text: "Hello from MCP",
        durationMs: 1500,
      },
    });
    expect(pollUntilTerminal).toHaveBeenCalledWith("req_display123");
    expect(result.isError).toBeUndefined();
    expect(result.content).toEqual([
      {
        type: "text",
        text: "Displayed the requested text for 1500 ms.",
      },
    ]);
    expect(result.structuredContent).toEqual({
      action: "display_text",
      status: "COMPLETED",
      outcome: {
        displayed: true,
        durationMs: 1500,
      },
    });
  });

  test("display text tool returns mapped terminal failures without success output", async () => {
    const tool = createTool({
      submitCommand: async () => createSubmitResponse(),
      pollUntilTerminal: async () =>
        createCommandRecord("FAILED", {
          error: {
            code: "BLUETOOTH_UNAVAILABLE",
            message: "Bluetooth is unavailable on the phone.",
            retryable: true,
          },
        }),
    });

    const result = await tool.handler({ text: "Hello", durationMs: 1500 });

    expect(result.isError).toBe(true);
    expect(result.content[0]).toEqual({
      type: "text",
      text: "Display text command failed: Bluetooth is unavailable on the phone.",
    });
    expect(result.error).toMatchObject({
      code: "BLUETOOTH_UNAVAILABLE",
      retryable: true,
    });
  });

  test("display text tool returns relay polling errors as MCP errors", async () => {
    const tool = createTool({
      submitCommand: async () => createSubmitResponse(),
      pollUntilTerminal: async () => {
        throw new RelayRequestError(
          "MCP_COMMAND_POLL_TIMEOUT",
          "MCP-side polling timed out before the relay command reached a terminal state",
          true,
          {
            requestId: "req_display123",
            lastStatus: "RUNNING",
          },
        );
      },
    });

    const result = await tool.handler({ text: "Hello", durationMs: 1500 });

    expect(result.isError).toBe(true);
    expect(result.error).toEqual({
      code: "MCP_COMMAND_POLL_TIMEOUT",
      message: "MCP-side polling timed out before the relay command reached a terminal state",
      retryable: true,
      details: {
        requestId: "req_display123",
        lastStatus: "RUNNING",
      },
    });
  });

  test("display text tool rejects invalid input before submitting to relay", async () => {
    const submitCommand = mock(async () => createSubmitResponse());
    const tool = createTool({
      submitCommand,
      pollUntilTerminal: async () => createCommandRecord("COMPLETED"),
    });

    const result = await tool.handler({ text: "   ", durationMs: 0 });

    expect(result.isError).toBe(true);
    expect(result.error?.code).toBe("MCP_INVALID_PARAMS");
    expect(submitCommand).not.toHaveBeenCalled();
  });
});

function createTool(overrides: {
  submitCommand: (request: { deviceId: string; action: "display_text"; payload: { text: string; durationMs: number } }) => Promise<SubmitCommandResponse>;
  pollUntilTerminal: (requestId: string) => Promise<CommandRecord>;
}) {
  const useCase = createDisplayTextUseCase({
    relayCommandClient: {
      submitCommand: overrides.submitCommand,
      getCommandStatus: async () => {
        throw new Error("not used");
      },
      downloadImage: async () => {
        throw new Error("not used");
      },
    },
    commandPoller: {
      pollUntilTerminal: overrides.pollUntilTerminal,
    },
  });

  return createDisplayTextTool({
    defaultDeviceId: DEFAULT_DEVICE_ID,
    useCase,
  });
}

function createSubmitResponse(): SubmitCommandResponse {
  return {
    ok: true,
    requestId: "req_display123",
    deviceId: DEFAULT_DEVICE_ID,
    action: "display_text",
    status: "CREATED",
    createdAt: 1_710_000_000_000,
    statusUrl: "https://relay.example.com/api/v1/commands/req_display123",
  };
}

function createCommandRecord(
  status: CommandRecord["status"],
  overrides: Partial<CommandRecord> = {},
): CommandRecord {
  return {
    requestId: "req_display123",
    deviceId: DEFAULT_DEVICE_ID,
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
            durationMs: 1500,
          }
        : null,
    error: null,
    image: null,
    ...overrides,
  };
}
