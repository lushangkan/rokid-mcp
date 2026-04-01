export type McpEnv = {
  relayBaseUrl: string;
  requestTimeoutMs: number;
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
    requestTimeoutMs: readFiniteNumber(env.RELAY_REQUEST_TIMEOUT_MS, 5000, "RELAY_REQUEST_TIMEOUT_MS"),
  };
}
