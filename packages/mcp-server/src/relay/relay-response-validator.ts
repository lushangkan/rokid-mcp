import type { TSchema } from "@sinclair/typebox";
import { Value } from "@sinclair/typebox/value";
import {
  ErrorResponseSchema,
  GetCommandStatusResponseSchema,
  type GetCommandStatusResponse,
  GetDeviceStatusResponseSchema,
  type GetDeviceStatusResponse,
  SubmitCommandResponseSchema,
  type SubmitCommandResponse,
} from "@rokid-mcp/protocol";

type RelayValidationError = {
  code: string;
  message: string;
  retryable: boolean;
  details?: Record<string, unknown>;
};

export type RelayValidationResult<T> =
  | {
      ok: true;
      value: T;
    }
  | {
      ok: false;
      error: RelayValidationError;
    };

export function validateRelayGetDeviceStatusResponse(value: unknown): RelayValidationResult<GetDeviceStatusResponse> {
  return validateRelayResponse(value, GetDeviceStatusResponseSchema);
}

export function validateRelaySubmitCommandResponse(value: unknown): RelayValidationResult<SubmitCommandResponse> {
  return validateRelayResponse(value, SubmitCommandResponseSchema);
}

export function validateRelayGetCommandStatusResponse(value: unknown): RelayValidationResult<GetCommandStatusResponse> {
  return validateRelayResponse(value, GetCommandStatusResponseSchema);
}

function validateRelayResponse<T>(value: unknown, successSchema: TSchema): RelayValidationResult<T> {
  if (Value.Check(successSchema, value)) {
    return {
      ok: true,
      value: value as T,
    };
  }

  const relayError = toRelayError(value);
  if (relayError) {
    return {
      ok: false,
      error: relayError,
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

function toRelayError(value: unknown): RelayValidationError | null {
  if (!Value.Check(ErrorResponseSchema, value)) {
    return null;
  }

  return (value as { error: RelayValidationError }).error;
}
