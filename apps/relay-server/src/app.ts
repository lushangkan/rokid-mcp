import { PROTOCOL_NAME, PROTOCOL_VERSION } from "@rokid-mcp/protocol";
import { Elysia } from "elysia";

export function createApp() {
  return new Elysia().get("/health", () => ({
    ok: true,
    service: "relay-server",
    protocol: PROTOCOL_NAME,
    version: PROTOCOL_VERSION
  }));
}
