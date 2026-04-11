import { describe, expect, test } from "bun:test";

import { readRelayEnv } from "./env.ts";

const REQUIRED_AUTH_ENV = {
  RELAY_HTTP_AUTH_TOKENS: "mcp-token-1",
  RELAY_WS_AUTH_TOKENS: "phone-token-1",
} as const;

describe("readRelayEnv", () => {
  test("returns normalized auth env with default hello timeout", () => {
    const relayEnv = readRelayEnv({
      ...REQUIRED_AUTH_ENV,
      RELAY_HTTP_AUTH_TOKENS: "  mcp-token-1, mcp-token-2 ,mcp-token-1 ",
      RELAY_WS_AUTH_TOKENS: " phone-token-1 ",
    });

    expect(relayEnv.port).toBe(3000);
    expect(relayEnv.host).toBe("0.0.0.0");
    expect(relayEnv.heartbeatIntervalMs).toBe(5000);
    expect(relayEnv.heartbeatTimeoutMs).toBe(15000);
    expect(relayEnv.httpAuthTokens).toEqual(["mcp-token-1", "mcp-token-2"]);
    expect(relayEnv.wsAuthTokens).toEqual(["phone-token-1"]);
    expect(relayEnv.helloTimeoutMs).toBe(5000);
  });

  test("throws when RELAY_HTTP_AUTH_TOKENS is missing", () => {
    expect(() =>
      readRelayEnv({
        RELAY_WS_AUTH_TOKENS: "phone-token-1",
      })
    ).toThrow("Missing required environment variable: RELAY_HTTP_AUTH_TOKENS");
  });

  test("throws when RELAY_WS_AUTH_TOKENS is missing", () => {
    expect(() =>
      readRelayEnv({
        RELAY_HTTP_AUTH_TOKENS: "mcp-token-1",
      })
    ).toThrow("Missing required environment variable: RELAY_WS_AUTH_TOKENS");
  });

  test("throws when auth token lists contain blank entries", () => {
    expect(() =>
      readRelayEnv({
        ...REQUIRED_AUTH_ENV,
        RELAY_HTTP_AUTH_TOKENS: "mcp-token-1,,mcp-token-2",
      })
    ).toThrow("Invalid environment variable RELAY_HTTP_AUTH_TOKENS");

    expect(() =>
      readRelayEnv({
        ...REQUIRED_AUTH_ENV,
        RELAY_WS_AUTH_TOKENS: "phone-token-1,   ",
      })
    ).toThrow("Invalid environment variable RELAY_WS_AUTH_TOKENS");
  });

  test("parses an explicit hello timeout override", () => {
    const relayEnv = readRelayEnv({
      ...REQUIRED_AUTH_ENV,
      RELAY_WS_HELLO_TIMEOUT_MS: "7500",
    });

    expect(relayEnv.helloTimeoutMs).toBe(7500);
  });

  test("throws when hello timeout is not a positive integer", () => {
    expect(() =>
      readRelayEnv({
        ...REQUIRED_AUTH_ENV,
        RELAY_WS_HELLO_TIMEOUT_MS: "0",
      })
    ).toThrow("Environment variable RELAY_WS_HELLO_TIMEOUT_MS must be a positive integer");

    expect(() =>
      readRelayEnv({
        ...REQUIRED_AUTH_ENV,
        RELAY_WS_HELLO_TIMEOUT_MS: "3.14",
      })
    ).toThrow("Environment variable RELAY_WS_HELLO_TIMEOUT_MS must be a positive integer");
  });

  test("throws on invalid numeric values", () => {
    expect(() =>
      readRelayEnv({
        ...REQUIRED_AUTH_ENV,
        PORT: "abc",
        HOST: "127.0.0.1",
        RELAY_HEARTBEAT_INTERVAL_MS: "5000",
        RELAY_HEARTBEAT_TIMEOUT_MS: "15000",
      })
    ).toThrow("Invalid numeric environment variable: PORT");
  });
});
