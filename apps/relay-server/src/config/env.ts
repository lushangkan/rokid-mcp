export type RelayEnv = {
  port: number;
  host: string;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
};

function readFiniteNumber(value: string | undefined, fallback: number, name: string): number {
  const parsed = Number(value ?? fallback);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid numeric environment variable: ${name}`);
  }

  return parsed;
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
    )
  };
}
