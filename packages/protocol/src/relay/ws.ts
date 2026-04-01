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
    authToken: Type.String({ minLength: 1 }),
    appVersion: Type.String({ minLength: 1 }),
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
    seq: Type.Integer({ minimum: 0 }),
    runtimeState: RuntimeStateSchema,
    uplinkState: UplinkStateSchema,
    pendingCommandCount: Type.Integer({ minimum: 0 }),
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

export const RelayPhoneStateUpdatePayloadSchema = Type.Object(
  {
    setupState: SetupStateSchema,
    runtimeState: RuntimeStateSchema,
    uplinkState: UplinkStateSchema,
    lastErrorCode: Type.Optional(NullableStringSchema),
    lastErrorMessage: Type.Optional(NullableStringSchema),
    activeCommandRequestId: Type.Null(),
  },
  { additionalProperties: false },
);

export const RelayPhoneStateUpdateMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("phone_state_update"),
    deviceId: DeviceIdSchema,
    sessionId: SessionIdSchema,
    timestamp: TimestampSchema,
    payload: RelayPhoneStateUpdatePayloadSchema,
  },
  { additionalProperties: false },
);

export const RelayHelloAckPayloadSchema = Type.Object(
  {
    sessionId: SessionIdSchema,
    serverTime: TimestampSchema,
    heartbeatIntervalMs: Type.Integer({ minimum: 1 }),
    heartbeatTimeoutMs: Type.Integer({ minimum: 1 }),
    sessionTtlMs: Type.Optional(Type.Integer({ minimum: 1 })),
    limits: Type.Object(
      {
        maxPendingCommands: Type.Integer({ minimum: 1 }),
        maxImageUploadSizeBytes: Type.Integer({ minimum: 1 }),
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
  RelayPhoneStateUpdateMessageSchema,
]);

export type RelayHelloPayload = Static<typeof RelayHelloPayloadSchema>;
export type RelayHelloMessage = Static<typeof RelayHelloMessageSchema>;
export type RelayHeartbeatPayload = Static<typeof RelayHeartbeatPayloadSchema>;
export type RelayHeartbeatMessage = Static<typeof RelayHeartbeatMessageSchema>;
export type RelayPhoneStateUpdatePayload = Static<typeof RelayPhoneStateUpdatePayloadSchema>;
export type RelayPhoneStateUpdateMessage = Static<typeof RelayPhoneStateUpdateMessageSchema>;
export type RelayHelloAckPayload = Static<typeof RelayHelloAckPayloadSchema>;
export type RelayHelloAckMessage = Static<typeof RelayHelloAckMessageSchema>;
export type RelayDeviceInboundMessage = Static<typeof RelayDeviceInboundMessageSchema>;
