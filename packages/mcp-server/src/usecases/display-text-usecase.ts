import type { DeviceId } from "@rokid-mcp/protocol";

import type { CommandPoller } from "../command/command-poller.js";
import { mapDisplayTextResult, type McpToolResult } from "../mapper/mcp-result-mapper.js";
import type { RelayCommandClient } from "../relay/relay-command-client.js";

export type DisplayTextUseCaseInput = {
  deviceId: DeviceId;
  text: string;
  durationMs: number;
};

export type DisplayTextUseCase = {
  execute(input: DisplayTextUseCaseInput): Promise<McpToolResult>;
};

export type DisplayTextUseCaseDependencies = {
  relayCommandClient: RelayCommandClient;
  commandPoller: CommandPoller;
};

export function createDisplayTextUseCase(deps: DisplayTextUseCaseDependencies): DisplayTextUseCase {
  return {
    async execute(input) {
      const submission = await deps.relayCommandClient.submitCommand({
        deviceId: input.deviceId,
        action: "display_text",
        payload: {
          text: input.text,
          durationMs: input.durationMs,
        },
      });

      const command = await deps.commandPoller.pollUntilTerminal(submission.requestId);
      return mapDisplayTextResult(command);
    },
  };
}
