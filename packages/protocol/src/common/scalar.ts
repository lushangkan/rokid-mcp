import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import { PROTOCOL_NAME, PROTOCOL_VERSION } from "./constants.js";

export { PROTOCOL_NAME, PROTOCOL_VERSION };

export const DeviceIdSchema = Type.String({
  pattern: "^[a-zA-Z0-9._-]{3,64}$",
});

export const RequestIdSchema = Type.String({
  pattern: "^req_[a-zA-Z0-9_-]{6,128}$",
});

export const SessionIdSchema = Type.String({
  pattern: "^ses_[a-zA-Z0-9_-]{6,128}$",
});

export const ImageIdSchema = Type.String({
  pattern: "^img_[a-zA-Z0-9_-]{6,128}$",
});

export const TransferIdSchema = Type.String({
  pattern: "^trf_[a-zA-Z0-9_-]{6,128}$",
});

export const UploadTokenSchema = Type.String({
  minLength: 24,
});

export const Sha256Schema = Type.String({
  pattern: "^[a-f0-9]{64}$",
});

export const TimestampSchema = Type.Integer({ minimum: 1 });

export const NullableStringSchema = Type.Union([Type.String(), Type.Null()]);

export type DeviceId = Static<typeof DeviceIdSchema>;
export type RequestId = Static<typeof RequestIdSchema>;
export type SessionId = Static<typeof SessionIdSchema>;
export type ImageId = Static<typeof ImageIdSchema>;
export type TransferId = Static<typeof TransferIdSchema>;
export type UploadToken = Static<typeof UploadTokenSchema>;
export type Sha256 = Static<typeof Sha256Schema>;
export type Timestamp = Static<typeof TimestampSchema>;
