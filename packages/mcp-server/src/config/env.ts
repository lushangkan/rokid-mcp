import { Value } from "@sinclair/typebox/value";
import { DeviceIdSchema, type DeviceId } from "@rokid-mcp/protocol";

export type McpEnv = {
  relayBaseUrl: string;
  requestTimeoutMs: number;
  defaultDeviceId: DeviceId;
  commandPollIntervalMs: number;
  commandTimeoutMs: number;
};

function readRequiredString(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }

  return value;
}

function readPositiveInteger(value: string | undefined, fallback: number, name: string): number {
  const parsed = Number(value ?? fallback);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid numeric environment variable: ${name}`);
  }

  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`Environment variable ${name} must be a positive integer`);
  }

  return parsed;
}

function readPositiveIntegerFromEnv(env: NodeJS.ProcessEnv, names: string[], fallback: number): number {
  for (const name of names) {
    if (env[name] !== undefined) {
      return readPositiveInteger(env[name], fallback, name);
    }
  }

  return fallback;
}

function readRequiredDeviceId(env: NodeJS.ProcessEnv, name: string): DeviceId {
  const value = readRequiredString(env, name);

  if (!Value.Check(DeviceIdSchema, value)) {
    throw new Error(`Invalid device id environment variable: ${name}`);
  }

  return value;
}

function normalizeBaseUrl(value: string): string {
  try {
    const url = new URL(value);
    return url.toString().replace(/\/$/, "");
  } catch {
    throw new Error("Invalid URL environment variable: RELAY_BASE_URL");
  }
}

export function readMcpEnv(env: NodeJS.ProcessEnv = process.env): McpEnv {
  return {
    relayBaseUrl: normalizeBaseUrl(readRequiredString(env, "RELAY_BASE_URL")),
    requestTimeoutMs: readPositiveIntegerFromEnv(env, ["MCP_REQUEST_TIMEOUT_MS", "RELAY_REQUEST_TIMEOUT_MS"], 5000),
    defaultDeviceId: readRequiredDeviceId(env, "ROKID_DEFAULT_DEVICE_ID"),
    commandPollIntervalMs: readPositiveInteger(env.MCP_COMMAND_POLL_INTERVAL_MS, 1000, "MCP_COMMAND_POLL_INTERVAL_MS"),
    commandTimeoutMs: readPositiveInteger(env.MCP_COMMAND_TIMEOUT_MS, 90000, "MCP_COMMAND_TIMEOUT_MS"),
  };
}
