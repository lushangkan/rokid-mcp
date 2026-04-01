import { Value } from "@sinclair/typebox/value";
import {
  ErrorResponseSchema,
  type ErrorResponse,
  GetDeviceStatusResponseSchema,
  type GetDeviceStatusResponse,
} from "../../../protocol/src/index.js";

type RelayValidationError = ErrorResponse["error"];

export type RelayValidationResult =
  | {
      ok: true;
      value: GetDeviceStatusResponse;
    }
  | {
      ok: false;
      error: RelayValidationError;
    };

export function validateRelayGetDeviceStatusResponse(value: unknown): RelayValidationResult {
  if (Value.Check(GetDeviceStatusResponseSchema, value)) {
    return {
      ok: true,
      value,
    };
  }

  if (Value.Check(ErrorResponseSchema, value)) {
    return {
      ok: false,
      error: value.error,
    };
  }

  return {
    ok: false,
    error: {
      code: "MCP_RELAY_RESPONSE_INVALID",
      message: "Relay response schema mismatch",
      retryable: false,
    },
  };
}
