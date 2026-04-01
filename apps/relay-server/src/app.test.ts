import { describe, expect, test } from "bun:test";
import { createApp } from "./app.js";
import { readRelayEnv } from "./config/env.js";

describe("relay app", () => {
  test("health route responds", async () => {
    const app = createApp();
    const response = await app.handle(new Request("http://localhost/health"));
    const json = await response.json();

    expect(response.status).toBe(200);
    expect(json.ok).toBe(true);
    expect(json.service).toBe("relay-server");
    expect(typeof json.protocol).toBe("string");
    expect(json.protocol.length).toBeGreaterThan(0);
    expect(typeof json.version).toBe("string");
    expect(json.version).toMatch(/^\d+\.\d+$/);
  });

  test("unknown route is not successful", async () => {
    const app = createApp();
    const response = await app.handle(new Request("http://localhost/not-found"));

    expect(response.status).not.toBe(200);
  });
});

describe("readRelayEnv", () => {
  test("throws on invalid numeric values", () => {
    expect(() =>
      readRelayEnv({
        PORT: "abc",
        HOST: "127.0.0.1",
        RELAY_HEARTBEAT_INTERVAL_MS: "5000",
        RELAY_HEARTBEAT_TIMEOUT_MS: "15000"
      })
    ).toThrow("Invalid numeric environment variable: PORT");
  });
});
