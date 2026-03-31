import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

export const PROTOCOL_NAME = "rokid-relay-protocol";
export const PROTOCOL_VERSION = "1.0" as const;

export const DeviceIdSchema = Type.String({
  pattern: "^[a-zA-Z0-9._-]{3,64}$",
});

export const SessionIdSchema = Type.String({
  pattern: "^ses_[a-zA-Z0-9_-]{6,128}$",
});

export const TimestampSchema = Type.Number({ minimum: 1 });

export const NullableStringSchema = Type.Union([Type.String(), Type.Null()]);

export type DeviceId = Static<typeof DeviceIdSchema>;
export type SessionId = Static<typeof SessionIdSchema>;
export type Timestamp = Static<typeof TimestampSchema>;
