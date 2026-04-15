import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";

import { createMcpServer, type McpServerDependencies } from "./server.js";

type ConnectableMcpServer = Pick<ReturnType<typeof createMcpServer>, "connect">;

export type StdioServerRuntimeDependencies = McpServerDependencies & {
  createServer?: (deps: McpServerDependencies) => ConnectableMcpServer;
  createTransport?: () => Transport;
};

/**
 * Connects the Rokid MCP server to stdio so MCP hosts can keep the process alive
 * and exchange JSON-RPC messages over stdin/stdout.
 */
export async function startStdioServer(deps: StdioServerRuntimeDependencies = {}): Promise<void> {
  const {
    createServer = createMcpServer,
    createTransport = () => new StdioServerTransport(),
    ...serverDeps
  } = deps;

  const server = createServer(serverDeps);
  const transport = createTransport();

  await server.connect(transport);
}
