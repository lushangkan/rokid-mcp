import { spawn } from "node:child_process";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { describe, expect, test } from "bun:test";

import { runStdioServerCli } from "./cli.js";

type SpawnResult = {
  exitCode: number | null;
  stderr: string;
  stdout: string;
};

function runBunProcess(args: string[], env: NodeJS.ProcessEnv = process.env): Promise<SpawnResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, {
      cwd: join(import.meta.dir, ".."),
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });

    let stdout = "";
    let stderr = "";

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });

    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.on("error", reject);
    child.on("close", (exitCode) => {
      resolve({ exitCode, stderr, stdout });
    });
  });
}

describe("runStdioServerCli", () => {
  test("delegates validated env to the reusable stdio runtime", async () => {
    const env = {
      relayBaseUrl: "https://relay.example.com",
      requestTimeoutMs: 5_000,
      defaultDeviceId: "rokid_glasses_01",
      commandPollIntervalMs: 250,
      commandTimeoutMs: 30_000,
    } as const;
    const receivedCalls: Array<{ env: typeof env }> = [];
    const loggedErrors: string[] = [];
    const exitCodes: number[] = [];

    await runStdioServerCli({
      readEnv: () => env,
      startServer: async (deps) => {
        receivedCalls.push(deps as { env: typeof env });
      },
      logger: {
        error(message, error) {
          loggedErrors.push(`${message}:${String(error)}`);
        },
      },
      exit: (code) => {
        exitCodes.push(code);
      },
    });

    expect(receivedCalls).toEqual([{ env }]);
    expect(loggedErrors).toEqual([]);
    expect(exitCodes).toEqual([]);
  });

  test("importing stdio.ts does not trigger startup side effects", async () => {
    const stdioModuleUrl = pathToFileURL(join(import.meta.dir, "stdio.ts")).href;
    const env = { ...process.env };

    delete env.RELAY_BASE_URL;
    delete env.ROKID_DEFAULT_DEVICE_ID;

    const result = await runBunProcess([
      "--eval",
      `await import(${JSON.stringify(stdioModuleUrl)});`,
    ], env);

    expect(result.exitCode).toBe(0);
    expect(result.stderr).toBe("");
    expect(result.stdout).toBe("");
  });

  test("direct CLI startup exits non-zero and writes to stderr when env is missing", async () => {
    const cliEntryPath = join(import.meta.dir, "cli.ts");
    const env = { ...process.env };

    delete env.RELAY_BASE_URL;
    delete env.ROKID_DEFAULT_DEVICE_ID;

    const result = await runBunProcess([cliEntryPath], env);

    expect(result.exitCode).toBe(1);
    expect(result.stdout).toBe("");
    expect(result.stderr).toContain("Failed to start MCP stdio server");
    expect(result.stderr).toContain("Missing required environment variable: RELAY_BASE_URL");
  });
});
