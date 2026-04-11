import { parseTokenAllowlist } from "../lib/auth.ts";

export type RelayEnv = {
  port: number;
  host: string;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
  httpAuthTokens: string[];
  wsAuthTokens: string[];
  helloTimeoutMs: number;
};

function readRequiredString(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }

  return value;
}

function readFiniteNumber(value: string | undefined, fallback: number, name: string): number {
  const parsed = Number(value ?? fallback);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid numeric environment variable: ${name}`);
  }

  return parsed;
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

function readRequiredTokenAllowlist(env: NodeJS.ProcessEnv, name: string): string[] {
  const value = readRequiredString(env, name);

  try {
    return parseTokenAllowlist(value);
  } catch (error) {
    const message = error instanceof Error ? error.message : "invalid token allowlist";
    throw new Error(`Invalid environment variable ${name}: ${message}`);
  }
}

export function readRelayEnv(env: NodeJS.ProcessEnv = process.env): RelayEnv {
  return {
    port: readFiniteNumber(env.PORT, 3000, "PORT"),
    host: env.HOST ?? "0.0.0.0",
    heartbeatIntervalMs: readFiniteNumber(
      env.RELAY_HEARTBEAT_INTERVAL_MS,
      5000,
      "RELAY_HEARTBEAT_INTERVAL_MS"
    ),
    heartbeatTimeoutMs: readFiniteNumber(
      env.RELAY_HEARTBEAT_TIMEOUT_MS,
      15000,
      "RELAY_HEARTBEAT_TIMEOUT_MS"
    ),
    httpAuthTokens: readRequiredTokenAllowlist(env, "RELAY_HTTP_AUTH_TOKENS"),
    wsAuthTokens: readRequiredTokenAllowlist(env, "RELAY_WS_AUTH_TOKENS"),
    helloTimeoutMs: readPositiveInteger(env.RELAY_WS_HELLO_TIMEOUT_MS, 5000, "RELAY_WS_HELLO_TIMEOUT_MS"),
  };
}
