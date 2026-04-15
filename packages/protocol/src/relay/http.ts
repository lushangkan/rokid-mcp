import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import {
  DEFAULT_IMAGE_CONTENT_TYPE,
  MAX_DISPLAY_TEXT_DURATION_MS,
  MAX_DISPLAY_TEXT_LENGTH,
  CapabilitiesSchema,
  CommandStatusSchema,
  DeviceIdSchema,
  DeviceSessionStateSchema,
  ErrorResponseSchema,
  ImageIdSchema,
  ImageStatusSchema,
  NullableStringSchema,
  RequestIdSchema,
  RuntimeStateSchema,
  SessionIdSchema,
  Sha256Schema,
  SetupStateSchema,
  TimestampSchema,
  TerminalErrorSchema,
  TransferIdSchema,
  UploadTokenSchema,
} from "../common/index.js";

export const CommandActionSchema = Type.Union([
  Type.Literal("display_text"),
  Type.Literal("capture_photo"),
]);

export const CapturePhotoQualitySchema = Type.Union([
  Type.Literal("low"),
  Type.Literal("medium"),
  Type.Literal("high"),
]);

export const ImageContentTypeSchema = Type.Literal(DEFAULT_IMAGE_CONTENT_TYPE);

export const StatusUrlSchema = Type.String({ minLength: 1 });

export const DisplayTextCommandPayloadSchema = Type.Object(
  {
    text: Type.String({ minLength: 1, maxLength: MAX_DISPLAY_TEXT_LENGTH }),
    durationMs: Type.Integer({ minimum: 1, maximum: MAX_DISPLAY_TEXT_DURATION_MS }),
  },
  { additionalProperties: false },
);

export const CapturePhotoCommandPayloadSchema = Type.Object(
  {
    quality: Type.Optional(CapturePhotoQualitySchema),
  },
  { additionalProperties: false },
);

export const SubmitDisplayTextCommandRequestSchema = Type.Object(
  {
    deviceId: DeviceIdSchema,
    action: Type.Literal("display_text"),
    payload: DisplayTextCommandPayloadSchema,
  },
  { additionalProperties: false },
);

export const SubmitCapturePhotoCommandRequestSchema = Type.Object(
  {
    deviceId: DeviceIdSchema,
    action: Type.Literal("capture_photo"),
    payload: CapturePhotoCommandPayloadSchema,
  },
  { additionalProperties: false },
);

export const SubmitCommandRequestSchema = Type.Union([
  SubmitDisplayTextCommandRequestSchema,
  SubmitCapturePhotoCommandRequestSchema,
]);

export const CommandSubmissionStatusSchema = Type.Union([
  Type.Literal("CREATED"),
  Type.Literal("DISPATCHED_TO_PHONE"),
]);

export const ReservedImageSchema = Type.Object(
  {
    imageId: ImageIdSchema,
    transferId: TransferIdSchema,
    status: Type.Literal("RESERVED"),
    mimeType: ImageContentTypeSchema,
    expiresAt: TimestampSchema,
  },
  { additionalProperties: false },
);

export const SubmitDisplayTextCommandResponseSchema = Type.Object(
  {
    ok: Type.Literal(true),
    requestId: RequestIdSchema,
    deviceId: DeviceIdSchema,
    action: Type.Literal("display_text"),
    status: CommandSubmissionStatusSchema,
    createdAt: TimestampSchema,
    statusUrl: StatusUrlSchema,
  },
  { additionalProperties: false },
);

export const SubmitCapturePhotoCommandResponseSchema = Type.Object(
  {
    ok: Type.Literal(true),
    requestId: RequestIdSchema,
    deviceId: DeviceIdSchema,
    action: Type.Literal("capture_photo"),
    status: CommandSubmissionStatusSchema,
    createdAt: TimestampSchema,
    statusUrl: StatusUrlSchema,
    image: ReservedImageSchema,
  },
  { additionalProperties: false },
);

export const SubmitCommandResponseSchema = Type.Union([
  SubmitDisplayTextCommandResponseSchema,
  SubmitCapturePhotoCommandResponseSchema,
]);

export const DisplayTextCommandResultSchema = Type.Object(
  {
    action: Type.Literal("display_text"),
    displayed: Type.Literal(true),
    durationMs: Type.Integer({ minimum: 1, maximum: MAX_DISPLAY_TEXT_DURATION_MS }),
  },
  { additionalProperties: false },
);

export const CapturePhotoCommandResultSchema = Type.Object(
  {
    action: Type.Literal("capture_photo"),
    imageId: ImageIdSchema,
    transferId: TransferIdSchema,
    mimeType: ImageContentTypeSchema,
    size: Type.Integer({ minimum: 1 }),
    width: Type.Integer({ minimum: 1 }),
    height: Type.Integer({ minimum: 1 }),
    sha256: Type.Optional(Sha256Schema),
  },
  { additionalProperties: false },
);

export const CommandResultSchema = Type.Union([
  DisplayTextCommandResultSchema,
  CapturePhotoCommandResultSchema,
]);

export const CommandImageSchema = Type.Object(
  {
    imageId: ImageIdSchema,
    transferId: TransferIdSchema,
    status: ImageStatusSchema,
    mimeType: ImageContentTypeSchema,
    size: Type.Optional(Type.Integer({ minimum: 1 })),
    width: Type.Optional(Type.Integer({ minimum: 1 })),
    height: Type.Optional(Type.Integer({ minimum: 1 })),
    sha256: Type.Optional(Sha256Schema),
    expiresAt: Type.Optional(TimestampSchema),
    uploadedAt: Type.Optional(TimestampSchema),
  },
  { additionalProperties: false },
);

export const CommandRecordSchema = Type.Object(
  {
    requestId: RequestIdSchema,
    deviceId: DeviceIdSchema,
    action: CommandActionSchema,
    status: CommandStatusSchema,
    createdAt: TimestampSchema,
    updatedAt: TimestampSchema,
    acknowledgedAt: Type.Union([TimestampSchema, Type.Null()]),
    completedAt: Type.Union([TimestampSchema, Type.Null()]),
    cancelledAt: Type.Union([TimestampSchema, Type.Null()]),
    result: Type.Union([CommandResultSchema, Type.Null()]),
    error: Type.Union([TerminalErrorSchema, Type.Null()]),
    image: Type.Union([CommandImageSchema, Type.Null()]),
  },
  { additionalProperties: false },
);

export const CommandStatusResponseSchema = Type.Object(
  {
    ok: Type.Literal(true),
    command: CommandRecordSchema,
    timestamp: TimestampSchema,
  },
  { additionalProperties: false },
);

export const GetCommandStatusResponseSchema = CommandStatusResponseSchema;

export const GetDeviceStatusParamsSchema = Type.Object(
  {
    deviceId: DeviceIdSchema,
  },
  { additionalProperties: false },
);

export const GetDeviceStatusDeviceSchema = Type.Object(
  {
    deviceId: DeviceIdSchema,
    connected: Type.Boolean(),
    sessionState: DeviceSessionStateSchema,
    setupState: SetupStateSchema,
    runtimeState: RuntimeStateSchema,
    capabilities: CapabilitiesSchema,
    activeCommandRequestId: Type.Union([RequestIdSchema, Type.Null()]),
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
    device: GetDeviceStatusDeviceSchema,
    timestamp: TimestampSchema,
  },
  { additionalProperties: false },
);

export const ImageUploadHeadersSchema = Type.Object(
  {
    contentType: ImageContentTypeSchema,
    contentLength: Type.Optional(Type.Integer({ minimum: 1 })),
    deviceId: DeviceIdSchema,
    requestId: RequestIdSchema,
    sha256: Type.Optional(Sha256Schema),
  },
  { additionalProperties: false },
);

export const ImageUploadRequestSchema = Type.Object(
  {
    imageId: ImageIdSchema,
    transferId: TransferIdSchema,
    uploadToken: UploadTokenSchema,
    headers: ImageUploadHeadersSchema,
  },
  { additionalProperties: false },
);

export const ImageUploadResponseSchema = Type.Object(
  {
    ok: Type.Literal(true),
    image: Type.Object(
      {
        imageId: ImageIdSchema,
        transferId: TransferIdSchema,
        status: Type.Literal("UPLOADED"),
        mimeType: ImageContentTypeSchema,
        size: Type.Integer({ minimum: 1 }),
        sha256: Type.Optional(Sha256Schema),
        uploadedAt: TimestampSchema,
      },
      { additionalProperties: false },
    ),
    timestamp: TimestampSchema,
  },
  { additionalProperties: false },
);

export const ImageDownloadResponseSchema = Type.Object(
  {
    ok: Type.Literal(true),
    image: Type.Object(
      {
        imageId: ImageIdSchema,
        transferId: TransferIdSchema,
        status: Type.Literal("UPLOADED"),
        mimeType: ImageContentTypeSchema,
        size: Type.Integer({ minimum: 1 }),
        sha256: Type.Optional(Sha256Schema),
      },
      { additionalProperties: false },
    ),
    timestamp: TimestampSchema,
  },
  { additionalProperties: false },
);

export const RelaySuccessOrErrorResponseSchema = Type.Union([
  GetDeviceStatusResponseSchema,
  SubmitCommandResponseSchema,
  CommandStatusResponseSchema,
  ImageUploadResponseSchema,
  ImageDownloadResponseSchema,
  ErrorResponseSchema,
]);

export type CommandAction = Static<typeof CommandActionSchema>;
export type CapturePhotoQuality = Static<typeof CapturePhotoQualitySchema>;
export type ImageContentType = Static<typeof ImageContentTypeSchema>;
export type DisplayTextCommandPayload = Static<typeof DisplayTextCommandPayloadSchema>;
export type CapturePhotoCommandPayload = Static<typeof CapturePhotoCommandPayloadSchema>;
export type SubmitDisplayTextCommandRequest = Static<typeof SubmitDisplayTextCommandRequestSchema>;
export type SubmitCapturePhotoCommandRequest = Static<typeof SubmitCapturePhotoCommandRequestSchema>;
export type SubmitCommandRequest = Static<typeof SubmitCommandRequestSchema>;
export type ReservedImage = Static<typeof ReservedImageSchema>;
export type SubmitDisplayTextCommandResponse = Static<typeof SubmitDisplayTextCommandResponseSchema>;
export type SubmitCapturePhotoCommandResponse = Static<typeof SubmitCapturePhotoCommandResponseSchema>;
export type SubmitCommandResponse = Static<typeof SubmitCommandResponseSchema>;
export type DisplayTextCommandResult = Static<typeof DisplayTextCommandResultSchema>;
export type CapturePhotoCommandResult = Static<typeof CapturePhotoCommandResultSchema>;
export type CommandResult = Static<typeof CommandResultSchema>;
export type CommandImage = Static<typeof CommandImageSchema>;
export type CommandRecord = Static<typeof CommandRecordSchema>;
export type CommandStatusResponse = Static<typeof CommandStatusResponseSchema>;
export type GetCommandStatusResponse = Static<typeof GetCommandStatusResponseSchema>;
export type GetDeviceStatusParams = Static<typeof GetDeviceStatusParamsSchema>;
export type GetDeviceStatusDevice = Static<typeof GetDeviceStatusDeviceSchema>;
export type GetDeviceStatusResponse = Static<typeof GetDeviceStatusResponseSchema>;
export type ImageUploadHeaders = Static<typeof ImageUploadHeadersSchema>;
export type ImageUploadRequest = Static<typeof ImageUploadRequestSchema>;
export type ImageUploadResponse = Static<typeof ImageUploadResponseSchema>;
export type ImageDownloadResponse = Static<typeof ImageDownloadResponseSchema>;
export type RelaySuccessOrErrorResponse = Static<typeof RelaySuccessOrErrorResponseSchema>;
