import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";
import { Elysia } from "elysia";

import { readRelayEnv, type RelayEnv } from "./config/env.ts";
import { CommandService } from "./modules/command/command-service.ts";
import { DeviceSessionManager } from "./modules/device/device-session-manager.ts";
import { ImageService } from "./modules/image/image-service.ts";
import { createHttpCommandsRoutes } from "./routes/http-commands.ts";
import { createHttpDevicesRoutes } from "./routes/http-devices.ts";
import { createHttpImagesRoutes } from "./routes/http-images.ts";
import { createDeviceWsRoutes } from "./routes/ws-device.ts";

export type CreateAppOptions = {
  env: RelayEnv;
  manager: DeviceSessionManager;
  imageService?: ImageService;
  commandService?: CommandService;
};

export function createApp(options: CreateAppOptions) {
  const { env, manager } = options;
  const imageService = options.imageService ?? new ImageService();
  const commandService =
    options.commandService ??
    new CommandService({
      imageReservations: imageService,
      imageStates: imageService,
    });

  return new Elysia()
    .state("manager", manager)
    .state("imageService", imageService)
    .state("commandService", commandService)
    .use(createHttpDevicesRoutes({ manager }))
    .use(createHttpCommandsRoutes({ manager, commandService }))
    .use(createHttpImagesRoutes({ imageService }))
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
  const imageService = new ImageService();
  const commandService = new CommandService({
    imageReservations: imageService,
    imageStates: imageService,
  });

  manager.startCleanupJob();
  imageService.startCleanupJob();

  return createApp({ env, manager, imageService, commandService });
}
