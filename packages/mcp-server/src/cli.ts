import type { McpEnv } from "./config/env.js";

import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

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

type MainModuleMeta = {
  url: string;
  main?: boolean;
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

function normalizeModulePath(path: string): string {
  const resolvedPath = resolve(path);
  return process.platform === "win32" ? resolvedPath.toLowerCase() : resolvedPath;
}

export function isMainModule(meta: MainModuleMeta, argv: readonly string[] = process.argv): boolean {
  if (meta.main !== undefined) {
    return meta.main;
  }

  const entryPath = argv[1];
  if (!entryPath) {
    return false;
  }

  return normalizeModulePath(fileURLToPath(meta.url)) === normalizeModulePath(entryPath);
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

if (isMainModule(import.meta)) {
  await runStdioServerCli();
}
