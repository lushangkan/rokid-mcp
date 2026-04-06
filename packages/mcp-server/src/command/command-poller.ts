import type { CommandRecord, CommandStatus } from "@rokid-mcp/protocol";

import { RelayRequestError } from "../lib/errors.js";
import type { RelayCommandClient } from "../relay/relay-command-client.js";

const TERMINAL_STATUSES = new Set<CommandStatus>(["COMPLETED", "FAILED", "TIMEOUT", "CANCELLED"]);

export type CommandPoller = {
  pollUntilTerminal(requestId: string): Promise<CommandRecord>;
};

export type CommandPollerOptions = {
  relayCommandClient: RelayCommandClient;
  timeoutMs: number;
  pollIntervalMs: number;
  now?: () => number;
  wait?: (delayMs: number) => Promise<void>;
};

export function createCommandPoller(options: CommandPollerOptions): CommandPoller {
  const now = options.now ?? Date.now;
  const wait = options.wait ?? defaultWait;

  return {
    async pollUntilTerminal(requestId) {
      const startedAt = now();
      let attempts = 0;
      let lastStatus: CommandStatus | undefined;

      while (true) {
        attempts += 1;
        const statusResponse = await options.relayCommandClient.getCommandStatus(requestId);
        const command = statusResponse.command;
        lastStatus = command.status;

        if (isTerminalStatus(command.status)) {
          return command;
        }

        const remainingMs = startedAt + options.timeoutMs - now();
        if (remainingMs <= 0) {
          throw new RelayRequestError(
            "MCP_COMMAND_POLL_TIMEOUT",
            "MCP-side polling timed out before the relay command reached a terminal state",
            true,
            {
              requestId,
              timeoutMs: options.timeoutMs,
              attempts,
              lastStatus,
            },
          );
        }

        await wait(Math.min(options.pollIntervalMs, remainingMs));
      }
    },
  };
}

function isTerminalStatus(status: CommandStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

function defaultWait(delayMs: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, delayMs);
  });
}
