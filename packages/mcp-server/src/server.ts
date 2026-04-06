import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import { readMcpEnv, type McpEnv } from "./config/env.js";
import { createCommandPoller, type CommandPoller } from "./command/command-poller.js";
import { createImageDownloader } from "./image/image-downloader.js";
import { createDownloadedImageVerifier } from "./image/image-verifier.js";
import { createCapturePhotoResultMapper } from "./mapper/capture-photo-result-mapper.js";
import { createDisplayTextUseCase, type DisplayTextUseCase } from "./usecases/display-text-usecase.js";
import { createCapturePhotoUseCase, type CapturePhotoUseCase } from "./usecases/capture-photo-usecase.js";
import { createRelayClient, type RelayClient } from "./relay/relay-client.js";
import { createRelayCommandClient, type RelayCommandClient } from "./relay/relay-command-client.js";
import {
  createCapturePhotoTool,
  CAPTURE_PHOTO_TOOL_NAME,
  capturePhotoMcpInputSchema,
} from "./tools/capture-photo.js";
import {
  createDisplayTextTool,
  DISPLAY_TEXT_TOOL_NAME,
  displayTextMcpInputSchema,
} from "./tools/display-text.js";
import {
  createGetDeviceStatusTool,
  GET_DEVICE_STATUS_TOOL_NAME,
  getDeviceStatusMcpInputSchema,
} from "./tools/get-device-status.js";

export type McpServerDependencies = {
  env?: McpEnv;
  relayClient?: RelayClient;
  relayCommandClient?: RelayCommandClient;
  commandPoller?: CommandPoller;
  displayTextUseCase?: DisplayTextUseCase;
  capturePhotoUseCase?: CapturePhotoUseCase;
};

export const ACTIVE_MCP_TOOL_NAMES = [
  GET_DEVICE_STATUS_TOOL_NAME,
  DISPLAY_TEXT_TOOL_NAME,
  CAPTURE_PHOTO_TOOL_NAME,
] as const;

export function createMcpServer(deps: McpServerDependencies = {}): McpServer {
  const env = deps.env ?? readMcpEnv();
  const relayClient = deps.relayClient ?? createRelayClient(env);
  const relayCommandClient = deps.relayCommandClient ?? createRelayCommandClient(env);
  const commandPoller = deps.commandPoller ??
    createCommandPoller({
      relayCommandClient,
      timeoutMs: env.commandTimeoutMs,
      pollIntervalMs: env.commandPollIntervalMs,
    });
  const getDeviceStatusTool = createGetDeviceStatusTool({ relayClient });
  const displayTextUseCase = deps.displayTextUseCase ?? createDisplayTextUseCase({ relayCommandClient, commandPoller });
  const capturePhotoUseCase = deps.capturePhotoUseCase ?? createCapturePhotoUseCase({
    relayCommandClient,
    commandPoller,
    imageDownloader: createImageDownloader({ relayCommandClient }),
    downloadedImageVerifier: createDownloadedImageVerifier(),
    resultMapper: createCapturePhotoResultMapper(),
  });
  const displayTextTool = createDisplayTextTool({
    defaultDeviceId: env.defaultDeviceId,
    useCase: displayTextUseCase,
  });
  const capturePhotoTool = createCapturePhotoTool({
    defaultDeviceId: env.defaultDeviceId,
    useCase: capturePhotoUseCase,
  });

  const server = new McpServer({
    name: "rokid-mcp-server",
    version: "0.1.0",
  });

  server.registerTool(
    GET_DEVICE_STATUS_TOOL_NAME,
    {
      description: getDeviceStatusTool.description,
      inputSchema: getDeviceStatusMcpInputSchema,
    },
    async ({ deviceId }) => getDeviceStatusTool.handler({ deviceId }),
  );

  server.registerTool(
    DISPLAY_TEXT_TOOL_NAME,
    {
      description: displayTextTool.description,
      inputSchema: displayTextMcpInputSchema,
    },
    async ({ text, durationMs }) => displayTextTool.handler({ text, durationMs }),
  );

  server.registerTool(
    CAPTURE_PHOTO_TOOL_NAME,
    {
      description: capturePhotoTool.description,
      inputSchema: capturePhotoMcpInputSchema,
    },
    async ({ quality }) => capturePhotoTool.handler({ quality }),
  );

  return server;
}
