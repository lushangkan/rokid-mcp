import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";

import {
  DeviceIdSchema,
  NullableStringSchema,
  SessionIdSchema,
  TimestampSchema,
} from "./scalar.js";

describe("scalar schemas", () => {
  test("DeviceIdSchema accepts rokid_glasses_01", () => {
    expect(Value.Check(DeviceIdSchema, "rokid_glasses_01")).toBe(true);
  });

  test("SessionIdSchema rejects bad-session", () => {
    expect(Value.Check(SessionIdSchema, "bad-session")).toBe(false);
  });

  test("TimestampSchema rejects 0", () => {
    expect(Value.Check(TimestampSchema, 0)).toBe(false);
  });

  test("NullableStringSchema accepts null and ERR_TIMEOUT", () => {
    expect(Value.Check(NullableStringSchema, null)).toBe(true);
    expect(Value.Check(NullableStringSchema, "ERR_TIMEOUT")).toBe(true);
  });
});
