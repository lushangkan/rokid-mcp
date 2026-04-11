import type { McpEnv } from "./config/env.js";

import { readMcpEnv } from "./config/env.js";
import { startStdioServer } from "./stdio.js";

type StartupLogger = {
  error: (message: string, error?: unknown) => void;
};

export type StdioCliDependencies = {
  readEnv?: () => McpEnv;
  startServer?: typeof startStdioServer;
  logger?: StartupLogger;
  exit?: (code: number) => void;
};

function formatStartupError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }

  return String(error);
}

function createStderrLogger(stderr: Pick<NodeJS.WriteStream, "write"> = process.stderr): StartupLogger {
  return {
    error(message, error) {
      const details = error === undefined ? "" : `: ${formatStartupError(error)}`;
      stderr.write(`${message}${details}\n`);
    },
  };
}

export async function runStdioServerCli(deps: StdioCliDependencies = {}): Promise<void> {
  const {
    readEnv = readMcpEnv,
    startServer = startStdioServer,
    logger = createStderrLogger(),
    exit = (code) => {
      process.exit(code);
    },
  } = deps;

  try {
    const env = readEnv();
    await startServer({ env });
  } catch (error) {
    logger.error("Failed to start MCP stdio server", error);
    exit(1);
  }
}

if (import.meta.main) {
  await runStdioServerCli();
}
