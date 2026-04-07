import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import {
  ACCEPTED_IMAGE_CONTENT_TYPES,
  CapabilitySchema,
  DeviceIdSchema,
  DEFAULT_IMAGE_CONTENT_TYPE,
  GLOBAL_ACTIVE_COMMAND_LIMIT,
  ImageIdSchema,
  NullableStringSchema,
  PROTOCOL_VERSION,
  RequestIdSchema,
  RuntimeStateSchema,
  Sha256Schema,
  SessionIdSchema,
  SetupStateSchema,
  TerminalErrorCodeSchema,
  TimestampSchema,
  TerminalErrorSchema,
  TransferIdSchema,
  UploadTokenSchema,
} from "../common/index.js";
import {
  CapturePhotoCommandPayloadSchema,
  CommandActionSchema,
  CommandResultSchema,
  DisplayTextCommandPayloadSchema,
} from "./http.js";

export const RelayHelloCapabilitiesSchema = Type.Array(CapabilitySchema, {
  minItems: 1,
  uniqueItems: true,
});

export const PhoneInfoSchema = Type.Object(
  {
    brand: Type.Optional(Type.String({ minLength: 1 })),
    model: Type.Optional(Type.String({ minLength: 1 })),
    androidVersion: Type.Optional(Type.String({ minLength: 1 })),
    sdkInt: Type.Optional(Type.Integer({ minimum: 1 })),
  },
  { additionalProperties: false },
);

export const TargetGlassesSchema = Type.Object(
  {
    bluetoothName: Type.Optional(Type.String({ minLength: 1 })),
    bluetoothAddress: Type.Optional(Type.String({ minLength: 1 })),
  },
  { additionalProperties: false },
);

export const RelayConfigSchema = Type.Object(
  {
    baseUrl: Type.Optional(Type.String({ minLength: 1 })),
  },
  { additionalProperties: false },
);

export const RelayHelloPayloadSchema = Type.Object(
  {
    authToken: Type.String({ minLength: 1 }),
    appVersion: Type.String({ minLength: 1 }),
    appBuild: Type.Optional(Type.String({ minLength: 1 })),
    phoneInfo: PhoneInfoSchema,
    setupState: SetupStateSchema,
    runtimeState: RuntimeStateSchema,
    capabilities: RelayHelloCapabilitiesSchema,
    targetGlasses: Type.Optional(TargetGlassesSchema),
    relayConfig: Type.Optional(RelayConfigSchema),
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
    pendingCommandCount: Type.Integer({ minimum: 0, maximum: GLOBAL_ACTIVE_COMMAND_LIMIT }),
    activeCommandRequestId: Type.Union([RequestIdSchema, Type.Null()]),
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
    lastErrorCode: Type.Optional(NullableStringSchema),
    lastErrorMessage: Type.Optional(NullableStringSchema),
    activeCommandRequestId: Type.Optional(Type.Union([RequestIdSchema, Type.Null()])),
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
        maxPendingCommands: Type.Literal(GLOBAL_ACTIVE_COMMAND_LIMIT),
        maxImageUploadSizeBytes: Type.Integer({ minimum: 1 }),
        acceptedImageContentTypes: Type.Array(Type.Literal(DEFAULT_IMAGE_CONTENT_TYPE), {
          minItems: ACCEPTED_IMAGE_CONTENT_TYPES.length,
          maxItems: ACCEPTED_IMAGE_CONTENT_TYPES.length,
        }),
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

export const CommandDispatchImageSchema = Type.Object(
  {
    imageId: ImageIdSchema,
    transferId: TransferIdSchema,
    uploadToken: UploadTokenSchema,
    contentType: Type.Literal(DEFAULT_IMAGE_CONTENT_TYPE),
    expiresAt: TimestampSchema,
    maxSizeBytes: Type.Integer({ minimum: 1 }),
  },
  { additionalProperties: false },
);

export const DisplayTextCommandDispatchPayloadSchema = Type.Object(
  {
    action: Type.Literal("display_text"),
    timeoutMs: Type.Integer({ minimum: 1 }),
    params: DisplayTextCommandPayloadSchema,
  },
  { additionalProperties: false },
);

export const CapturePhotoCommandDispatchPayloadSchema = Type.Object(
  {
    action: Type.Literal("capture_photo"),
    timeoutMs: Type.Integer({ minimum: 1 }),
    params: CapturePhotoCommandPayloadSchema,
    image: CommandDispatchImageSchema,
  },
  { additionalProperties: false },
);

export const CommandDispatchPayloadSchema = Type.Union([
  DisplayTextCommandDispatchPayloadSchema,
  CapturePhotoCommandDispatchPayloadSchema,
]);

export const CommandDispatchMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("command"),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sessionId: SessionIdSchema,
    timestamp: TimestampSchema,
    payload: CommandDispatchPayloadSchema,
  },
  { additionalProperties: false },
);

export const CommandCancelPayloadSchema = Type.Object(
  {
    action: CommandActionSchema,
    cancelledAt: TimestampSchema,
    reasonCode: Type.Optional(TerminalErrorCodeSchema),
    reasonMessage: Type.Optional(Type.String({ minLength: 1 })),
  },
  { additionalProperties: false },
);

export const CommandCancelMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("command_cancel"),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sessionId: SessionIdSchema,
    timestamp: TimestampSchema,
    payload: CommandCancelPayloadSchema,
  },
  { additionalProperties: false },
);

export const CommandAckPayloadSchema = Type.Object(
  {
    action: CommandActionSchema,
    acknowledgedAt: TimestampSchema,
    runtimeState: Type.Union([Type.Literal("READY"), Type.Literal("BUSY")]),
  },
  { additionalProperties: false },
);

export const CommandAckMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("command_ack"),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sessionId: Type.Optional(SessionIdSchema),
    timestamp: TimestampSchema,
    payload: CommandAckPayloadSchema,
  },
  { additionalProperties: false },
);

export const CommandExecutionStatusSchema = Type.Union([
  Type.Literal("forwarding_to_glasses"),
  Type.Literal("waiting_glasses_ack"),
  Type.Literal("executing"),
  Type.Literal("displaying"),
  Type.Literal("capturing"),
  Type.Literal("image_captured"),
  Type.Literal("uploading_image"),
  Type.Literal("image_uploaded"),
]);

export const CommandStatusImageProgressSchema = Type.Object(
  {
    imageId: ImageIdSchema,
    transferId: TransferIdSchema,
    uploadStartedAt: Type.Optional(TimestampSchema),
    uploadedAt: Type.Optional(TimestampSchema),
    sha256: Type.Optional(Sha256Schema),
  },
  { additionalProperties: false },
);

export const CommandStatusBasePayloadSchema = Type.Object(
  {
    action: CommandActionSchema,
    statusAt: TimestampSchema,
    detailCode: Type.Optional(Type.String({ minLength: 1 })),
    detailMessage: Type.Optional(Type.String({ minLength: 1 })),
  },
  { additionalProperties: false },
);

export const CommandStatusForwardingPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object({ status: Type.Literal("forwarding_to_glasses") }, { additionalProperties: false }),
]);

export const CommandStatusWaitingAckPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object({ status: Type.Literal("waiting_glasses_ack") }, { additionalProperties: false }),
]);

export const CommandStatusExecutingPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object({ status: Type.Literal("executing") }, { additionalProperties: false }),
]);

export const CommandStatusDisplayingPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object(
    { status: Type.Literal("displaying"), action: Type.Literal("display_text") },
    { additionalProperties: false },
  ),
]);

export const CommandStatusCapturingPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object(
    { status: Type.Literal("capturing"), action: Type.Literal("capture_photo") },
    { additionalProperties: false },
  ),
]);

export const CommandStatusImageCapturedPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object(
    {
      status: Type.Literal("image_captured"),
      action: Type.Literal("capture_photo"),
      image: CommandStatusImageProgressSchema,
    },
    { additionalProperties: false },
  ),
]);

export const CommandStatusUploadingImagePayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object(
    {
      status: Type.Literal("uploading_image"),
      action: Type.Literal("capture_photo"),
      image: CommandStatusImageProgressSchema,
    },
    { additionalProperties: false },
  ),
]);

export const CommandStatusImageUploadedPayloadSchema = Type.Composite([
  CommandStatusBasePayloadSchema,
  Type.Object(
    {
      status: Type.Literal("image_uploaded"),
      action: Type.Literal("capture_photo"),
      image: CommandStatusImageProgressSchema,
    },
    { additionalProperties: false },
  ),
]);

export const CommandStatusPayloadSchema = Type.Union([
  CommandStatusForwardingPayloadSchema,
  CommandStatusWaitingAckPayloadSchema,
  CommandStatusExecutingPayloadSchema,
  CommandStatusDisplayingPayloadSchema,
  CommandStatusCapturingPayloadSchema,
  CommandStatusImageCapturedPayloadSchema,
  CommandStatusUploadingImagePayloadSchema,
  CommandStatusImageUploadedPayloadSchema,
]);

export const CommandStatusMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("command_status"),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sessionId: Type.Optional(SessionIdSchema),
    timestamp: TimestampSchema,
    payload: CommandStatusPayloadSchema,
  },
  { additionalProperties: false },
);

export const CommandResultPayloadSchema = Type.Object(
  {
    completedAt: TimestampSchema,
    result: CommandResultSchema,
  },
  { additionalProperties: false },
);

export const CommandResultMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("command_result"),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sessionId: Type.Optional(SessionIdSchema),
    timestamp: TimestampSchema,
    payload: CommandResultPayloadSchema,
  },
  { additionalProperties: false },
);

export const CommandErrorPayloadSchema = Type.Object(
  {
    action: CommandActionSchema,
    failedAt: TimestampSchema,
    error: TerminalErrorSchema,
  },
  { additionalProperties: false },
);

export const CommandErrorMessageSchema = Type.Object(
  {
    version: Type.Literal(PROTOCOL_VERSION),
    type: Type.Literal("command_error"),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sessionId: Type.Optional(SessionIdSchema),
    timestamp: TimestampSchema,
    payload: CommandErrorPayloadSchema,
  },
  { additionalProperties: false },
);

export const RelayDeviceInboundMessageSchema = Type.Union([
  RelayHelloMessageSchema,
  RelayHeartbeatMessageSchema,
  RelayPhoneStateUpdateMessageSchema,
  CommandAckMessageSchema,
  CommandStatusMessageSchema,
  CommandResultMessageSchema,
  CommandErrorMessageSchema,
]);

export const RelayDeviceOutboundMessageSchema = Type.Union([
  RelayHelloAckMessageSchema,
  CommandDispatchMessageSchema,
  CommandCancelMessageSchema,
]);

export type RelayHelloPayload = Static<typeof RelayHelloPayloadSchema>;
export type RelayHelloMessage = Static<typeof RelayHelloMessageSchema>;
export type RelayHeartbeatPayload = Static<typeof RelayHeartbeatPayloadSchema>;
export type RelayHeartbeatMessage = Static<typeof RelayHeartbeatMessageSchema>;
export type RelayPhoneStateUpdatePayload = Static<typeof RelayPhoneStateUpdatePayloadSchema>;
export type RelayPhoneStateUpdateMessage = Static<typeof RelayPhoneStateUpdateMessageSchema>;
export type RelayHelloAckPayload = Static<typeof RelayHelloAckPayloadSchema>;
export type RelayHelloAckMessage = Static<typeof RelayHelloAckMessageSchema>;
export type CommandDispatchImage = Static<typeof CommandDispatchImageSchema>;
export type DisplayTextCommandDispatchPayload = Static<typeof DisplayTextCommandDispatchPayloadSchema>;
export type CapturePhotoCommandDispatchPayload = Static<typeof CapturePhotoCommandDispatchPayloadSchema>;
export type CommandDispatchPayload = Static<typeof CommandDispatchPayloadSchema>;
export type CommandDispatchMessage = Static<typeof CommandDispatchMessageSchema>;
export type CommandCancelPayload = Static<typeof CommandCancelPayloadSchema>;
export type CommandCancelMessage = Static<typeof CommandCancelMessageSchema>;
export type CommandAckPayload = Static<typeof CommandAckPayloadSchema>;
export type CommandAckMessage = Static<typeof CommandAckMessageSchema>;
export type CommandExecutionStatus = Static<typeof CommandExecutionStatusSchema>;
export type CommandStatusImageProgress = Static<typeof CommandStatusImageProgressSchema>;
export type CommandStatusPayload = Static<typeof CommandStatusPayloadSchema>;
export type CommandStatusMessage = Static<typeof CommandStatusMessageSchema>;
export type CommandResultPayload = Static<typeof CommandResultPayloadSchema>;
export type CommandResultMessage = Static<typeof CommandResultMessageSchema>;
export type CommandErrorPayload = Static<typeof CommandErrorPayloadSchema>;
export type CommandErrorMessage = Static<typeof CommandErrorMessageSchema>;
export type RelayDeviceInboundMessage = Static<typeof RelayDeviceInboundMessageSchema>;
export type RelayDeviceOutboundMessage = Static<typeof RelayDeviceOutboundMessageSchema>;
