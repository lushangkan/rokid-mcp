import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

import { readMcpEnv, type McpEnv } from "./config/env.js";
import { createRelayClient, type RelayClient } from "./relay/relay-client.js";
import { createGetDeviceStatusTool, GET_DEVICE_STATUS_TOOL_NAME } from "./tools/get-device-status.js";

export type McpServerDependencies = {
  env?: McpEnv;
  relayClient?: RelayClient;
};

export function createMcpServer(deps: McpServerDependencies = {}): McpServer {
  const env = deps.env ?? readMcpEnv();
  const relayClient = deps.relayClient ?? createRelayClient(env);
  const getDeviceStatusTool = createGetDeviceStatusTool({ relayClient });

  const server = new McpServer({
    name: "rokid-mcp-server",
    version: "0.1.0",
  });

  server.registerTool(
    GET_DEVICE_STATUS_TOOL_NAME,
    {
      description: getDeviceStatusTool.description,
      inputSchema: {
        deviceId: z.string(),
      },
    },
    async ({ deviceId }) => getDeviceStatusTool.handler({ deviceId }),
  );

  return server;
}
