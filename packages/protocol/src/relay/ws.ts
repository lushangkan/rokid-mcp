import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import {
  CapabilitiesSchema,
  DeviceIdSchema,
  NullableStringSchema,
  PROTOCOL_VERSION,
  RuntimeStateSchema,
  SessionIdSchema,
  SetupStateSchema,
  TimestampSchema,
  UplinkStateSchema,
} from "../common/index.js";

export const RelayHelloPayloadSchema = Type.Object(
  {
    authToken: Type.String(),
    appVersion: Type.String(),
    phoneInfo: Type.Object({}, { additionalProperties: true }),
    setupState: SetupStateSchema,
    runtimeState: RuntimeStateSchema,
    uplinkState: UplinkStateSchema,
    capabilities: CapabilitiesSchema,
  },
  { additionalProperties: false },
);

export const RelayHelloMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("hello"),
    deviceId: DeviceIdSchema,
    timestamp: TimestampSchema,
    payload: RelayHelloPayloadSchema,
  },
  { additionalProperties: false },
);

export const RelayHeartbeatPayloadSchema = Type.Object(
  {
    seq: Type.Number({ minimum: 0 }),
    runtimeState: RuntimeStateSchema,
    uplinkState: UplinkStateSchema,
    pendingCommandCount: Type.Number({ minimum: 0 }),
    activeCommandRequestId: NullableStringSchema,
  },
  { additionalProperties: false },
);

export const RelayHeartbeatMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("heartbeat"),
    deviceId: DeviceIdSchema,
    sessionId: SessionIdSchema,
    timestamp: TimestampSchema,
    payload: RelayHeartbeatPayloadSchema,
  },
  { additionalProperties: false },
);

export const RelayHelloAckPayloadSchema = Type.Object(
  {
    sessionId: SessionIdSchema,
    serverTime: TimestampSchema,
    heartbeatIntervalMs: Type.Number({ minimum: 1 }),
    heartbeatTimeoutMs: Type.Number({ minimum: 1 }),
    limits: Type.Object(
      {
        maxPendingCommands: Type.Number({ minimum: 1 }),
        maxImageUploadSizeBytes: Type.Number({ minimum: 1 }),
        acceptedImageContentTypes: Type.Array(Type.String(), { minItems: 1 }),
      },
      { additionalProperties: false },
    ),
  },
  { additionalProperties: false },
);

export const RelayHelloAckMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("hello_ack"),
    deviceId: DeviceIdSchema,
    timestamp: TimestampSchema,
    payload: RelayHelloAckPayloadSchema,
  },
  { additionalProperties: false },
);

export const RelayDeviceInboundMessageSchema = Type.Union([
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
]);

export type RelayHelloPayload = Static<typeof RelayHelloPayloadSchema>;
export type RelayHelloMessage = Static<typeof RelayHelloMessageSchema>;
export type RelayHeartbeatPayload = Static<typeof RelayHeartbeatPayloadSchema>;
export type RelayHeartbeatMessage = Static<typeof RelayHeartbeatMessageSchema>;
export type RelayHelloAckPayload = Static<typeof RelayHelloAckPayloadSchema>;
export type RelayHelloAckMessage = Static<typeof RelayHelloAckMessageSchema>;
export type RelayDeviceInboundMessage = Static<typeof RelayDeviceInboundMessageSchema>;
