export type RelayEnv = {
  port: number;
  host: string;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
};

export function readRelayEnv(env: NodeJS.ProcessEnv = process.env): RelayEnv {
  return {
    port: Number(env.PORT ?? 3000),
    host: env.HOST ?? "0.0.0.0",
    heartbeatIntervalMs: Number(env.RELAY_HEARTBEAT_INTERVAL_MS ?? 5000),
    heartbeatTimeoutMs: Number(env.RELAY_HEARTBEAT_TIMEOUT_MS ?? 15000)
  };
}
