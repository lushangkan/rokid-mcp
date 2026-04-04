import { Elysia } from "elysia";

import type { DeviceSessionManager } from "../modules/device/device-session-manager.js";

type HttpDevicesRoutesOptions = {
  manager: DeviceSessionManager;
};

export function createHttpDevicesRoutes(options: HttpDevicesRoutesOptions) {
  return new Elysia({ name: "http-devices-routes" }).get("/api/v1/devices/:deviceId/status", ({ params }) =>
    options.manager.getCurrentDeviceStatus((params as { deviceId: string }).deviceId),
  );
}
