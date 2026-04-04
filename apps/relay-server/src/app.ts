import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";
import { Elysia } from "elysia";

import { readRelayEnv, type RelayEnv } from "./config/env.js";
import { DeviceSessionManager } from "./modules/device/device-session-manager.js";
import { createHttpDevicesRoutes } from "./routes/http-devices.js";
import { createDeviceWsRoutes } from "./routes/ws-device.js";

export type CreateAppOptions = {
  env: RelayEnv;
  manager: DeviceSessionManager;
};

export function createApp(options: CreateAppOptions) {
  const { env, manager } = options;

  return new Elysia()
    .state("manager", manager)
    .use(createHttpDevicesRoutes({ manager }))
    .use(
      createDeviceWsRoutes({
        manager,
        heartbeatIntervalMs: env.heartbeatIntervalMs,
        heartbeatTimeoutMs: env.heartbeatTimeoutMs,
      }),
    )
    .get("/health", () => ({
      ok: true,
      service: "relay-server",
      protocol: PROTOCOL_NAME,
      version: PROTOCOL_VERSION,
    }));
}

export function createDefaultApp(env: RelayEnv = readRelayEnv()) {
  const manager = new DeviceSessionManager({
    heartbeatTimeoutMs: env.heartbeatTimeoutMs,
    cleanupIntervalMs: env.heartbeatIntervalMs,
  });

  manager.startCleanupJob();

  return createApp({ env, manager });
}
