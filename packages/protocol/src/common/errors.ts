import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import { TimestampSchema } from "./scalar.js";

export const ErrorDetailsSchema = Type.Record(Type.String({ minLength: 1 }), Type.Unknown());

export const TerminalErrorCodeSchema = Type.Union([
  Type.Literal("TIMEOUT"),
  Type.Literal("BLUETOOTH_UNAVAILABLE"),
  Type.Literal("UPLOAD_FAILED"),
  Type.Literal("CHECKSUM_MISMATCH"),
  Type.Literal("UNSUPPORTED_OPERATION"),
]);

export const TerminalErrorSchema = Type.Object(
  {
    code: TerminalErrorCodeSchema,
    message: Type.String({ minLength: 1 }),
    retryable: Type.Boolean(),
    details: Type.Optional(ErrorDetailsSchema),
  },
  {
    additionalProperties: false,
  },
);

export const ErrorResponseSchema = Type.Object({
  ok: Type.Literal(false),
  error: Type.Object({
    code: Type.String({ minLength: 1 }),
    message: Type.String({ minLength: 1 }),
    retryable: Type.Boolean(),
    details: Type.Optional(ErrorDetailsSchema),
  }, {
    additionalProperties: false,
  }),
  timestamp: TimestampSchema,
}, {
  additionalProperties: false,
});

export type TerminalErrorCode = Static<typeof TerminalErrorCodeSchema>;
export type TerminalError = Static<typeof TerminalErrorSchema>;
export type ErrorResponse = Static<typeof ErrorResponseSchema>;
