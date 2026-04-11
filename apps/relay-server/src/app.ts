import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";
import { Elysia } from "elysia";

import { readRelayEnv, type RelayEnv } from "./config/env.ts";
import { createRelayHttpAuthMiddleware } from "./lib/auth-middleware.ts";
import { CommandService } from "./modules/command/command-service.ts";
import { DeviceSessionManager } from "./modules/device/device-session-manager.ts";
import { ImageService } from "./modules/image/image-service.ts";
import { createHttpCommandsRoutes } from "./routes/http-commands.ts";
import { createHttpDevicesRoutes } from "./routes/http-devices.ts";
import { createHttpImagesRoutes } from "./routes/http-images.ts";
import { createDeviceWsController } from "./routes/ws-device.ts";

export type CreateAppOptions = {
  env: RelayEnv;
  manager: DeviceSessionManager;
  imageService?: ImageService;
  commandService?: CommandService;
};

export const ACTIVE_RELAY_ROUTE_HISTORY = [
  { method: "GET", path: "/api/v1/devices/:deviceId/status" },
  { method: "POST", path: "/api/v1/commands" },
  { method: "GET", path: "/api/v1/commands/:requestId" },
  { method: "PUT", path: "/api/v1/images/:imageId" },
  { method: "GET", path: "/api/v1/images/:imageId" },
  { method: "WS", path: "/ws/device" },
  { method: "GET", path: "/health" },
] as const;

export function createApp(options: CreateAppOptions) {
  const { env, manager } = options;
  const imageService = options.imageService ?? new ImageService();
  const commandService =
    options.commandService ??
    new CommandService({
      imageReservations: imageService,
      imageStates: imageService,
    });
  const deviceWs = createDeviceWsController({
    manager,
    commandService,
    heartbeatIntervalMs: env.heartbeatIntervalMs,
    heartbeatTimeoutMs: env.heartbeatTimeoutMs,
    helloTimeoutMs: env.helloTimeoutMs,
    wsAuthTokens: env.wsAuthTokens,
  });

  return new Elysia()
    .state("manager", manager)
    .state("imageService", imageService)
    .state("commandService", commandService)
    .use(createRelayHttpAuthMiddleware({ httpAuthTokens: env.httpAuthTokens }))
    .use(createHttpDevicesRoutes({ manager }))
    .use(
      createHttpCommandsRoutes({
        manager,
        commandService,
        dispatchPendingCommand: deviceWs.dispatchPendingCommand,
      }),
    )
    .use(createHttpImagesRoutes({ imageService }))
    .use(deviceWs.app)
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
  const imageService = new ImageService();
  const commandService = new CommandService({
    imageReservations: imageService,
    imageStates: imageService,
  });

  manager.startCleanupJob();
  imageService.startCleanupJob();

  return createApp({ env, manager, imageService, commandService });
}
