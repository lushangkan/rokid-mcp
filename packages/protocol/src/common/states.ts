import { Type } from "@sinclair/typebox";
import type { Static } from "@sinclair/typebox";

export const SetupStateSchema = Type.Union([
  Type.Literal("UNINITIALIZED"),
  Type.Literal("INITIALIZED"),
]);

export const RuntimeStateSchema = Type.Union([
  Type.Literal("DISCONNECTED"),
  Type.Literal("CONNECTING"),
  Type.Literal("READY"),
  Type.Literal("BUSY"),
  Type.Literal("ERROR"),
]);

export const UplinkStateSchema = Type.Union([
  Type.Literal("OFFLINE"),
  Type.Literal("CONNECTING"),
  Type.Literal("ONLINE"),
  Type.Literal("ERROR"),
]);

export const DeviceSessionStateSchema = Type.Union([
  Type.Literal("OFFLINE"),
  Type.Literal("ONLINE"),
  Type.Literal("STALE"),
  Type.Literal("CLOSED"),
]);

export const CommandStatusSchema = Type.Union([
  Type.Literal("CREATED"),
  Type.Literal("DISPATCHED_TO_PHONE"),
  Type.Literal("ACKNOWLEDGED_BY_PHONE"),
  Type.Literal("RUNNING"),
  Type.Literal("COMPLETED"),
  Type.Literal("FAILED"),
  Type.Literal("TIMEOUT"),
  Type.Literal("CANCELLED"),
]);

export const ImageStatusSchema = Type.Union([
  Type.Literal("RESERVED"),
  Type.Literal("UPLOADING"),
  Type.Literal("UPLOADED"),
  Type.Literal("FAILED"),
]);

export const CapabilitySchema = Type.Union([
  Type.Literal("display_text"),
  Type.Literal("capture_photo"),
]);

export const CapabilitiesSchema = Type.Array(CapabilitySchema, { minItems: 0 });

export type SetupState = Static<typeof SetupStateSchema>;
export type RuntimeState = Static<typeof RuntimeStateSchema>;
export type UplinkState = Static<typeof UplinkStateSchema>;
export type DeviceSessionState = Static<typeof DeviceSessionStateSchema>;
export type CommandStatus = Static<typeof CommandStatusSchema>;
export type ImageStatus = Static<typeof ImageStatusSchema>;
export type Capability = Static<typeof CapabilitySchema>;
export type Capabilities = Static<typeof CapabilitiesSchema>;
