import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";

import {
  DeviceIdSchema,
  ImageIdSchema,
  NullableStringSchema,
  RequestIdSchema,
  Sha256Schema,
  SessionIdSchema,
  TimestampSchema,
  TransferIdSchema,
  UploadTokenSchema,
} from "./scalar.js";

describe("scalar schemas", () => {
  test("DeviceIdSchema accepts rokid_glasses_01", () => {
    expect(Value.Check(DeviceIdSchema, "rokid_glasses_01")).toBe(true);
  });

  test("SessionIdSchema rejects bad-session", () => {
    expect(Value.Check(SessionIdSchema, "bad-session")).toBe(false);
  });

  test("request, image, and transfer ids accept protocol prefixes", () => {
    expect(Value.Check(RequestIdSchema, "req_abc123")).toBe(true);
    expect(Value.Check(ImageIdSchema, "img_abc123")).toBe(true);
    expect(Value.Check(TransferIdSchema, "trf_abc123")).toBe(true);
  });

  test("UploadTokenSchema and Sha256Schema reject malformed values", () => {
    expect(Value.Check(UploadTokenSchema, "short-token")).toBe(false);
    expect(Value.Check(Sha256Schema, "not-a-checksum")).toBe(false);
  });

  test("TimestampSchema rejects 0", () => {
    expect(Value.Check(TimestampSchema, 0)).toBe(false);
  });

  test("TimestampSchema rejects fractional milliseconds", () => {
    expect(Value.Check(TimestampSchema, 1.5)).toBe(false);
  });

  test("NullableStringSchema accepts null and ERR_TIMEOUT", () => {
    expect(Value.Check(NullableStringSchema, null)).toBe(true);
    expect(Value.Check(NullableStringSchema, "ERR_TIMEOUT")).toBe(true);
  });
});
