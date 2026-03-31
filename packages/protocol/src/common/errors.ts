import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

import { TimestampSchema } from "./scalar.js";

export const ErrorResponseSchema = Type.Object({
  ok: Type.Literal(false),
  error: Type.Object({
    code: Type.String(),
    message: Type.String(),
    retryable: Type.Boolean(),
    details: Type.Optional(Type.Unknown()),
  }),
  timestamp: TimestampSchema,
});

export type ErrorResponse = Static<typeof ErrorResponseSchema>;
