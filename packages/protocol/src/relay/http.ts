import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import {
  CapabilitiesSchema,
  DeviceIdSchema,
  DeviceSessionStateSchema,
  NullableStringSchema,
  RuntimeStateSchema,
  SessionIdSchema,
  SetupStateSchema,
  TimestampSchema,
  UplinkStateSchema,
} from "../common/index.js";

export const GetDeviceStatusParamsSchema = Type.Object(
  {
    deviceId: DeviceIdSchema,
  },
  { additionalProperties: false },
);

export const DeviceStatusSnapshotSchema = Type.Object(
  {
    deviceId: DeviceIdSchema,
    connected: Type.Boolean(),
    sessionState: DeviceSessionStateSchema,
    setupState: SetupStateSchema,
    runtimeState: RuntimeStateSchema,
    uplinkState: UplinkStateSchema,
    capabilities: CapabilitiesSchema,
    activeCommandRequestId: NullableStringSchema,
    lastErrorCode: NullableStringSchema,
    lastErrorMessage: NullableStringSchema,
    lastSeenAt: Type.Union([TimestampSchema, Type.Null()]),
    sessionId: Type.Union([SessionIdSchema, Type.Null()]),
  },
  { additionalProperties: false },
);

export const GetDeviceStatusResponseSchema = Type.Object(
  {
    ok: Type.Literal(true),
    device: DeviceStatusSnapshotSchema,
    timestamp: TimestampSchema,
  },
  { additionalProperties: false },
);

export type GetDeviceStatusParams = Static<typeof GetDeviceStatusParamsSchema>;
export type DeviceStatusSnapshot = Static<typeof DeviceStatusSnapshotSchema>;
export type GetDeviceStatusResponse = Static<typeof GetDeviceStatusResponseSchema>;
