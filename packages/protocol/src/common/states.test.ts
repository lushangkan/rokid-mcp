import { describe, expect, test } from "bun:test";
import { Value } from "@sinclair/typebox/value";

import {
  CapabilitiesSchema,
  CapabilitySchema,
  CommandStatusSchema,
  DeviceSessionStateSchema,
  ImageStatusSchema,
  RuntimeStateSchema,
  SetupStateSchema,
  UplinkStateSchema,
} from "./states.js";

describe("state schemas", () => {
  test("SetupStateSchema accepts INITIALIZED", () => {
    expect(Value.Check(SetupStateSchema, "INITIALIZED")).toBe(true);
  });

  test("RuntimeStateSchema accepts READY", () => {
    expect(Value.Check(RuntimeStateSchema, "READY")).toBe(true);
  });

  test("UplinkStateSchema rejects BROKEN", () => {
    expect(Value.Check(UplinkStateSchema, "BROKEN")).toBe(false);
  });

  test("DeviceSessionStateSchema accepts STALE", () => {
    expect(Value.Check(DeviceSessionStateSchema, "STALE")).toBe(true);
  });

  test("CommandStatusSchema accepts CANCELLED and rejects IMAGE_UPLOADING", () => {
    expect(Value.Check(CommandStatusSchema, "CANCELLED")).toBe(true);
    expect(Value.Check(CommandStatusSchema, "IMAGE_UPLOADING")).toBe(false);
  });

  test("ImageStatusSchema accepts UPLOADED and rejects EXPIRED", () => {
    expect(Value.Check(ImageStatusSchema, "UPLOADED")).toBe(true);
    expect(Value.Check(ImageStatusSchema, "EXPIRED")).toBe(false);
  });

  test("CapabilitiesSchema accepts list and CapabilitySchema accepts capture_photo", () => {
    expect(
      Value.Check(CapabilitiesSchema, ["display_text", "capture_photo"]),
    ).toBe(true);
    expect(Value.Check(CapabilitySchema, "capture_photo")).toBe(true);
  });
});
