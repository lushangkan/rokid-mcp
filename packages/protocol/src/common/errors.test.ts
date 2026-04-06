import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";

import {
  ErrorResponseSchema,
  TerminalErrorCodeSchema,
  TerminalErrorSchema,
} from "./errors.js";

describe("error schemas", () => {
  test("valid error response should pass", () => {
    const value = {
      ok: false,
      error: {
        code: "ERR_TIMEOUT",
        message: "Request timed out",
        retryable: true,
        details: {
          attempt: 3,
          reason: "network",
        },
      },
      timestamp: 1710000000,
    };

    expect(Value.Check(ErrorResponseSchema, value)).toBe(true);
  });

  test("terminal errors accept frozen terminal codes", () => {
    expect(Value.Check(TerminalErrorCodeSchema, "TIMEOUT")).toBe(true);
    expect(
      Value.Check(TerminalErrorSchema, {
        code: "UPLOAD_FAILED",
        message: "phone upload failed",
        retryable: true,
      }),
    ).toBe(true);
  });

  test("terminal errors reject unsupported codes", () => {
    expect(Value.Check(TerminalErrorCodeSchema, "DEVICE_BUSY")).toBe(false);
  });

  test("invalid details shape should fail", () => {
    const value = {
      ok: false,
      error: {
        code: "ERR_TIMEOUT",
        message: "Request timed out",
        retryable: true,
        details: "not-an-object",
      },
      timestamp: 1710000000,
    };

    expect(Value.Check(ErrorResponseSchema, value)).toBe(false);
  });

  test("invalid ok literal should fail", () => {
    const value = {
      ok: true,
      error: {
        code: "ERR_TIMEOUT",
        message: "Request timed out",
        retryable: true,
      },
      timestamp: 1710000000,
    };

    expect(Value.Check(ErrorResponseSchema, value)).toBe(false);
  });

  test("extra outer properties should fail", () => {
    const value = {
      ok: false,
      error: {
        code: "ERR_TIMEOUT",
        message: "Request timed out",
        retryable: true,
      },
      timestamp: 1710000000,
      unexpected: "drift",
    };

    expect(Value.Check(ErrorResponseSchema, value)).toBe(false);
  });

  test("extra nested error properties should fail", () => {
    const value = {
      ok: false,
      error: {
        code: "ERR_TIMEOUT",
        message: "Request timed out",
        retryable: true,
        internal: "drift",
      },
      timestamp: 1710000000,
    };

    expect(Value.Check(ErrorResponseSchema, value)).toBe(false);
  });
});
